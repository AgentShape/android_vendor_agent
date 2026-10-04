package moe.uni.agent.framework.device

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject

/** 剪贴板（ADR-0029 第 6 条）。system uid 有 READ_CLIPBOARD_IN_BACKGROUND，读取不需要窗口焦点。 */
internal class Clipboard(private val context: Context) {
    private val manager: ClipboardManager get() = context.getSystemService(ClipboardManager::class.java)

    fun get(): JSONObject {
        val clip = manager.primaryClip
        val text = clip?.let { c -> (0 until c.itemCount).joinToString("\n") { c.getItemAt(it).coerceToText(context) } }
        return Results.ok().put(TEXT, text ?: JSONObject.NULL)
    }

    fun set(text: String): JSONObject {
        manager.setPrimaryClip(ClipData.newPlainText(LABEL, text))
        return Results.ok()
    }

    private companion object {
        const val LABEL = "agent"
        const val TEXT = "text"
    }
}
