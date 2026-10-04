package moe.uni.agent.tools.screen

import android.os.ParcelFileDescriptor
import android.os.ServiceSpecificException
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.framework.ScreenResult
import moe.uni.agent.framework.ToolError
import moe.uni.agent.tools.Schema
import moe.uni.agent.tools.Tool
import moe.uni.agent.tools.ToolContext
import moe.uni.agent.tools.ToolFailure
import moe.uni.agent.tools.ToolOutput
import moe.uni.agent.tools.ToolResult
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * 单步屏幕工具（ADR-0022 第 10 条）：经 IAgentScreen 调用 AgentFramework，这里负责 schema 和结果组装。
 * AgentFramework 只返回数据，这里组织成交给模型的 JSON（ADR-0030 第 3、4 条）：ok/error、action、screen、events、tree，另附截图。
 * 动作执行后等界面稳定，自动返回新的状态。结果各字段的读法是工具组说明（GROUP_DESCRIPTION）。
 */
object ScreenTools {
    // 工具名
    const val SCREEN_LIST = "screen_list"
    const val SCREEN_STATE = "screen_state"
    const val SCREEN_QUERY = "screen_query"
    const val TAP = "tap"
    const val NODE_ACTION = "node_action"
    const val SET_TEXT = "set_text"
    const val SELECT_TEXT = "select_text"
    const val SCROLL = "scroll"
    const val SWIPE = "swipe"
    const val PRESS_KEY = "press_key"
    const val TYPE_TEXT = "type_text"
    const val LAUNCH_APP = "launch_app"
    const val MOVE_TASK = "move_task"

    // 参数
    const val DISPLAY = "display"
    const val ELEMENT = "element"
    const val X = "x"
    const val Y = "y"
    const val COUNT = "count"
    const val LONG = "long"
    const val ACTION = "action"
    const val TEXT = "text"
    const val PREFIX = "prefix"
    const val SUFFIX = "suffix"
    const val SELECTION = "selection"
    const val SELECTION_TEXT = "text"
    const val DIRECTION = "direction"
    const val PAGES = "pages"
    const val FROM_X = "from_x"
    const val FROM_Y = "from_y"
    const val TO_X = "to_x"
    const val TO_Y = "to_y"
    const val DURATION_MS = "duration_ms"
    const val KEY = "key"
    const val PACKAGE = "package"
    const val INTENT = "intent"
    const val TASK_ID = "task_id"
    const val SELECTOR = "selector"

    const val SWIPE_MS = 300

    /** IAgentScreen 中指代虚拟屏幕的 display 值（AIDL 常量）。 */
    const val VIRTUAL_DISPLAY = IAgentScreen.DISPLAY_VIRTUAL

    /** 单步工具：返回节点树和截图。 */
    const val FULL = IAgentScreen.OBSERVE_TREE or IAgentScreen.OBSERVE_SCREENSHOT

    // 结果字段；action、screen 内部和 AgentFramework 的 data 的字段名见 AgentFramework 的 control/ResultFields.kt。
    const val RESULT_ACTION = "action"
    const val RESULT_SETTLED = "settled"
    const val RESULT_SCREEN = "screen"
    const val RESULT_EVENTS = "events"
    const val RESULT_SCREENSHOT_ERROR = "screenshot_error"
    const val RESULT_TREE = "tree"

    /** 结构化节点的字段，screen_query 和 exec_js 共用这段说明。 */
    const val NODE_DOC = "节点：{element,cls,text,desc,hint,label,id,bounds:[l,t,r,b],clickable,longClickable,checkable,checked," +
        "editable,password,scrollable:[方向],focused,selected,enabled,offscreen,actions:[自定义 action]}。"

    /** 选择器的字段，screen_query 和 exec_js 共用这段说明。 */
    const val SELECTOR_DOC = "选择器（所有字段都要满足）：text/desc/hint/label/id/cls 精确匹配，textContains/descContains/labelContains，bounds，" +
        "clickable/checked/editable/scrollable/selected/focused/enabled 等布尔值，index 取第几个匹配（从 0 开始）。"

