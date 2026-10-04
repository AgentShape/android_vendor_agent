package moe.uni.agent.framework.a11y

/**
 * 节点的 JSON 字段名（ADR-0024），query 的 nodes 和选择器都用它们；结果的其他字段见 control/ResultFields.kt。
 * AgentApp 的 exec_prelude.js 按这些名字读取和构造选择器，它不能引用本文件，改动时两边一起改。
 */
object NodeFields {
    const val ELEMENT = "element"
    const val CLS = "cls"
    const val TEXT = "text"
    const val DESC = "desc"
    const val HINT = "hint"
    const val LABEL = "label"
    const val ID = "id"
    const val BOUNDS = "bounds"
    const val CLICKABLE = "clickable"
    const val LONG_CLICKABLE = "longClickable"
    const val CHECKABLE = "checkable"
    const val CHECKED = "checked"
    const val EDITABLE = "editable"
    const val PASSWORD = "password"
    const val SCROLLABLE = "scrollable"
    const val FOCUSED = "focused"
    const val SELECTED = "selected"
    const val ENABLED = "enabled"
    const val ACTIONS = "actions"

    /** 边界部分在屏幕外（ADR-0022 第 9b 条）。 */
    const val OFFSCREEN = "offscreen"
}

/** 选择器里除节点字段以外的键。 */
object SelectorKeys {
    const val INDEX = "index"
    const val CONTAINS_SUFFIX = "Contains"
}

/** 滚动方向：scroll 的参数，也是节点 scrollable 字段的取值。 */
object Directions {
    const val UP = "up"
    const val DOWN = "down"
    const val LEFT = "left"
    const val RIGHT = "right"
    const val FORWARD = "forward"
    const val BACKWARD = "backward"

    val ALL = listOf(UP, DOWN, LEFT, RIGHT)

    /** 内容朝这些方向滚动时对应 ACTION_SCROLL_FORWARD。 */
    fun isForward(direction: String) = direction == DOWN || direction == RIGHT
}
