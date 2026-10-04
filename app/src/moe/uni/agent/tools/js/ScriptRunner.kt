package moe.uni.agent.tools.js

import android.os.ParcelFileDescriptor
import android.os.ServiceSpecificException
import android.os.SystemClock
import android.util.Log
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.framework.ScreenResult
import moe.uni.agent.framework.ToolError
import moe.uni.agent.storage.UserStorage
import moe.uni.agent.tools.ToolFailure
import moe.uni.agent.tools.ToolOutput
import moe.uni.agent.tools.ToolResult
import moe.uni.agent.tools.file.FileOps
import moe.uni.agent.tools.file.FileTools
import moe.uni.agent.tools.screen.ScreenTools
import moe.uni.agent.tools.shell.OutputCapture
import moe.uni.agent.tools.shell.ShellTools
import moe.uni.agent.tools.shell.Executor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * exec_js 的执行（ADR-0024）：在当前会话的 QuickJS 运行时里执行脚本。sh 走 agentd（与 exec 相同），fs 与文件工具相同，
 * 屏幕操作经 IAgentScreen 调用 AgentFramework，中间动作不回传状态和截图。结果是 JSON（ADR-0030 第 5 条）：
 * 成败、耗时、log、脚本的值、事件，执行过屏幕动作时再附上最终状态；截图是 display() 的截图和最终状态的截图。
 */