    /**
     * AgentFramework 的结果组织成交给模型的 JSON：data（screen_list）的字段直接放在顶层，节点树放在最后。
     * 空的字段省略。exec_js 的最终状态也用它（ADR-0030 第 5 条）。
     */
    fun present(r: ScreenResult): JSONObject {
        val out = r.error?.let { ToolOutput.failure(it) } ?: ToolOutput.ok()
        r.action?.let { out.put(RESULT_ACTION, JSONObject(it)) }
        r.data?.let { data -> JSONObject(data).let { d -> d.keys().forEach { out.put(it, d.get(it)) } } }
        r.screen?.let {
            out.put(RESULT_SETTLED, r.settled)
            out.put(RESULT_SCREEN, JSONObject(it))
        }
        r.events.orEmpty().takeIf { it.isNotEmpty() }?.let { events -> out.put(RESULT_EVENTS, JSONArray(events.map(::JSONObject))) }
        r.screenshotError?.let { out.put(RESULT_SCREENSHOT_ERROR, ToolOutput.error(it)) }
        r.tree?.let { out.put(RESULT_TREE, it) }
        return out
    }

    fun images(r: ScreenResult): List<ByteArray> =
        listOfNotNull(r.image?.let { ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> input.readBytes() } })

    /** IAgentScreen 无法执行时的错误码（屏幕不可用、参数无效等）；message 由 AgentFramework 写成事实陈述。 */
    fun failure(e: ServiceSpecificException) = ToolFailure(
        when (e.errorCode) {
            IAgentScreen.ERROR_INVALID_ARGUMENT -> ToolError.INVALID_ARGUMENT
            IAgentScreen.ERROR_NO_ASSOCIATION, IAgentScreen.ERROR_UNAVAILABLE -> ToolError.UNAVAILABLE
            else -> ToolError.FAILED
        },
        e.message.orEmpty(),
    )

    /** 屏幕工具的工具组说明：屏幕、结果的读法、节点树和事件（ADR-0030 第 4 条）。exec_js 的屏幕部分也按它读。 */
    val GROUP_DESCRIPTION = """
        屏幕工具：
        - 屏幕 0 是用户正在使用的主屏幕；另有一块用户看不到的虚拟屏幕（id 见 $SCREEN_LIST），屏幕工具默认在它上面操作，不打扰用户。
          只有用户要求或必须由用户处理时才用主屏幕；主屏幕灭屏或锁屏时不可用（unavailable），改用虚拟屏幕，或请用户点亮、解锁。
        - 动作的结果：action 是这次动作做了什么；screen 是动作之后的屏幕信息：display、main（是否主屏幕）、width、height、
          top（前台 activity）、ime（输入法是否显示）、focused（输入焦点所在元素的编号）；tree 是无障碍节点树；另附截图。
          动作失败时 ok 为 false，screen 和 tree 仍是当前状态。screenshot_error 是没能截图的原因。
          settled 为 false 表示等到上限时界面仍在变化（加载、动画），返回的是中间状态；要等到某个结果出现，用 exec_js 的等待。
        - 节点树每行一个节点，缩进表示层级，[n] 是元素编号，之后是类名、状态、文字、desc/hint/label、#资源 id、自定义 actions 和边界 [l,t,r,b]。
          编号是元素的标签，元素不变时编号不变；界面局部变化后可以直接用上一次的编号，元素已消失时动作会失败并返回新状态。
          编号不连续，也不按行的顺序排列；$SCREEN_QUERY 返回的编号与节点树相同。
          边界为空或完全在屏幕外的元素不列出，要操作它们先滚动；状态里的 offscreen 表示元素有一部分在屏幕外，边界照实给出。
          WebView（网页）里的文字常只在可点击元素的 desc 或 label 里而不在 text：按文字找元素时用 descContains/labelContains，用 text 找不到不代表不存在。
        - events 是这段时间的系统事件：
          secure_window 表示 component 显示了受系统保护（FLAG_SECURE）的窗口，agent 无法操作，需要告诉用户亲自处理；
          result 为 moved_to_main（已把 task 移到主屏幕）、moved_by_system（系统把它移到了主屏幕）、move_failed、not_moved（task 已不在这块屏幕上）或 no_task。
          launch_blocked 表示 component 的启动或显示被系统策略阻止（可能是 FLAG_SECURE，也可能是其他策略）。
          两者的 task、task_display 是它现在的 task 和所在屏幕，没有时为 null。
    """.trimIndent()

    val ALL: List<Tool> = listOf(
        ScreenList, ScreenState, ScreenQuery, Tap, NodeAction, SetText, SelectText, Scroll, Swipe, PressKey, TypeText, LaunchApp, MoveTask,
    )
}

