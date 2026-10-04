package moe.uni.agent.tools.js

import moe.uni.agent.tools.Schema
import moe.uni.agent.tools.Tool
import moe.uni.agent.tools.ToolContext
import moe.uni.agent.tools.ToolResult
import moe.uni.agent.tools.screen.ScreenTools
import org.json.JSONObject

/**
 * exec_js 工具（ADR-0024）：定义在这里，执行在 ScriptRunner。用户没有开启 root shell 时，
 * 说明里没有 sh()，脚本里也没有这个函数（ADR-0029 第 4 条），所以分两个实例。
 */
class ExecJs private constructor(shell: Boolean) : Tool {
    override val name = NAME
    override val kind get() = Tool.Kind.SCRIPT

    override fun parameters(schema: Schema) = with(schema) {
        string(CODE, "JavaScript 脚本，同步风格", required = true)
        integer(TIMEOUT_MS, "超时毫秒，默认 $DEFAULT_TIMEOUT_MS，最大 $MAX_TIMEOUT_MS")
    }

    override fun run(context: ToolContext, args: JSONObject): ToolResult {
        val timeout = args.optLong(TIMEOUT_MS).takeIf { it > 0 }?.coerceAtMost(MAX_TIMEOUT_MS) ?: DEFAULT_TIMEOUT_MS
        return context.scripts.run(args.getString(CODE), timeout)
    }

