package moe.uni.agent.framework.control

import android.os.Handler
import android.os.HandlerThread
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import org.json.JSONObject
import moe.uni.agent.framework.IAgentEvents
import moe.uni.agent.framework.system.Tasks

/**
 * 等待用的事件（ADR-0034 第 4 条），推给 AgentApp 注册的监听；没有监听时什么都不做。
 * - 屏幕有无障碍事件或新帧时发 changed，同一屏幕 CHANGE_DELAY_MS 内合并成一条；
 * - 无障碍事件中的页面切换、pane、toast、announcement 发带内容的语义事件；
 * - 通知由 NotificationEvents 交来。
 * 所有发送都在自己的线程上按顺序进行，IAgentEvents 是 oneway，到达顺序与发送顺序相同。
 */
class Events(private val tasks: Tasks) {
    private val handler = Handler(HandlerThread(THREAD_NAME).apply { start() }.looper)
    private val listeners = RemoteCallbackList<IAgentEvents>()

    /** 已安排了 changed 的屏幕。 */
    private val scheduled = HashSet<Int>()

    private val active: Boolean get() = listeners.registeredCallbackCount > 0

    fun add(listener: IAgentEvents) {
        listeners.register(listener)
    }

    fun remove(listener: IAgentEvents) {
        listeners.unregister(listener)
    }

    /** 屏幕有变化：无障碍事件或虚拟屏幕的新帧。 */
    fun changed(display: Int) {
        if (!active || display < 0) return
        synchronized(scheduled) { if (!scheduled.add(display)) return }
        handler.postDelayed({
            synchronized(scheduled) { scheduled.remove(display) }
            broadcast(screenEvent(WaitEventFields.CHANGED, display))
        }, CHANGE_DELAY_MS)
    }

    /** 在无障碍服务的主线程上调用：事件返回后会被回收，内容当场取出。 */
    fun onAccessibility(e: AccessibilityEvent) {
        if (!active) return
        changed(e.displayId)
        val pkg = e.packageName?.toString()
        val text = e.text.joinToString(TEXT_SEPARATOR)
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pane = e.contentChangeTypes and PANE_SHOWN_OR_HIDDEN
                when {
                    pane != 0 -> post(WaitEventFields.PANE, e.displayId, pkg) {
                        put(WaitEventFields.TEXT, text).put(WaitEventFields.SHOWN, pane and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED != 0)
                    }
                    // 只有 pane 标题变化的不算页面切换。
                    e.contentChangeTypes and AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_TITLE != 0 -> Unit
                    else -> post(WaitEventFields.WINDOW, e.displayId, pkg) {
                        put(WaitEventFields.CLS, e.className?.toString() ?: JSONObject.NULL).put(WaitEventFields.TEXT, text)
                    }
                }
            }
            // 通知也发这种事件，但不带文字，通知以 NotificationEvents 为准（ADR-0034 第 4 条）。
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> if (e.className?.toString() == TOAST_CLASS) {
                post(WaitEventFields.TOAST, e.displayId, pkg) { put(WaitEventFields.TEXT, text) }
            }
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> post(WaitEventFields.ANNOUNCEMENT, e.displayId, pkg) { put(WaitEventFields.TEXT, text) }
        }
    }

    /** 通知事件（与屏幕无关）。 */
    fun notification(event: JSONObject) {
        if (!active) return
        handler.post { broadcast(event.put(WaitEventFields.TYPE, WaitEventFields.NOTIFICATION)) }
    }

    /**
     * toast 和 announcement 的事件不带屏幕 id，按包名归到正在显示它的屏幕；找不到时归主屏幕：
     * 没有可见 task 的 app（如后台服务）弹的 toast 显示在主屏幕上。同一个 app 不会同时在两块屏幕上（launch_app 返回 conflict）。
     */
    private fun post(type: String, display: Int, pkg: String?, fill: JSONObject.() -> Unit) {
        val event = JSONObject().apply(fill)
        handler.post {
            val d = if (display >= 0) display
            else tasks.all().firstOrNull { it.isVisible && Tasks.packageOf(it) == pkg }?.displayId ?: Display.DEFAULT_DISPLAY
            broadcast(screenEvent(type, d).put(WaitEventFields.PACKAGE, pkg ?: JSONObject.NULL).also { e ->
                event.keys().forEach { e.put(it, event.get(it)) }
            })
        }
    }

    private fun screenEvent(type: String, display: Int) = JSONObject().put(WaitEventFields.TYPE, type)
        .put(WaitEventFields.DISPLAY, display).put(WaitEventFields.MAIN, display == Display.DEFAULT_DISPLAY)

    private fun broadcast(event: JSONObject) {
        val text = event.toString()
        val n = listeners.beginBroadcast()
        try {
            for (i in 0 until n) {
                try {
                    listeners.getBroadcastItem(i).onEvent(text)
                } catch (e: RemoteException) {
                    // 调用方已死，RemoteCallbackList 会自动注销。
                }
            }
        } finally {
            listeners.finishBroadcast()
        }
    }

    private companion object {
        const val THREAD_NAME = "events"
        const val CHANGE_DELAY_MS = 50L
        const val TEXT_SEPARATOR = " "
        val TOAST_CLASS: String = Toast::class.java.name
        const val PANE_SHOWN_OR_HIDDEN =
            AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED
    }
}
