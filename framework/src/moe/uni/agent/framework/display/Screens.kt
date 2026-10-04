package moe.uni.agent.framework.display

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.ServiceSpecificException
import android.util.DisplayMetrics
import android.view.Display
import moe.uni.agent.framework.IAgentScreen

/**
 * 两类屏幕（ADR-0022 第 1、7 条）：主屏幕 0 和 agent 的虚拟屏幕。IAgentScreen 里 display 为 -1 时指虚拟屏幕。
 * 以虚拟屏幕为目标时休眠就唤醒；以主屏幕为目标时灭屏或锁屏就报错，不唤醒主屏幕。
 */
class Screens(context: Context, val virtual: VirtualScreen) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)

    private val main: Display get() = displays.getDisplay(Display.DEFAULT_DISPLAY)

    val mainOn: Boolean get() = main.state == Display.STATE_ON
    val mainLocked: Boolean get() = keyguard.isKeyguardLocked

    fun isMain(display: Int) = display == Display.DEFAULT_DISPLAY

    /** 解析调用方给的屏幕 id，返回实际 id；woke 回调在虚拟屏幕刚被唤醒时调用。 */
    fun resolve(display: Int, woke: (Int) -> Unit): Int {
        if (isMain(display)) {
            if (!mainOn) throw ServiceSpecificException(IAgentScreen.ERROR_UNAVAILABLE, "主屏幕已灭屏")
            if (mainLocked) throw ServiceSpecificException(IAgentScreen.ERROR_UNAVAILABLE, "主屏幕已锁屏")
            return display
        }
        val id = virtual.id
        if (display != VIRTUAL && display != id) {
            throw ServiceSpecificException(IAgentScreen.ERROR_INVALID_ARGUMENT, "没有屏幕 $display（主屏幕是 ${Display.DEFAULT_DISPLAY}，虚拟屏幕是 $id）")
        }
        if (virtual.wake()) woke(id)
        return id
    }

    fun width(display: Int) = if (isMain(display)) mainMetrics().widthPixels else virtual.width

    fun height(display: Int) = if (isMain(display)) mainMetrics().heightPixels else virtual.height

    /** 屏幕的逻辑矩形，节点边界按它判断是否在屏幕上（ADR-0022 第 9b 条）。 */
    fun bounds(display: Int) = Rect(0, 0, width(display), height(display))

    private fun mainMetrics() = DisplayMetrics().also { main.getRealMetrics(it) }

    companion object {
        /** IAgentScreen 中指代虚拟屏幕的 display 值（AIDL 常量）。 */
        const val VIRTUAL = IAgentScreen.DISPLAY_VIRTUAL
    }
}
