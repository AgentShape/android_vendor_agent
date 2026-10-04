package moe.uni.agent.framework.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一块屏幕的节点树（ADR-0022 第 9 条）：给模型看的文本，每行一个节点，缩进表示层级；
 * 以及给 exec_js 用的结构化形式（ADR-0024）。以交互能力为主剪枝，不依赖 contentDescription；
 * 不满足保留条件的容器删掉本身，子节点提到上一层。screen 是屏幕的逻辑矩形：边界为空或与它不相交的节点不保留，
 * 部分在它外面的标 offscreen（ADR-0022 第 9b 条）。WebView 子树的剪枝见第 9c 条。
 * 编号由 number 按节点和它们的内容指纹给出（Nodes 跨快照保持，ADR-0022 第 9a 条）：先遍历出保留的节点，编号之后再生成文本。
 */
class NodeTree(
    windows: List<AccessibilityWindowInfo>,
    private val screen: Rect,
    number: (nodes: List<AccessibilityNodeInfo>, fingerprints: List<List<String?>>) -> IntArray,
) {
    /** 保留的节点，按先序排列。 */
    val nodes = ArrayList<AccessibilityNodeInfo>()

    /** nodes[i] 的编号；文本里不单调。 */
    val numbers: IntArray
    val text: String

    /** 输入焦点所在节点的编号，没有时为 0；不写进 text，由屏幕信息的 focused 字段给出（ADR-0030 第 4 条）。 */
    val focused: Int

    /** 文本的行：节点行是节点下标、缩进层级和编号之后的内容，窗口行的下标为 -1。 */
    private class Row(val index: Int, val depth: Int, val body: String)

    private val rows = ArrayList<Row>()

    /** nodes[i] 的内容指纹。 */
    private val fingerprints = ArrayList<List<String?>>()

    init {
        for (window in windows) {
            val root = window.root ?: continue
            val header = StringBuilder("window ").append(windowType(window.type))
            window.title?.let { header.append(' ').append(quote(it)) }
            if (window.isFocused) header.append(" (focused)")
            rows += Row(-1, 0, header.toString())
            walk(root, 1, false, emptyList())
        }
        numbers = number(nodes, fingerprints)
        val focus = windows.firstNotNullOfOrNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
        focused = focus?.let { numbers.getOrNull(nodes.indexOf(it)) } ?: 0
        text = rows.joinToString("\n") { row ->
            if (row.index < 0) row.body else INDENT.repeat(row.depth) + "[${numbers[row.index]}] " + row.body
        }
    }

    /** nodes[index] 的结构化形式，字段与节点树的一行对应；空值省略。 */
    fun json(index: Int): JSONObject {
        val n = nodes[index]
        val r = NodeActions.bounds(n)
        val o = JSONObject().put(NodeFields.ELEMENT, numbers[index]).put(NodeFields.CLS, shortClass(n))
        ownText(n)?.let { o.put(NodeFields.TEXT, it) }
        n.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { o.put(NodeFields.DESC, it) }
        n.hintText?.toString()?.takeIf { it.isNotEmpty() }?.let { o.put(NodeFields.HINT, it) }
        if (unlabeled(n)) label(n).takeIf { it.isNotEmpty() }?.let { o.put(NodeFields.LABEL, it) }
        shortId(n)?.let { o.put(NodeFields.ID, it) }
        o.put(NodeFields.BOUNDS, JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
        o.put(NodeFields.CLICKABLE, n.isClickable).put(NodeFields.LONG_CLICKABLE, n.isLongClickable)
            .put(NodeFields.CHECKABLE, n.isCheckable).put(NodeFields.CHECKED, n.isChecked)
            .put(NodeFields.EDITABLE, n.isEditable).put(NodeFields.PASSWORD, n.isPassword)
            .put(NodeFields.SCROLLABLE, JSONArray(NodeActions.scrollDirections(n))).put(NodeFields.FOCUSED, n.isFocused)
            .put(NodeFields.SELECTED, n.isSelected).put(NodeFields.ENABLED, n.isEnabled)
            .put(NodeFields.OFFSCREEN, !screen.contains(r))
        NodeActions.customActions(n).takeIf { it.isNotEmpty() }
            ?.let { list -> o.put(NodeFields.ACTIONS, JSONArray(list.map { it.label.toString() })) }
        return o
    }

    /**
     * 不可见的节点连同子孙跳过；不保留的节点（不满足保留条件，或边界为空、不在屏幕上）删掉本身，子节点提到上一层。
     * web：是否在 WebView 子树内；kept：最近的被保留祖先的自身 text 和 contentDescription（ADR-0022 第 9c 条）。
     */
    private fun walk(node: AccessibilityNodeInfo, depth: Int, web: Boolean, kept: List<String>) {
        if (!node.isVisibleToUser) return
        var childDepth = depth
        var childKept = kept
        if (NodeActions.visibleBounds(node, screen) != null && keep(node, web, kept)) {
            val label = if (unlabeled(node)) label(node) else null
            rows += Row(nodes.size, depth, line(node, label))
            fingerprints += fingerprint(node, label)
            nodes += node
            childDepth++
            childKept = listOfNotNull(ownText(node), node.contentDescription?.toString()?.takeIf { it.isNotEmpty() })
        }
        val childWeb = web || node.className?.toString() == WEBVIEW_CLASS
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, childDepth, childWeb, childKept) }
    }

    /**
     * 保留条件（第 9 条）。WebView 子树内能获得焦点不算理由（第 9c 条第 1 点：Chromium 把大量没有内容的容器标成 focusable）；
     * 只因文字保留的节点，文字与最近的被保留祖先的自身 text 或 contentDescription 相同时不保留（第 9c 条第 2 点：
     * Chromium 把链接名放在 contentDescription，下面是同文字的 TextView）。
     */
    private fun keep(n: AccessibilityNodeInfo, web: Boolean, kept: List<String>) =
        interactive(n) || n.isScrollable || (n.isFocusable && !web) ||
            !n.contentDescription.isNullOrEmpty() || !n.hintText.isNullOrEmpty() || n.isSelected || n.isFocused ||
            (!n.text.isNullOrEmpty() && ownText(n) !in kept)

    private fun interactive(n: AccessibilityNodeInfo) = n.isClickable || n.isLongClickable || n.isCheckable || n.isEditable

    /** 可交互但没有文字、描述和提示的节点，用子孙节点的文字作标签。 */
    private fun unlabeled(n: AccessibilityNodeInfo) =
        interactive(n) && ownText(n) == null && n.contentDescription.isNullOrEmpty() && n.hintText.isNullOrEmpty()

    /**
     * 内容指纹（ADR-0022 第 9a 条第 1 点）：模型在这一行看到的内容——类名、资源 id、contentDescription、
     * 非可编辑节点的 text（可编辑节点的文字会被输入改变）和没有自身标签的可交互节点的聚合标签。
     */
    private fun fingerprint(n: AccessibilityNodeInfo, label: String?): List<String?> = listOf(
        n.className?.toString(), n.viewIdResourceName, n.contentDescription?.toString(),
        n.text?.toString()?.takeUnless { n.isEditable }, label,
    )

    /** 一行中编号之后的部分；label 是 unlabeled 节点的聚合标签，其他节点为 null。 */
    private fun line(n: AccessibilityNodeInfo, label: String?): String {
        val parts = mutableListOf(shortClass(n))
        val flags = mutableListOf<String>()
        if (n.isClickable) flags += "clickable"
        if (n.isLongClickable) flags += "long-clickable"
        if (n.isCheckable) flags += if (n.isChecked) "checked" else "unchecked"
        if (n.isEditable) flags += "editable"
        if (n.isPassword) flags += "password"
        NodeActions.scrollDirections(n).takeIf { it.isNotEmpty() }?.let { flags += "scrollable: ${it.joinToString(",")}" }
        if (n.isFocused) flags += "focused"
        if (n.isSelected) flags += "selected"
        if (!n.isEnabled) flags += "disabled"
        val r = NodeActions.bounds(n)
        if (!screen.contains(r)) flags += "offscreen"
        if (flags.isNotEmpty()) parts += "(${flags.joinToString(", ")})"

        ownText(n)?.let { parts += quote(it) }
        n.contentDescription?.takeIf { it.isNotEmpty() }?.let { parts += "desc=${quote(it)}" }
        n.hintText?.takeIf { it.isNotEmpty() }?.let { parts += "hint=${quote(it)}" }
        label?.let { parts += if (it.isEmpty()) "unlabeled" else "label=${quote(it)}" }
        shortId(n)?.let { parts += "#$it" }
        NodeActions.customActions(n).takeIf { it.isNotEmpty() }
            ?.let { list -> parts += "actions=[${list.joinToString(",") { quote(it.label) }}]" }
        parts += "[${r.left},${r.top},${r.right},${r.bottom}]"
        return parts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun label(n: AccessibilityNodeInfo) = StringBuilder().also { collectLabel(n, it) }.toString().trim()

    private fun collectLabel(n: AccessibilityNodeInfo, into: StringBuilder) {
        for (i in 0 until n.childCount) {
            if (into.length >= LABEL_MAX) return
            val child = n.getChild(i) ?: continue
            val own = child.text?.takeUnless { child.isShowingHintText } ?: child.contentDescription
            if (!own.isNullOrEmpty()) into.append(own).append(' ')
            collectLabel(child, into)
        }
    }

    companion object {
        private const val INDENT = "  "
        private const val TEXT_MAX = 200
        private const val LABEL_MAX = 60
        private const val ID_SEPARATOR = ":id/"

        /** 这个类名的祖先之下是 WebView 子树（ADR-0022 第 9c 条）。 */
        private const val WEBVIEW_CLASS = "android.webkit.WebView"

        /** 混淆过的 id 通常只有一两个字符，省略。 */
        private const val OBFUSCATED_ID_MAX = 2

        private fun ownText(n: AccessibilityNodeInfo) = n.text?.toString()?.takeUnless { it.isEmpty() || n.isShowingHintText }

        private fun shortClass(n: AccessibilityNodeInfo) = n.className?.toString()?.substringAfterLast('.').orEmpty()

        private fun shortId(n: AccessibilityNodeInfo) =
            n.viewIdResourceName?.substringAfter(ID_SEPARATOR)?.takeIf { it.length > OBFUSCATED_ID_MAX }

        fun quote(value: CharSequence): String {
            val s = value.toString().let { if (it.length > TEXT_MAX) it.take(TEXT_MAX) + "…" else it }
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        }

        private fun windowType(type: Int) = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "ime"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "divider"
            else -> "other"
        }
    }
}