    /** 脚本的第一行非空内容。 */
    override fun summary(args: JSONObject): String =
        args.optString(CODE).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    override val description = listOfNotNull(
        """
        在 QuickJS 中执行 JavaScript，可组合提供的工具。
        这并非浏览器环境。
        let/const 只在本次调用内有效。
        脚本的值是最后一个表达式的值，也可以在顶层 return。
        结果：ok、error（脚本出错时 code 为 failed 或抛出的 Error 的 code，message 是 JS 的错误，另有 stack；超时为 timeout，被停止为 cancelled）、
        elapsed_ms、log（log() 的输出，过长时只保留尾部，log_truncated）、value（脚本的值，过长时截断成字符串，value_truncated）、
        events（系统事件）、waits（每次 wait() 的结局：display、matched 命中的 when 序号或 "timeout"、elapsed_ms、settled、events 期间的事件数）；
        执行过屏幕动作时再附上最终状态 settled、screen、tree 和截图（只读查询不附带），另加 display() 的截图。
        接口失败时抛 Error，e.code 是错误代码（与工具结果的 error.code 相同）。
        """.trimIndent(),
        SH_DOC.takeIf { shell },
        """
        - fs.read(path) → 全部文本（超过 16MiB 时抛错，改用 fs.readBytes 分块读取）；fs.readBytes(path, offset, length) → {data_base64, next_offset}；
          fs.write(path, text, {append})、fs.edit(path, old, new, count)、fs.stat(path)、fs.list(path) 的结果与对应工具相同；失败抛错。
          路径必须在用户存储（/sdcard）内。
        - screen 指虚拟屏幕，screen.on(0) 指主屏幕；screen 的方法也可以直接作为全局函数调用（作用于虚拟屏幕）。
          list() → 与 screen_list 结果相同的 {screens, tasks, apps}。
        - state() → 与 screen_query 结果相同的 {display, main, width, height, top, ime, focused, nodes}；findAll(sel) → 节点数组；
          find(sel) → 节点或 null，多于一个时抛错。
        - ${ScreenTools.NODE_DOC}
        - ${ScreenTools.SELECTOR_DOC}匹配到多个元素时加条件或 index。
        - 屏幕动作（成功返回与对应屏幕工具结果相同的 action 对象，失败抛错）：tap(target, {count, long})、setText(target, text)、
          selectText(target, text, {prefix, suffix, selection})、action(target, name)、scroll(target|null, "up"|"down"|"left"|"right", pages)、
          swipe(fx, fy, tx, ty, {duration})、type(text)（在焦点输入框的光标处插入）、key("ENTER"|"CTRL+A"|...)、launch(package 或 {intent})、moveTask(taskId)。
          target 是节点、选择器、元素编号或 {x, y}；执行前会在当时的界面上重新唯一定位，界面变了就抛错。
        - 等待：屏幕动作的返回值是起点，只看它之后发生的事；mark() 以现在为起点；watch(path, ...) 开始监听文件或目录（不递归，/sdcard 内）并以现在为起点，
          要先 watch 再触发。起点.when(cond, handler).when(...).timeout(handler).wait(ms=5000)：事件驱动地反复判断，命中第一个 cond 就返回
          handler(s, cond 的值)（没有 handler 时返回 cond 的值）；超时返回 timeout 的 handler 的值，没有则抛 timeout。挂了 when 却不 wait 会报错。
          cond 是函数 s => …，或选择器（等于 s => s.find(选择器)）。条件在界面变化中途就可能命中，需要时加 s.settled。例：
          tap({text:"登录"}).when({textContains:"欢迎"}, () => "ok").when(s => s.toast(/错误/), (s, t) => t.text).wait(10000)；
          等消失：mark().when(s => !s.find(sel)).wait()；下载完成：const m = watch("/sdcard/Download"); tap(…); m.when(s => s.file(/\.apk$/, "moved")).wait(60000)。
        - s 的成员：find(sel) → 第一个匹配或 null（多个不抛错）、findAll(sel)、state() 屏幕信息、settled 界面是否已稳定、elapsed 已等的毫秒数、
          events 起点之后的事件；window(f)、pane(f)、toast(f)、announcement(f)、notification(f)、file(f, change) → 起点之后最近一条匹配的事件或 null。
          f 是文字（包含即可）或正则，匹配事件的主要文字（notification 为 title 或 text，file 为 path，其余为 text）；也可以是字段对象，每个字段都要满足。
          事件：window {package, cls, text} 页面或窗口切换；pane {package, text, shown} 面板出现或消失；toast {package, text} 只有主屏幕有；
          announcement {package, text}；notification {change: posted|updated|removed, key, package, title, text, …}；
          file {change: created|modified|moved|deleted, path}（moved 是改名后的新路径，下载完成即从 .pending-… 改名为正式文件名）。
          屏幕类事件只看起点所在的屏幕，通知和文件不分屏幕。
        - screenshot() 返回截图句柄，display(句柄) 把它附在结果里；log(...) 输出文字；sleep(ms)。
        - tool(name, args) 调用任意工具（exec_js 除外），返回工具结果的对象（不带截图）；ok 为 false 时抛错。
          常用的有封装：apps.list(filter, includeSystem)、apps.install(path)、apps.uninstall(package)；
          settings.get(namespace, key) → 值、settings.list(namespace, filter) → [{key, value}]、settings.put(namespace, key, value)；
          device.info()；notifications.list() → 通知数组、notifications.open(key)、notifications.dismiss(key)；clipboard.get() → 文字、clipboard.set(text)。
        """.trimIndent(),
    ).joinToString("\n")

    companion object {
        const val NAME = "exec_js"

        /** 用户开启 root shell 时提供，脚本里有 sh()。 */
        val WITH_SHELL = ExecJs(shell = true)
        val WITHOUT_SHELL = ExecJs(shell = false)

        private const val CODE = "code"
        private const val TIMEOUT_MS = "timeout_ms"
        private const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val MAX_TIMEOUT_MS = 600_000L

        /** 说明里 sh() 的那一条，只在开启 root shell 时出现。 */
        private const val SH_DOC = "- sh(command, {cwd, timeout}) → 与 exec 工具相同的结果对象 " +
            "{ok, error, state, exit_code, signal, output, truncated, saved_output_path}，以 root 在新 shell 中执行；非零退出不抛错。"
    }
}
