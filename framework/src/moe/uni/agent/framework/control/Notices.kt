package moe.uni.agent.framework.control

import android.util.Log
import moe.uni.agent.framework.FrameworkApp
import org.json.JSONObject

/**
 * 系统回调（安全窗口等）产生的事件，放进下一次结果的 events 字段（ADR-0025、ADR-0030）。
 * 回调线程和调用线程都会访问。
 */
class Notices {
    private val pending = ArrayList<String>()

    fun add(event: JSONObject) {
        val text = event.toString()
        Log.i(FrameworkApp.TAG, "event $text")
        synchronized(pending) { pending += text }
    }

    fun take(): Array<String> = synchronized(pending) { pending.toTypedArray().also { pending.clear() } }
}
