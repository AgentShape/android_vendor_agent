package moe.uni.agent.framework.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import moe.uni.agent.framework.ActionFailure
import moe.uni.agent.framework.ToolError
import moe.uni.agent.framework.display.VirtualScreen

/**
 * 注入触摸和按键（ADR-0022 第 4、10 条）：虚拟屏幕用 VirtualTouchscreen/VirtualKeyboard，主屏幕用 injectInputEvent。
 * 只报告注入结果，界面效果由调用方返回的状态体现。
 */
class Injector(context: Context, private val virtual: VirtualScreen) {
    private val input = context.getSystemService(InputManager::class.java)

    fun tap(display: Int, x: Float, y: Float, times: Int, longPress: Boolean) {
        repeat(times) { i ->
            if (i > 0) SystemClock.sleep(TAP_INTERVAL_MS)
            val down = SystemClock.uptimeMillis()
            touch(display, MotionEvent.ACTION_DOWN, x, y, down)
            SystemClock.sleep(if (longPress) LONG_PRESS_MS else TAP_MS)
            touch(display, MotionEvent.ACTION_UP, x, y, down)
        }
    }

    /** 按住从一点拖到另一点；hold 是抬起前在终点停顿的毫秒数，用来避免惯性滚动。 */
    fun drag(display: Int, fx: Float, fy: Float, tx: Float, ty: Float, duration: Int, hold: Int) {
        val down = SystemClock.uptimeMillis()
        touch(display, MotionEvent.ACTION_DOWN, fx, fy, down)
        val steps = (duration / MOVE_STEP_MS).coerceAtLeast(2)
        for (i in 1..steps) {
            SystemClock.sleep((duration / steps).toLong())
            touch(display, MotionEvent.ACTION_MOVE, fx + (tx - fx) * i / steps, fy + (ty - fy) * i / steps, down)
        }
        if (hold > 0) {
            SystemClock.sleep(hold.toLong())
            touch(display, MotionEvent.ACTION_MOVE, tx, ty, down)
        }
        touch(display, MotionEvent.ACTION_UP, tx, ty, down)
    }

    /** 按下并抬起组合键；返回是否全部注入成功。 */
    fun key(display: Int, combo: Keys.Combo): Boolean {
        if (display != Display.DEFAULT_DISPLAY) {
            val results = combo.modifiers.map { virtual.key(it, true) } +
                virtual.key(combo.key, true) + virtual.key(combo.key, false) +
                combo.modifiers.reversed().map { virtual.key(it, false) }
            return results.all { it }
        }
        val now = SystemClock.uptimeMillis()
        val meta = Keys.metaState(combo.modifiers)
        return listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).all { action ->
            val event = KeyEvent(now, SystemClock.uptimeMillis(), action, combo.key, 0, meta,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD)
            event.setDisplayId(display)
            input.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT)
        }
    }

    private fun touch(display: Int, action: Int, x: Float, y: Float, downTime: Long) {
        val ok = if (display == Display.DEFAULT_DISPLAY) {
            val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            event.setDisplayId(display)
            try {
                input.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT)
            } finally {
                event.recycle()
            }
        } else {
            virtual.touch(action, x, y)
        }
        if (!ok) throw ActionFailure(ToolError.FAILED, "注入触摸事件失败（${MotionEvent.actionToString(action)} $x,$y）")
    }

    companion object {
        private const val TAP_MS = 50L
        private const val LONG_PRESS_MS = 800L
        private const val TAP_INTERVAL_MS = 80L
        private const val MOVE_STEP_MS = 10
    }
}