/** 屏幕工具的公共部分：display 参数、摘要。 */
private abstract class ScreenTool(override val name: String, override val description: String) : Tool {
    override val kind get() = Tool.Kind.SCREEN
    override val groupDescription get() = ScreenTools.GROUP_DESCRIPTION

    override fun summary(args: JSONObject): String = when {
        args.has(ScreenTools.TEXT) -> args.optString(ScreenTools.TEXT)
        args.has(ScreenTools.PACKAGE) -> args.optString(ScreenTools.PACKAGE)
        args.has(ScreenTools.KEY) -> args.optString(ScreenTools.KEY)
        args.has(ScreenTools.ELEMENT) -> "#" + args.optInt(ScreenTools.ELEMENT) +
            (args.optString(ScreenTools.ACTION).takeIf { it.isNotEmpty() }?.let { " $it" } ?: "")
        args.has(ScreenTools.X) -> "(${args.optInt(ScreenTools.X)}, ${args.optInt(ScreenTools.Y)})"
        else -> args.optString(ScreenTools.DIRECTION)
    }

    protected fun JSONObject.display() = optInt(ScreenTools.DISPLAY, ScreenTools.VIRTUAL_DISPLAY)

    protected fun Schema.display() =
        integer(ScreenTools.DISPLAY, "屏幕 id（见 ${ScreenTools.SCREEN_LIST}）；省略或 ${ScreenTools.VIRTUAL_DISPLAY} 为虚拟屏幕，0 为主屏幕")
}

/** 调用 AgentFramework 的一个动作（或状态），返回 JSON 和截图。 */
private abstract class ScreenAction(name: String, description: String) : ScreenTool(name, description) {
    /** 无法执行（屏幕不可用、参数无效等）时是错误；动作本身失败时 AgentFramework 同样返回当前状态。 */
    override fun run(context: ToolContext, args: JSONObject): ToolResult {
        val result = try {
            context.screen().act(args)
        } catch (e: ServiceSpecificException) {
            throw ScreenTools.failure(e)
        }
        return ToolResult.json(ScreenTools.present(result), ScreenTools.images(result))
    }

    /** 执行动作，返回 AgentFramework 的结果。 */
    protected abstract fun IAgentScreen.act(args: JSONObject): ScreenResult
}

private object ScreenList : ScreenAction(
    ScreenTools.SCREEN_LIST,
    "列出屏幕、每块屏幕上的 task、可启动的 app。结果：screens（display、main、width、height、awake 是否亮屏、locked 是否锁屏；" +
        "虚拟屏幕休眠时操作会自动唤醒）、tasks（task、display、component 顶部 activity、visible；同一屏幕上靠前的在上层）、apps（label、package）。",
) {
    override fun IAgentScreen.act(args: JSONObject): ScreenResult = list()
}

/**
 * 按选择器查找元素（ADR-0024 第 5 条的 query 开放给模型，2026-09-30）：结构化 JSON，不带截图。
 * 与 screen_state 看的是同一棵无障碍树，查询结果同样成为这块屏幕的最近一次状态。
 */
private object ScreenQuery : ScreenTool(
    ScreenTools.SCREEN_QUERY,
    "按选择器查找屏幕上的元素：重新构建无障碍节点树，返回屏幕信息（与 screen 相同的字段）和匹配的节点 nodes，" +
        "不带截图和节点树文本，比 ${ScreenTools.SCREEN_STATE} 省 token。返回的元素编号与节点树相同，可以直接用于后续动作：编号是元素的标签，元素不变时编号不变。" +
        ScreenTools.NODE_DOC + ScreenTools.SELECTOR_DOC,
) {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        string(ScreenTools.SELECTOR, "选择器 JSON 对象，例如 {\"textContains\":\"设置\"}；为空或 {} 时返回全部节点")
    }

    override fun summary(args: JSONObject): String = args.optString(ScreenTools.SELECTOR)

    override fun run(context: ToolContext, args: JSONObject): ToolResult {
        val selector = try {
            JSONObject(args.optString(ScreenTools.SELECTOR).ifBlank { EMPTY_SELECTOR })
        } catch (e: JSONException) {
            throw ToolFailure(ToolError.INVALID_ARGUMENT, "${ScreenTools.SELECTOR} 不是 JSON 对象")
        }
        val found = try {
            JSONObject(context.screen().query(args.display(), selector.toString(), true))
        } catch (e: ServiceSpecificException) {
            throw ScreenTools.failure(e)
        }
        val out = ToolOutput.ok()
        found.keys().forEach { out.put(it, found.get(it)) }
        return ToolResult.json(out)
    }

    private const val EMPTY_SELECTOR = "{}"
}

