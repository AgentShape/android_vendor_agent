package moe.uni.agent.framework.a11y

import android.content.ComponentName
import android.graphics.Rect
import android.os.ServiceSpecificException
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import moe.uni.agent.framework.ActionFailure
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.framework.ToolError

/**
 * 每块屏幕的窗口、最近一次返回状态时的节点和元素编号的记忆（ADR-0022 第 9a 条）。编号是元素的标签：
 * 记忆里身份相同（AccessibilityNodeInfo.equals，即 sourceNodeId + windowId）且内容指纹相同的节点沿用旧编号，
 * 否则取计数器的下一个号，退下的编号不再复用；指纹由 NodeTree 给出。记忆跨快照保留，前台包名变化时清空并归零。
 * 编号只解析到最近一次快照里的节点。调用方（ScreenControl）负责串行化。
 */
class Nodes {
    /** 记忆里的一个节点：编号、内容指纹、最后出现的时间（uptime 毫秒）。 */
    private class Entry(val number: Int, val fingerprint: List<String?>, val seen: Long)

    /** 一块屏幕的记忆。 */
    private class Memory {
        /** 记忆所属的前台包名。 */
        var pkg: String? = null
        val entries = HashMap<AccessibilityNodeInfo, Entry>()

        /** 计数器：用过的最大编号。 */
        var last = 0

        /** 最近一次快照：编号 → 节点。 */
        var current: Map<Int, AccessibilityNodeInfo> = emptyMap()
    }

    private val memories = HashMap<Int, Memory>()

    fun windows(display: Int): List<AccessibilityWindowInfo> {
        val service = ScreenAccessibility.instance
            ?: throw ServiceSpecificException(IAgentScreen.ERROR_UNAVAILABLE, "AgentFramework 的无障碍服务没有连接")
        return service.windowsOnAllDisplays.get(display).orEmpty()
    }

    /** 构建新的节点树，它成为这块屏幕的最近一次状态；top 是屏幕信息的 top（前台 activity），screen 是屏幕的逻辑矩形。 */
    fun build(display: Int, top: String?, screen: Rect, windows: List<AccessibilityWindowInfo> = windows(display)): NodeTree =
        NodeTree(windows, screen) { nodes, fingerprints ->
            number(display, top?.let { ComponentName.unflattenFromString(it)?.packageName }, nodes, fingerprints)
        }

    /** 编号对应最近一次快照里的节点，先刷新；元素已不在界面上就如实失败。 */
    fun element(display: Int, n: Int): AccessibilityNodeInfo {
        val node = memories[display]?.current?.get(n)
            ?: throw ActionFailure(ToolError.NOT_FOUND, "屏幕 $display 最近一次返回的状态中没有元素 $n，它已不在界面上或内容已变")
        if (!node.refresh()) throw ActionFailure(ToolError.NOT_FOUND, "元素 $n 已不在界面上")
        return node
    }

    private fun number(display: Int, pkg: String?, nodes: List<AccessibilityNodeInfo>, fingerprints: List<List<String?>>): IntArray {
        val m = memories.getOrPut(display) { Memory() }
        if (pkg != m.pkg) {
            m.pkg = pkg
            m.entries.clear()
            m.last = 0
        }
        val now = SystemClock.uptimeMillis()
        val numbers = IntArray(nodes.size) { i ->
            val number = m.entries[nodes[i]]?.takeIf { it.fingerprint == fingerprints[i] }?.number ?: ++m.last
            m.entries[nodes[i]] = Entry(number, fingerprints[i], now)
            number
        }
        m.entries.values.removeAll { now - it.seen > RETAIN_MS }
        // element() 刷新和操作的是这次快照的节点对象。
        m.current = nodes.indices.associate { numbers[it] to nodes[it] }
        return numbers
    }

    /** 获得输入焦点的可编辑节点。 */
    fun focusedEditable(display: Int): AccessibilityNodeInfo? = windows(display).firstNotNullOfOrNull { w ->
        w.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
    }

    fun imeShown(windows: List<AccessibilityWindowInfo>) = windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    companion object {
        /**
         * 超过这么久没出现的节点从记忆里丢弃，只为限制记忆大小（编号单调，丢弃不会造成重复）。
         * 按时间而不是快照次数：exec_js 的等待在界面每次变化时都可能建一次树；5 分钟足以覆盖一次弹窗或对话框从打开到关闭的停留。
         */
        private const val RETAIN_MS = 5 * 60 * 1000L
    }
}
