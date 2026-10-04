package moe.uni.agent.framework.input

import android.view.KeyEvent
import moe.uni.agent.framework.ActionFailure
import moe.uni.agent.framework.ToolError

/**
 * press_key 的按键支持表，按目标屏幕区分（ADR-0022 第 10 条，Q38）。
 * 普通键取自本树 VirtualKeyboard 的 native KEY_CODE_MAPPING（frameworks/native/libs/input/VirtualInputDevice.cpp），
 * 不含 Java 层拒绝的 DPAD_CENTER，也不含 HOME、RECENT_APPS、音量、亮度等系统键。
 */
object Keys {
    class Combo(val name: String, val modifiers: List<Int>, val key: Int)

    private const val KEYCODE_PREFIX = "KEYCODE_"
    private const val SEPARATOR = "+"
    private const val BACK = "BACK"
    private const val HOME = "HOME"
    private const val APP_SWITCH = "APP_SWITCH"

    private val MODIFIERS = mapOf(
        "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
        "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
        "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
        "META" to KeyEvent.KEYCODE_META_LEFT,
    )

    private val KEYBOARD = ('A'..'Z').map { "$it" } + ('0'..'9').map { "$it" } + (1..12).map { "F$it" } + listOf(
        "GRAVE", "MINUS", "EQUALS", "LEFT_BRACKET", "RIGHT_BRACKET", "BACKSLASH", "SEMICOLON", "APOSTROPHE",
        "COMMA", "PERIOD", "SLASH", "SPACE", "ENTER", "TAB", "ESCAPE", "DEL", "FORWARD_DEL", "INSERT",
        "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT", "MOVE_HOME", "MOVE_END", "PAGE_UP", "PAGE_DOWN",
    )

    /** 虚拟屏幕：VirtualKeyboard。没有桌面和最近任务，不支持 HOME/APP_SWITCH。 */
    private val VIRTUAL = (KEYBOARD + BACK).toSet()

    /** 主屏幕：injectInputEvent，导航键使用主屏幕的系统语义。 */
    private val MAIN = (KEYBOARD + listOf(BACK, HOME, APP_SWITCH)).toSet()

    /** 解析 "CTRL+A" 这样的组合键；不在目标屏幕支持表中时在注入前报错。 */
    fun parse(text: String, main: Boolean): Combo {
        val parts = text.split(SEPARATOR).map { it.trim().uppercase().removePrefix(KEYCODE_PREFIX) }
        if (parts.any { it.isEmpty() }) throw ActionFailure(ToolError.INVALID_ARGUMENT, "按键格式无效：$text")
        val key = parts.last()
        val modifiers = parts.dropLast(1).map {
            MODIFIERS[it] ?: throw ActionFailure(ToolError.INVALID_ARGUMENT, "$it 不是修饰键（修饰键有 ${MODIFIERS.keys.joinToString("、")}）")
        }
        if (!main && (key == HOME || key == APP_SWITCH)) {
            throw ActionFailure(ToolError.UNSUPPORTED, "虚拟屏幕不支持 $key：没有桌面和最近任务")
        }
        if (key !in (if (main) MAIN else VIRTUAL)) throw ActionFailure(ToolError.UNSUPPORTED, "$key 不在${if (main) "主" else "虚拟"}屏幕的按键支持表中")
        return Combo(parts.joinToString(SEPARATOR), modifiers, KeyEvent.keyCodeFromString(KEYCODE_PREFIX + key))
    }

    /** 修饰键对应的 metaState，给主屏幕注入用。 */
    fun metaState(modifiers: List<Int>): Int = modifiers.fold(0) { state, code ->
        state or when (code) {
            KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            else -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        }
    }
}
