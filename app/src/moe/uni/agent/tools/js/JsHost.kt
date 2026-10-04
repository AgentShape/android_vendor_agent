package moe.uni.agent.tools.js

import moe.uni.agent.tools.ToolOutput
import org.json.JSONObject

/**
 * prelude（assets/exec_prelude.js）与 Kotlin 宿主之间的约定（ADR-0024、ADR-0025）：__host 的函数名、
 * 参数名和返回格式。prelude 不能引用这里的常量，改动时两边一起改；屏幕节点的字段名见 AgentFramework 的 NodeFields。
 */
object JsHost {
    /** prelude 定义的入口：执行模型的代码并返回结果的 JSON。 */
    const val RUN = "__run"

    // 返回格式 {"ok":true,"value":...} 或 {"ok":false,"error":{"code":...,"message":...}}（错误与工具结果相同，ADR-0030）；
    // __run 的返回值也是这个格式，出错时另有 stack。
    const val OK = "ok"
    const val VALUE = "value"
    const val ERROR = "error"
    const val STACK = "stack"

    // 通用
    const val LOG = "log"
    const val SLEEP = "sleep"
    const val DISPLAY = "display"

    /** 按名字调用任意工具（exec_js 除外），返回工具的结果（ADR-0029 第 6 条）。 */
    const val TOOL = "tool"
    const val ARG_NAME = "name"
    const val ARG_ARGS = "args"

    // shell 与文件
    const val SH = "sh"
    const val FS_READ = "fs.read"
    const val FS_READ_BYTES = "fs.readBytes"
    const val FS_WRITE = "fs.write"
    const val FS_EDIT = "fs.edit"
    const val FS_STAT = "fs.stat"
    const val FS_LIST = "fs.list"

    // 屏幕
    const val LIST = "list"
    const val QUERY = "query"
    const val SCREENSHOT = "screenshot"
    const val TAP = "tap"
    const val SET_TEXT = "setText"
    const val SELECT_TEXT = "selectText"
    const val NODE_ACTION = "nodeAction"
    const val SCROLL = "scroll"
    const val SWIPE = "swipe"
    const val TYPE = "type"
    const val KEY = "key"
    const val LAUNCH = "launch"
    const val MOVE_TASK = "moveTask"

    // 等待（ADR-0034 第 5 条），返回值的字段见 ScriptEvents
    const val MARK = "mark"
    const val WATCH = "watch"
    const val UNWATCH = "unwatch"
    const val WAIT_EVENTS = "waitEvents"
    /** 一次 wait 结束，参数就是结果里 waits 的一项。 */
    const val WAIT_DONE = "waitDone"

    // 参数名
    const val ARG_TEXT = "text"
    const val ARG_MS = "ms"
    const val ARG_IMAGE = "image"
    const val ARG_COMMAND = "command"
    const val ARG_CWD = "cwd"
    const val ARG_TIMEOUT = "timeout"
    const val ARG_DISPLAY = "display"
    const val ARG_SELECTOR = "selector"
    const val ARG_ELEMENT = "element"
    const val ARG_X = "x"
    const val ARG_Y = "y"
    const val ARG_COUNT = "count"
    const val ARG_LONG = "long"
    const val ARG_PREFIX = "prefix"
    const val ARG_SUFFIX = "suffix"
    const val ARG_SELECTION = "selection"
    const val ARG_ACTION = "action"
    const val ARG_DIRECTION = "direction"
    const val ARG_PAGES = "pages"
    const val ARG_FROM_X = "fromX"
    const val ARG_FROM_Y = "fromY"
    const val ARG_TO_X = "toX"
    const val ARG_TO_Y = "toY"
    const val ARG_DURATION = "duration"
    const val ARG_KEY = "key"
    const val ARG_PACKAGE = "package"
    const val ARG_INTENT = "intent"
    const val ARG_TASK_ID = "taskId"
    const val ARG_SETTLE = "settle"
    const val ARG_PATHS = "paths"
    const val ARG_ID = "id"
    const val ARG_SINCE = "since"
    const val ARG_FROM = "from"

    fun value(value: Any?): ByteArray = JSONObject().put(OK, true).put(VALUE, value ?: JSONObject.NULL).toString().toByteArray()

    fun error(code: String, message: String): ByteArray = ToolOutput.failure(code, message).toString().toByteArray()
}