private object ScreenState : ScreenAction(ScreenTools.SCREEN_STATE, "返回屏幕的截图、屏幕信息和无障碍节点树。") {
    override fun parameters(schema: Schema) = schema.display()
    override fun IAgentScreen.act(args: JSONObject): ScreenResult = state(args.display())
}

private object Tap : ScreenAction(
    ScreenTools.TAP,
    "点击。指定 element 时优先执行元素的无障碍点击，不支持或连续点击时点元素在屏幕内部分的中心（元素此时完全在屏幕外则失败，" +
        "not_found，先滚动）；element 不大于 0 时点击坐标 x,y（屏幕像素）。" +
        "结果的 action：method 为 accessibility（执行了 action 字段中的无障碍 action）或 touch（点击了 point）。",
) {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        integer(ScreenTools.ELEMENT, "元素编号")
        integer(ScreenTools.X, "横坐标")
        integer(ScreenTools.Y, "纵坐标")
        integer(ScreenTools.COUNT, "连续点击次数，默认 1")
        boolean(ScreenTools.LONG, "长按，默认 false")
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult = tap(args.display(), args.optInt(ScreenTools.ELEMENT),
        args.optInt(ScreenTools.X), args.optInt(ScreenTools.Y), args.optInt(ScreenTools.COUNT, 1), args.optBoolean(ScreenTools.LONG),
        ScreenTools.FULL)
}

private object NodeAction : ScreenAction(
    ScreenTools.NODE_ACTION, "对元素执行它暴露的无障碍 action：标准名字（如 ACTION_EXPAND、ACTION_DISMISS）或节点树 actions=[...] 中的自定义标签。",
) {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        integer(ScreenTools.ELEMENT, "元素编号", required = true)
        string(ScreenTools.ACTION, "action 名字或标签", required = true)
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult =
        nodeAction(args.display(), args.getInt(ScreenTools.ELEMENT), args.getString(ScreenTools.ACTION), ScreenTools.FULL)
}

private object SetText : ScreenAction(ScreenTools.SET_TEXT, "用 ACTION_SET_TEXT 替换可编辑元素的全部内容。") {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        integer(ScreenTools.ELEMENT, "元素编号", required = true)
        string(ScreenTools.TEXT, "新内容", required = true)
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult =
        setText(args.display(), args.getInt(ScreenTools.ELEMENT), args.getString(ScreenTools.TEXT), ScreenTools.FULL)
}

private object SelectText : ScreenAction(
    ScreenTools.SELECT_TEXT, "在元素的文本中定位 text（匹配到多处时用 prefix/suffix 区分），选中它或把光标放到它的前后。",
) {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        integer(ScreenTools.ELEMENT, "元素编号", required = true)
        string(ScreenTools.TEXT, "要定位的文字", required = true)
        string(ScreenTools.PREFIX, "紧挨在 text 前面的文字，可为空")
        string(ScreenTools.SUFFIX, "紧挨在 text 后面的文字，可为空")
        string(ScreenTools.SELECTION, "${ScreenTools.SELECTION_TEXT}（选中，默认）、cursor_before 或 cursor_after")
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult = selectText(args.display(), args.getInt(ScreenTools.ELEMENT),
        args.optString(ScreenTools.TEXT), args.optString(ScreenTools.PREFIX), args.optString(ScreenTools.SUFFIX),
        args.optString(ScreenTools.SELECTION).ifEmpty { ScreenTools.SELECTION_TEXT }, ScreenTools.FULL)
}

