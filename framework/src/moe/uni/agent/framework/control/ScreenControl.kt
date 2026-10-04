package moe.uni.agent.framework.control

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.ServiceSpecificException
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import moe.uni.agent.framework.ActionFailure
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.IAgentEvents
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.framework.ScreenMirror
import moe.uni.agent.framework.ScreenResult
import moe.uni.agent.framework.ToolError
import moe.uni.agent.framework.a11y.NodeTree
import moe.uni.agent.framework.a11y.Nodes
import moe.uni.agent.framework.a11y.ScreenAccessibility
import moe.uni.agent.framework.a11y.Selector
import moe.uni.agent.framework.capture.Screenshots
import moe.uni.agent.framework.device.NotificationEvents
import moe.uni.agent.framework.display.Mirrors
import moe.uni.agent.framework.display.Screens
import moe.uni.agent.framework.display.VirtualScreen
import moe.uni.agent.framework.highlight.Highlight
import moe.uni.agent.framework.highlight.StatusPill
import moe.uni.agent.framework.input.Injector
import moe.uni.agent.framework.system.Bubbles
import moe.uni.agent.framework.system.Tasks

/**
 * IAgentScreen 的编排（ADR-0022）：解析屏幕 → 执行动作 → 等 UI 稳定 → 按 observe 组装状态和截图。
 * 结果只有数据（ADR-0030 第 3 条），字段见 ResultFields。所有调用（镜像除外）在这个对象上串行执行；各动作的实现在 Actions 里。
 */
class ScreenControl(private val context: Context) {
    val handler = Handler(HandlerThread(THREAD_NAME).apply { start() }.looper)

    private val tasks = Tasks()
    private val notices = Notices()
    private val nodes = Nodes()
    private val virtual: VirtualScreen =
        VirtualScreen(context, handler, this, SecureWindows(tasks, notices) { virtual }) { mirrors.clear() }
    private val screens = Screens(context, virtual)
    private val stability = Stability(virtual)
    private val highlight = Highlight(context)
    private val status = StatusPill(context)
    private val screenshots = Screenshots(virtual, status::layer)
    private val mirrors = Mirrors(virtual, highlight)
    private val bubbles = Bubbles(context)
    private val actions = Actions(context, screens, nodes, tasks, Injector(context, virtual), highlight)
    private val events = Events(tasks)

    /** observe 为 0 的动作推迟的稳定等待（ADR-0034 第 3 条）：display → 动作开始的 uptime。只在持锁时访问。 */
    private val unsettled = HashMap<Int, Long>()

    init {
        ScreenAccessibility.listener = events::onAccessibility
        virtual.frameListener = events::changed
        NotificationEvents(events::notification).register(context)
    }

    /** 虚拟屏幕就绪后，第一次时顺带打开 AgentApp 的气泡偏好（ADR-0031 第 6 条）。 */
    @Synchronized
    fun setup() {
        virtual.setup()
        bubbles.allowOnce()
    }

    // ---- 镜像（ADR-0031 第 3 条）：不加锁，由 Mirrors 在主线程串行 ----

    fun createMirror(display: Int, token: IBinder): ScreenMirror = mirrors.create(display, token)

    fun releaseMirror(token: IBinder) = mirrors.release(token)

    // ---- 状态胶囊（ADR-0033 第 5 条）：不加锁，由 StatusPill 在主线程串行，不等正在执行的动作 ----
    // 只是提示，不像动作那样解析和唤醒屏幕；display 用真实 id（主屏为 0）。

    fun showStatus(display: Int, text: String) = status.show(display, text)

    fun hideStatus(display: Int) = status.hide(display)

    // ---- 等待用的事件（ADR-0034 第 4 条）：不加锁，由 Events 自己串行 ----

    fun addEventListener(listener: IAgentEvents) = events.add(listener)

    fun removeEventListener(listener: IAgentEvents) = events.remove(listener)

    // ---- 感知 ----

