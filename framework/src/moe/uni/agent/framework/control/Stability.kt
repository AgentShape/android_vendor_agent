package moe.uni.agent.framework.control

import android.os.SystemClock
import android.view.Display
import moe.uni.agent.framework.a11y.ScreenAccessibility
import moe.uni.agent.framework.display.VirtualScreen

/**
 * UI 稳定判断（ADR-0022 第 8 条）：从 start 起，目标屏幕一段时间内没有无障碍事件（虚拟屏幕还要求没有新帧）
 * 即视为稳定，设等待上限。计时从动作开始，与 ADR-0023 的动作效果重叠。
 */
class Stability(private val virtual: VirtualScreen) {
    /** 返回是否已稳定；false 表示到上限时界面仍在变化（ADR-0034 第 2 条）。start 早于上限时（推迟的等待）只看现在是否静默。 */
    fun await(display: Int, start: Long = SystemClock.uptimeMillis()): Boolean {
        SystemClock.sleep(maxOf(0L, start + MIN_MS - SystemClock.uptimeMillis()))
        while (SystemClock.uptimeMillis() - start < MAX_MS) {
            if (quiet(display, start)) return true
            SystemClock.sleep(POLL_MS)
        }
        return quiet(display, start)
    }

    /** 现在是否已静默：没有推迟的稳定等待时，state 的 settled 按它给出。 */
    fun quiet(display: Int, start: Long = 0L): Boolean {
        val frame = if (display == Display.DEFAULT_DISPLAY) 0L else virtual.lastFrame
        val last = maxOf(start, ScreenAccessibility.lastEvent[display] ?: 0L, frame)
        return SystemClock.uptimeMillis() - last >= QUIET_MS
    }

    companion object {
        private const val MIN_MS = 100L
        private const val QUIET_MS = 250L
        const val MAX_MS = 4000L
        const val POLL_MS = 50L
    }
}