class ScriptRunner(
    /** 用户没有开启 root shell 时为 null，脚本里的 sh() 报错（ADR-0029 第 4 条）。 */
    private val shell: Executor?,
    private val files: FileOps,
    private val connect: () -> IAgentScreen,
    private val engine: () -> ScriptEngine,
    /** tool() 调用的工具，与模型可用的工具相同（同一个 ToolRegistry）。 */
    private val invokeTool: (String, JSONObject) -> ToolResult,
) {
    @Volatile private var current: Run? = null

    /** 停止：打断正在执行的脚本；正在执行的 sh 由 Executor.cancel 整组 kill。 */
    fun cancel() {
        current?.cancel()
    }

    fun run(code: String, timeoutMs: Long): ToolResult {
        val run = Run(engine()).also { current = it }
        try {
            return run.execute(code, timeoutMs)
        } finally {
            current = null
        }
    }

    /** 一次调用的状态：宿主函数就在这里实现。宿主函数的错误以 ToolFailure 表示，交给脚本时是带 code 的 Error。 */
    private inner class Run(private val engine: ScriptEngine) : ScriptEngine.Host {
        private val log = StringBuilder()
        private var logDropped = false
        private val events = JSONArray()
        private val shots = ArrayList<ByteArray>()
        private val shown = ArrayList<ByteArray>()
        private var screen: IAgentScreen? = null
        private var lastDisplay = ScreenTools.VIRTUAL_DISPLAY

        /** 等待用的事件队列（ADR-0034 第 4 条），运行结束时关闭；waits 是每次 wait 的结局（第 6 条）。 */
        private val waitEvents = ScriptEvents()
        private val waits = JSONArray()

        /** 执行过屏幕动作（不含 find、等待中的查询等只读查询）。 */
        private var acted = false
        private var deadline = 0L
        @Volatile private var cancelled = false

        fun cancel() {
            cancelled = true
            engine.interrupt()
        }

        fun execute(code: String, timeoutMs: Long): ToolResult {
            val start = SystemClock.uptimeMillis()
            deadline = start + timeoutMs
            val outcome = try {
                engine.run(this, code, timeoutMs, shell != null)
            } finally {
                waitEvents.close()
            }
            val elapsed = SystemClock.uptimeMillis() - start
            val result = outcome.uncaught?.let { JSONObject() } ?: JSONObject(outcome.result)
            val out = when {
                cancelled -> ToolOutput.failure(ToolError.CANCELLED, "用户停止了脚本")
                outcome.uncaught != null && elapsed >= timeoutMs -> ToolOutput.failure(ToolError.TIMEOUT, "脚本超过 ${timeoutMs}ms，已中断")
                outcome.uncaught != null -> {
                    // 超时和停止以外 __run 捕获不到的异常（例如构造结果时内存耗尽）。
                    Log.w(TAG, "uncaught: ${outcome.uncaught}")
                    ToolOutput.failure(ToolError.FAILED, "脚本异常终止")
                }
                result.optBoolean(JsHost.OK) -> ToolOutput.ok()
                else -> JSONObject().put(ToolOutput.OK, false).put(ToolOutput.ERROR, result.getJSONObject(JsHost.ERROR))
                    .put(STACK, result.optString(JsHost.STACK).trim().ifEmpty { null })
            }
            out.put(ELAPSED_MS, elapsed)
            if (log.isNotEmpty()) {
                out.put(LOG, log.toString().trimEnd('\n'))
                if (logDropped) out.put(LOG_TRUNCATED, true)
            }
            if (!cancelled && outcome.uncaught == null) value(result.optString(JsHost.VALUE), out)
            if (waits.length() > 0) out.put(WAITS, waits)
            val images = ArrayList(shown)
            // 执行过屏幕动作才附上最终状态和截图；只读查询和纯 shell、文件脚本不带，需要画面时脚本自己 display(screenshot())。
            val s = screen
            var state: JSONObject? = null
            if (s != null && acted && !cancelled) {
                try {
                    val r = s.state(lastDisplay)
                    state = ScreenTools.present(r)
                    images += ScreenTools.images(r)
                } catch (e: ServiceSpecificException) {
                    val failure = ScreenTools.failure(e)
                    out.put(STATE_ERROR, ToolOutput.error(failure.code, failure.message.orEmpty()))
                }
            }
            state?.optJSONArray(ScreenTools.RESULT_EVENTS)?.let { for (i in 0 until it.length()) events.put(it.get(i)) }
            if (events.length() > 0) out.put(ScreenTools.RESULT_EVENTS, events)
            state?.let { st ->
                for (key in listOf(ScreenTools.RESULT_SETTLED, ScreenTools.RESULT_SCREEN, ScreenTools.RESULT_SCREENSHOT_ERROR, ScreenTools.RESULT_TREE)) {
                    st.opt(key)?.let { out.put(key, it) }
                }
            }
            return ToolResult.json(out, images)
        }

        /** 脚本的值（JSON 文本）放进结果；过长时截断成字符串。 */
        private fun value(json: String, out: JSONObject) {
            if (json.isEmpty()) return
            if (json.length > LOG_LIMIT) {
                out.put(VALUE, json.take(LOG_LIMIT)).put(VALUE_TRUNCATED, true)
                return
            }
            out.put(VALUE, try {
                JSONTokener(json).nextValue()
            } catch (e: JSONException) {
                json
            })
        }

        override fun call(name: String, args: ByteArray): ByteArray {
            // 停止之后不再执行任何操作；剩下的 JS 很短时可能在中断检查之前就跑完，所以宿主这边也要拦住。
            if (cancelled) return JsHost.error(ToolError.CANCELLED, "用户停止了脚本")
            return try {
                JsHost.value(invoke(name, JSONObject(String(args, Charsets.UTF_8))))
            } catch (e: Exception) {
                val failure = ToolOutput.failureOf(e)
                JsHost.error(failure.code, failure.message.orEmpty())
            }
        }

        private fun invoke(name: String, a: JSONObject): Any? = when (name) {
            JsHost.LOG -> { append(a.getString(JsHost.ARG_TEXT)); null }
            JsHost.SLEEP -> { sleep(a.getLong(JsHost.ARG_MS)); null }
            JsHost.DISPLAY -> {
                shown += shots.getOrNull(a.optInt(JsHost.ARG_IMAGE, -1))
                    ?: throw ToolFailure(ToolError.INVALID_ARGUMENT, "display() 的参数不是 screenshot() 的返回值")
                null
            }
            JsHost.TOOL -> {
                val tool = a.getString(JsHost.ARG_NAME)
                if (tool == ExecJs.NAME) throw ToolFailure(ToolError.INVALID_ARGUMENT, "${ExecJs.NAME} 里不能调用 ${ExecJs.NAME}")
                JSONObject(invokeTool(tool, a.optJSONObject(JsHost.ARG_ARGS) ?: JSONObject()).output)
            }
            JsHost.SH -> (shell ?: throw ToolFailure(ToolError.PERMISSION_DENIED, ShellTools.ROOT_SHELL_OFF))
                .exec(a.getString(JsHost.ARG_COMMAND), a.optString(JsHost.ARG_CWD), a.optLong(JsHost.ARG_TIMEOUT))
            JsHost.FS_READ -> files.readText(a.getString(FileTools.PATH))
            JsHost.FS_READ_BYTES -> files.readBytes(a)
            JsHost.FS_WRITE -> files.write(a)
            JsHost.FS_EDIT -> files.edit(a)
            JsHost.FS_STAT -> files.stat(a)
            JsHost.FS_LIST -> files.list(a)
            JsHost.MARK -> waitEvents.mark(::connected)
            JsHost.WATCH -> {
                val paths = a.getJSONArray(JsHost.ARG_PATHS)
                if (paths.length() == 0) throw ToolFailure(ToolError.INVALID_ARGUMENT, "watch() 至少要一个路径")
                waitEvents.watch(::connected, (0 until paths.length()).map { paths.getString(it).let { p -> p to UserStorage.resolve(p) } })
            }
            JsHost.UNWATCH -> { waitEvents.unwatch(a.getInt(JsHost.ARG_ID)); null }
            JsHost.WAIT_EVENTS -> {
                // 不超过脚本的截止时间，之后由 QuickJS 的中断回调结束脚本。
                val timeout = minOf(a.getLong(JsHost.ARG_TIMEOUT), deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
                waitEvents.await(a.getLong(JsHost.ARG_SINCE), a.getLong(JsHost.ARG_FROM), timeout) { cancelled }
            }
            JsHost.WAIT_DONE -> { waits.put(a); null }
            else -> try {
                screen(name, a)
            } catch (e: ServiceSpecificException) {
                throw ScreenTools.failure(e)
            }
        }

        private fun connected(): IAgentScreen = screen ?: connect().also { screen = it }

        private fun screen(name: String, a: JSONObject): Any? {
            val s = connected()
            val d = a.optInt(JsHost.ARG_DISPLAY, ScreenTools.VIRTUAL_DISPLAY)
            if (name != JsHost.LIST) lastDisplay = d
            return when (name) {
                JsHost.LIST -> JSONObject(collect(s.list()).data)
                JsHost.QUERY -> JSONObject(s.query(d, a.getJSONObject(JsHost.ARG_SELECTOR).toString(), a.optBoolean(JsHost.ARG_SETTLE, true)))
                JsHost.SCREENSHOT -> {
                    val r = collect(s.state(d))
                    val image = r.image ?: throw r.screenshotError?.let { ToolFailure(it.code, it.message) }
                        ?: ToolFailure(ToolError.UNAVAILABLE, "屏幕 $d 没有截图")
                    shots += read(image)
                    shots.size - 1
                }
                else -> action(s, name, d, a)
            }
        }

        /** 中间动作不要状态和截图（observe 为 0），成功时返回动作做了什么（action 对象），失败时抛错。 */
        private fun action(s: IAgentScreen, name: String, d: Int, a: JSONObject): JSONObject {
            acted = true
            val r: ScreenResult = when (name) {
                JsHost.TAP -> s.tap(d, a.optInt(JsHost.ARG_ELEMENT), a.optInt(JsHost.ARG_X), a.optInt(JsHost.ARG_Y),
                    a.optInt(JsHost.ARG_COUNT, 1), a.optBoolean(JsHost.ARG_LONG), OBSERVE_NONE)
                JsHost.SET_TEXT -> s.setText(d, a.getInt(JsHost.ARG_ELEMENT), a.getString(JsHost.ARG_TEXT), OBSERVE_NONE)
                JsHost.SELECT_TEXT -> s.selectText(d, a.getInt(JsHost.ARG_ELEMENT), a.getString(JsHost.ARG_TEXT),
                    a.optString(JsHost.ARG_PREFIX), a.optString(JsHost.ARG_SUFFIX), a.optString(JsHost.ARG_SELECTION), OBSERVE_NONE)
                JsHost.NODE_ACTION -> s.nodeAction(d, a.getInt(JsHost.ARG_ELEMENT), a.getString(JsHost.ARG_ACTION), OBSERVE_NONE)
                JsHost.SCROLL -> s.scroll(d, a.optInt(JsHost.ARG_ELEMENT), a.getString(JsHost.ARG_DIRECTION),
                    a.optDouble(JsHost.ARG_PAGES, 1.0).toFloat(), OBSERVE_NONE)
                JsHost.SWIPE -> s.swipe(d, a.getInt(JsHost.ARG_FROM_X), a.getInt(JsHost.ARG_FROM_Y), a.getInt(JsHost.ARG_TO_X),
                    a.getInt(JsHost.ARG_TO_Y), a.optInt(JsHost.ARG_DURATION), OBSERVE_NONE)
                JsHost.TYPE -> s.typeText(d, a.getString(JsHost.ARG_TEXT), OBSERVE_NONE)
                JsHost.KEY -> s.pressKey(d, a.getString(JsHost.ARG_KEY), OBSERVE_NONE)
                JsHost.LAUNCH -> s.launchApp(d, a.optString(JsHost.ARG_PACKAGE), a.optString(JsHost.ARG_INTENT), OBSERVE_NONE)
                JsHost.MOVE_TASK -> s.moveTask(a.getInt(JsHost.ARG_TASK_ID), d, OBSERVE_NONE)
                else -> throw ToolFailure(ToolError.NOT_FOUND, "没有宿主函数 $name")
            }
            collect(r)
            r.error?.let { throw ToolFailure(it.code, it.message) }
            return JSONObject(r.action ?: EMPTY_OBJECT)
        }

        /** 系统事件（如安全窗口）留给最终结果，不让脚本吞掉。 */
        private fun collect(r: ScreenResult): ScreenResult {
            r.events.orEmpty().forEach { events.put(JSONObject(it)) }
            return r
        }

        private fun append(text: String) {
            log.append(text).append('\n')
            if (log.length > LOG_LIMIT) {
                log.delete(0, log.length - LOG_LIMIT)
                logDropped = true
            }
        }

        /** 可被停止按钮打断；不超过脚本的截止时间，之后由 QuickJS 的中断回调结束脚本。 */
        private fun sleep(ms: Long) {
            val end = minOf(SystemClock.uptimeMillis() + ms, deadline)
            while (!cancelled && SystemClock.uptimeMillis() < end) SystemClock.sleep(minOf(SLEEP_SLICE_MS, end - SystemClock.uptimeMillis()))
        }

        private fun read(pfd: ParcelFileDescriptor) = ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    private companion object {
        const val TAG = "ScriptRunner"

        /** log 和脚本的值各自的上限（只保留 log 的尾部）。 */
        const val LOG_LIMIT = OutputCapture.LIMIT
        const val SLEEP_SLICE_MS = 50L
        const val EMPTY_OBJECT = "{}"

        /** 中间动作不要节点树和截图，也不等 UI 稳定（推迟到下一次调用，ADR-0034 第 3 条）。 */
        const val OBSERVE_NONE = 0

        // 结果字段（ADR-0030 第 5 条）；events、screen、screenshot_error、tree 与屏幕工具相同。
        const val ELAPSED_MS = "elapsed_ms"
        const val LOG = "log"
        const val LOG_TRUNCATED = "log_truncated"
        const val VALUE = "value"
        const val VALUE_TRUNCATED = "value_truncated"
        const val STACK = "stack"
        const val STATE_ERROR = "state_error"
        const val WAITS = "waits"
    }
}