    @Synchronized
    fun list(): ScreenResult {
        val screenList = JSONArray().put(JSONObject().put(ListFields.DISPLAY, Display.DEFAULT_DISPLAY).put(ListFields.MAIN, true)
            .put(ListFields.WIDTH, screens.width(0)).put(ListFields.HEIGHT, screens.height(0))
            .put(ListFields.AWAKE, screens.mainOn).put(ListFields.LOCKED, screens.mainLocked))
        if (virtual.created) {
            screenList.put(JSONObject().put(ListFields.DISPLAY, virtual.id).put(ListFields.MAIN, false)
                .put(ListFields.WIDTH, virtual.width).put(ListFields.HEIGHT, virtual.height)
                .put(ListFields.AWAKE, virtual.isOn).put(ListFields.LOCKED, false))
        }
        // 同一屏幕上靠前的在上层。
        val taskList = JSONArray(tasks.all().map { t ->
            JSONObject().put(ListFields.TASK, t.taskId).put(ListFields.DISPLAY, t.displayId)
                .put(ListFields.COMPONENT, (t.topActivity ?: t.baseActivity)?.flattenToShortString() ?: JSONObject.NULL)
                .put(ListFields.VISIBLE, t.isVisible)
        })
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = JSONArray(pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first }
            .map { (label, pkg) -> JSONObject().put(ListFields.LABEL, label).put(ListFields.PACKAGE, pkg) })
        return ScreenResult().apply {
            ok = true
            data = JSONObject().put(ListFields.SCREENS, screenList).put(ListFields.TASKS, taskList).put(ListFields.APPS, apps).toString()
            events = notices.take()
        }
    }

    @Synchronized
    fun state(display: Int): ScreenResult {
        val d = resolve(display)
        val settled = settle(d) ?: stability.quiet(d)
        return result(d, IAgentScreen.OBSERVE_TREE or IAgentScreen.OBSERVE_SCREENSHOT, settled)
    }

    /**
     * 构建新的节点树并返回屏幕信息和匹配 selector 的节点（ADR-0024），编号同时成为该屏幕最近一次状态。
     * settle 为 false 时不补推迟的稳定等待（exec_js 等待中的查询）。
     */
    @Synchronized
    fun query(display: Int, selector: String, settle: Boolean): String {
        val d = resolve(display)
        if (settle) settle(d)
        val windows = nodes.windows(d)
        val top = tasks.top(d)
        val tree = nodes.build(d, top, screens.bounds(d), windows)
        val sel = try {
            JSONObject(selector.ifBlank { EMPTY_SELECTOR })
        } catch (e: JSONException) {
            throw ServiceSpecificException(IAgentScreen.ERROR_INVALID_ARGUMENT, "选择器不是 JSON 对象")
        }
        val matches = try {
            Selector.select(tree.nodes.indices.map { tree.json(it) }, sel)
        } catch (e: IllegalArgumentException) {
            throw ServiceSpecificException(IAgentScreen.ERROR_INVALID_ARGUMENT, e.message)
        } catch (e: JSONException) {
            throw ServiceSpecificException(IAgentScreen.ERROR_INVALID_ARGUMENT, "选择器字段的类型不对")
        }
        return screenInfo(d, top, windows, tree).put(ScreenFields.NODES, JSONArray(matches)).toString()
    }

    // ---- 动作 ----

    @Synchronized
    fun tap(display: Int, element: Int, x: Int, y: Int, count: Int, longPress: Boolean, observe: Int) =
        act(display, observe) { actions.tap(it, element, x, y, count, longPress) }

    @Synchronized
    fun nodeAction(display: Int, element: Int, action: String, observe: Int) =
        act(display, observe) { actions.nodeAction(it, element, action) }

    @Synchronized
    fun setText(display: Int, element: Int, text: String, observe: Int) =
        act(display, observe) { actions.setText(it, element, text) }

    @Synchronized
    fun selectText(display: Int, element: Int, text: String, prefix: String, suffix: String, selection: String, observe: Int) =
        act(display, observe) { actions.selectText(it, element, text, prefix, suffix, selection) }

    @Synchronized
    fun scroll(display: Int, element: Int, direction: String, pages: Float, observe: Int) =
        act(display, observe) { actions.scroll(it, element, direction, pages) }

    @Synchronized
    fun swipe(display: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int, observe: Int) =
        act(display, observe) { actions.swipe(it, fromX, fromY, toX, toY, durationMs) }

    @Synchronized
    fun pressKey(display: Int, key: String, observe: Int) = act(display, observe) { actions.pressKey(it, key) }

    @Synchronized
    fun typeText(display: Int, text: String, observe: Int) = act(display, observe) { actions.typeText(it, text) }

    @Synchronized
    fun launchApp(display: Int, packageName: String, intentUri: String, observe: Int) =
        act(display, observe) { actions.launchApp(it, packageName, intentUri) }

    @Synchronized
    fun moveTask(taskId: Int, display: Int, observe: Int) = act(display, observe) { actions.moveTask(it, taskId) }

    // ---- 编排 ----

    /** 解析屏幕；虚拟屏幕刚被唤醒时先等画面稳定（ADR-0022 第 7 条）。 */
    private fun resolve(display: Int) = screens.resolve(display) { stability.await(it) }

    /**
     * 补上这块屏幕推迟的稳定等待（ADR-0034 第 3 条），返回是否稳定；没有推迟的等待时返回 null。
     */
    private fun settle(d: Int): Boolean? = unsettled.remove(d)?.let { stability.await(d, it) }

    /**
     * 执行动作，等 UI 稳定后按 observe 返回新的状态和截图（第 10 条）；动作失败时同样返回当前状态。
     * 动作会画流光效果（ADR-0023）：要截图时先等它结束并隐藏。稳定等待从动作开始计时，与效果的时间重叠。
     * observe 为 0 时（exec_js 的中间动作）不等效果结束，也不构建节点树、不截图（ADR-0024），
     * 也不等 UI 稳定：推迟到这块屏幕的下一次调用之前（ADR-0034 第 3 条），等待中的脚本可以立即判断条件。
     * 屏幕不可用等无法执行的情况（ServiceSpecificException）照常抛给调用方；其他意外异常记日志，作为动作失败。
     */
    private fun act(display: Int, observe: Int, action: (Int) -> JSONObject): ScreenResult {
        val d = resolve(display)
        settle(d)
        val start = SystemClock.uptimeMillis()
        var done: JSONObject? = null
        val failure = try {
            done = action(d)
            null
        } catch (e: ActionFailure) {
            e.toError()
        } catch (e: ServiceSpecificException) {
            throw e
        } catch (e: RuntimeException) {
            Log.w(FrameworkApp.TAG, "动作执行出错", e)
            ActionFailure.error(ToolError.FAILED, "动作执行出错")
        }
        if (observe and IAgentScreen.OBSERVE_SCREENSHOT != 0) highlight.awaitIdle(d)
        val settled = if (observe == 0) {
            unsettled[d] = start
            false
        } else {
            stability.await(d, start)
        }
        return result(d, observe, settled).apply {
            ok = failure == null
            error = failure
            this.action = done?.toString()
        }
    }

    /** 当前状态：observe 含 OBSERVE_TREE 时带屏幕信息和节点树，含 OBSERVE_SCREENSHOT 时带截图；另取出这段时间的事件。 */
    private fun result(d: Int, observe: Int, settled: Boolean): ScreenResult {
        val out = ScreenResult().apply {
            ok = true
            this.settled = settled
        }
        if (observe and IAgentScreen.OBSERVE_TREE != 0) {
            val windows = nodes.windows(d)
            val top = tasks.top(d)
            val tree = nodes.build(d, top, screens.bounds(d), windows)
            out.screen = screenInfo(d, top, windows, tree).toString()
            out.tree = tree.text
        }
        if (observe and IAgentScreen.OBSERVE_SCREENSHOT != 0) {
            try {
                val bitmap = screenshots.bitmap(d)
                if (bitmap == null) out.screenshotError = ActionFailure.error(ToolError.UNAVAILABLE, "虚拟屏幕还没有画面")
                out.image = bitmap?.let { screenshots.memfd(it) }
            } catch (e: Exception) {
                Log.w(FrameworkApp.TAG, "截图失败", e)
                out.screenshotError = ActionFailure.error(ToolError.FAILED, "截图失败")
            }
        }
        out.events = notices.take()
        return out
    }

    /** 屏幕信息（字段见 ScreenFields）：state 的 screen 和 query 的外层共用。 */
    private fun screenInfo(d: Int, top: String?, windows: List<AccessibilityWindowInfo>, tree: NodeTree): JSONObject = JSONObject()
        .put(ScreenFields.DISPLAY, d)
        .put(ScreenFields.MAIN, screens.isMain(d))
        .put(ScreenFields.WIDTH, screens.width(d))
        .put(ScreenFields.HEIGHT, screens.height(d))
        .put(ScreenFields.TOP, top ?: JSONObject.NULL)
        .put(ScreenFields.IME, nodes.imeShown(windows))
        .put(ScreenFields.FOCUSED, tree.focused.takeIf { it > 0 } ?: JSONObject.NULL)

    companion object {
        private const val THREAD_NAME = "screen"
        private const val EMPTY_SELECTOR = "{}"
    }
}