private object Scroll : ScreenAction(
    ScreenTools.SCROLL,
    "滚动元素，不指定 element 时滚动整个屏幕。direction 是要查看的方向，down 表示查看下面的内容。" +
        "元素有对应的滚动 action 且页数是整数时逐页执行它（结果的 action.method 为 accessibility，scrolled 是实际滚动的页数，" +
        "小于 pages 表示到了边界；一页也滚不动时失败，通常表示已到边界）；否则在区域内滑动（元素在屏幕内的部分，或整个屏幕；method 为 swipe）。",
) {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        integer(ScreenTools.ELEMENT, "可滚动的元素编号")
        string(ScreenTools.DIRECTION, "up、down、left、right", required = true)
        number(ScreenTools.PAGES, "页数，可以是小数，默认 1")
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult = scroll(args.display(), args.optInt(ScreenTools.ELEMENT),
        args.getString(ScreenTools.DIRECTION), args.optDouble(ScreenTools.PAGES, 1.0).toFloat(), ScreenTools.FULL)
}

private object Swipe : ScreenAction(ScreenTools.SWIPE, "从一点按住滑动到另一点（屏幕像素）。") {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        integer(ScreenTools.FROM_X, "起点横坐标", required = true)
        integer(ScreenTools.FROM_Y, "起点纵坐标", required = true)
        integer(ScreenTools.TO_X, "终点横坐标", required = true)
        integer(ScreenTools.TO_Y, "终点纵坐标", required = true)
        integer(ScreenTools.DURATION_MS, "持续时间，默认 ${ScreenTools.SWIPE_MS}")
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult = swipe(args.display(), args.getInt(ScreenTools.FROM_X),
        args.getInt(ScreenTools.FROM_Y), args.getInt(ScreenTools.TO_X), args.getInt(ScreenTools.TO_Y),
        args.optInt(ScreenTools.DURATION_MS, ScreenTools.SWIPE_MS), ScreenTools.FULL)
}

private object PressKey : ScreenAction(
    ScreenTools.PRESS_KEY,
    "按键：Android keycode 去掉 KEYCODE_ 前缀，如 ENTER、DEL、BACK；组合键用 + 连接，如 CTRL+A。只报告注入是否成功，界面效果看返回的状态。" +
        "虚拟屏幕没有桌面和最近任务，不支持 HOME 和 APP_SWITCH，用 ${ScreenTools.SCREEN_LIST}、${ScreenTools.LAUNCH_APP}、" +
        "${ScreenTools.MOVE_TASK} 管理任务。",
) {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        string(ScreenTools.KEY, "按键", required = true)
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult = pressKey(args.display(), args.getString(ScreenTools.KEY), ScreenTools.FULL)
}

private object TypeText : ScreenAction(
    ScreenTools.TYPE_TEXT,
    "在获得输入焦点的输入框的光标处插入文字（有选中内容时替换它），不经过输入法和剪贴板。先点击输入框使它获得焦点；" +
        "密码框和读不到选区的输入框不能在光标处插入，改用 ${ScreenTools.SET_TEXT} 整体替换。" +
        "结果的 action：position 插入位置、length、replaced 替换掉的字符数、cursor 插入后的光标位置（没能设置时为 null）。",
) {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        string(ScreenTools.TEXT, "文字", required = true)
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult = typeText(args.display(), args.getString(ScreenTools.TEXT), ScreenTools.FULL)
}

private object LaunchApp : ScreenAction(
    ScreenTools.LAUNCH_APP,
    "在屏幕上启动 app：intent 非空时按它启动（intent: URI 或普通 URI），否则启动 package 的入口。" +
        "app 已在另一块屏幕上时不会启动（conflict），需要先 ${ScreenTools.MOVE_TASK}。" +
        "结果的 action：launched 启动的组件，visible 表示等待结束时它是否已在这块屏幕上显示。",
) {
    override fun parameters(schema: Schema) = with(schema) {
        display()
        string(ScreenTools.PACKAGE, "包名")
        string(ScreenTools.INTENT, "intent URI")
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult =
        launchApp(args.display(), args.optString(ScreenTools.PACKAGE), args.optString(ScreenTools.INTENT), ScreenTools.FULL)
}

private object MoveTask : ScreenAction(ScreenTools.MOVE_TASK, "把 task 移到指定屏幕。结果的 action.moved 为 false 表示它已在目标屏幕上。") {
    override fun parameters(schema: Schema) = with(schema) {
        integer(ScreenTools.TASK_ID, "task id（见 ${ScreenTools.SCREEN_LIST}）", required = true)
        display()
    }

    override fun IAgentScreen.act(args: JSONObject): ScreenResult = moveTask(args.getInt(ScreenTools.TASK_ID), args.display(), ScreenTools.FULL)
}
