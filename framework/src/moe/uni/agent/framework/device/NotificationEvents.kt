package moe.uni.agent.framework.device

import android.content.ComponentName
import android.content.Context
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.framework.control.WaitEventFields

/**
 * 等待用的通知事件（ADR-0034 第 4 条）。以系统身份注册（registerAsSystemService，与 SystemUI 相同，system uid 可用），
 * 不需要用户开"通知使用权"（validation/stage-t.md T0 第 1 项）。字段与 notification_list 的单条相同，
 * 另加 change；更新同样走 onNotificationPosted，按 key 是否已见过区分 posted 和 updated。
 * AgentApp 自己的通知（观察窗气泡等）不算：它们由 agent 的操作引起，会让 s.notification() 误命中。
 */
class NotificationEvents(private val send: (JSONObject) -> Unit) : NotificationListenerService() {
    private val keys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun register(context: Context) {
        try {
            registerAsSystemService(context, ComponentName(context, NotificationEvents::class.java), UserHandle.USER_ALL)
        } catch (e: Exception) {
            Log.w(FrameworkApp.TAG, "通知监听注册失败", e)
        }
    }

    override fun onListenerConnected() {
        // 注册之前已有的通知：之后再 post 算更新。
        try {
            activeNotifications?.forEach { keys += it.key }
        } catch (e: Exception) {
            Log.w(FrameworkApp.TAG, "读取已有通知失败", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        if (sbn.packageName == IAgentScreen.CALLER_PACKAGE) return
        val change = if (keys.add(sbn.key)) WaitEventFields.POSTED else WaitEventFields.UPDATED
        send(Notifications.json(sbn).put(WaitEventFields.CHANGE, change))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        if (sbn.packageName == IAgentScreen.CALLER_PACKAGE) return
        keys -= sbn.key
        send(Notifications.json(sbn).put(WaitEventFields.CHANGE, WaitEventFields.REMOVED).put(WaitEventFields.REASON, reason))
    }
}
