package moe.uni.agent.framework.a11y

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction

/** 无障碍节点上的读取与 action 辅助（ADR-0022 第 10、11 条）。 */
object NodeActions {
    /** 标准 AccessibilityAction 的 id → 名字（ACTION_CLICK 等），node_action 按名字查找。 */
    val STANDARD: Map<Int, String> = AccessibilityAction::class.java.fields
        .filter { it.type == AccessibilityAction::class.java && it.name.startsWith(ACTION_PREFIX) }
        .associate { (it.get(null) as AccessibilityAction).id to it.name }

    private const val ACTION_PREFIX = "ACTION_"

    private val SCROLLS = listOf(
        android.R.id.accessibilityActionScrollUp to Directions.UP,
        android.R.id.accessibilityActionScrollDown to Directions.DOWN,
        android.R.id.accessibilityActionScrollLeft to Directions.LEFT,
        android.R.id.accessibilityActionScrollRight to Directions.RIGHT,
    )

    fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }

    /**
     * 边界与屏幕矩形的交集；边界为空（含上下颠倒，滚动裁剪后的行会这样）或与屏幕不相交时为 null（ADR-0022 第 9b 条）。
     * 先排除空边界：Rect.intersect 对上下颠倒的矩形也可能返回 true。
     */
    fun visibleBounds(node: AccessibilityNodeInfo, screen: Rect): Rect? =
        bounds(node).takeUnless { it.isEmpty }?.takeIf { it.intersect(screen) }

    /** 正在显示提示文字时，真实内容是空的。 */
    fun text(node: AccessibilityNodeInfo): String? = if (node.isShowingHintText) "" else node.text?.toString()

    /** 自定义 action（有标签、不是标准 action），节点树里单独列出。 */
    fun customActions(node: AccessibilityNodeInfo) = node.actionList.filter { it.id !in STANDARD && !it.label.isNullOrEmpty() }

    fun name(action: AccessibilityAction) = STANDARD[action.id] ?: action.label?.let { "\"$it\"" } ?: "action ${action.id}"

    /** 按标签或标准名字（可省略 ACTION_ 前缀）找 action。 */
    fun find(node: AccessibilityNodeInfo, wanted: String): AccessibilityAction? =
        node.actionList.firstOrNull { it.label?.toString() == wanted }
            ?: node.actionList.firstOrNull { a ->
                STANDARD[a.id]?.let { it.equals(wanted, true) || it.removePrefix(ACTION_PREFIX).equals(wanted, true) } == true
            }

    /** 节点支持的滚动方向；没有具体方向时退回 forward/backward。 */
    fun scrollDirections(node: AccessibilityNodeInfo): List<String> {
        val ids = node.actionList.map { it.id }.toSet()
        val directions = SCROLLS.filter { it.first in ids }.map { it.second }
        if (directions.isNotEmpty()) return directions
        return listOfNotNull(
            Directions.FORWARD.takeIf { AccessibilityNodeInfo.ACTION_SCROLL_FORWARD in ids },
            Directions.BACKWARD.takeIf { AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD in ids },
        )
    }

    /** 向 direction 滚动要用的 action；没有对应 action 时返回 null。 */
    fun scrollAction(node: AccessibilityNodeInfo, direction: String): Int? {
        val ids = node.actionList.map { it.id }.toSet()
        SCROLLS.firstOrNull { it.second == direction && it.first in ids }?.let { return it.first }
        val generic = if (Directions.isForward(direction)) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return generic.takeIf { it in ids }
    }

    fun setText(node: AccessibilityNodeInfo, text: String) = node.performAction(
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
    )

    /** TextView 在选区已经是目标值时返回 false（SET_TEXT 之后光标本来就在末尾），这种情况算成功。 */
    fun select(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean =
        (node.textSelectionStart == start && node.textSelectionEnd == end) ||
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            })
}
