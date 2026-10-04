package moe.uni.agent.framework.device

import android.app.ActivityOptions
import android.app.INotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.ServiceManager
import android.service.notification.NotificationStats
import android.service.notification.StatusBarNotification
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.statusbar.NotificationVisibility
import moe.uni.agent.framework.ToolError
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知的列出、打开和清除（ADR-0029 第 6 条）。经 INotificationManager 读取全部应用的通知（system uid 有 ACCESS_NOTIFICATIONS）。
 * 打开和清除按用户在通知栏里的操作处理：执行点击动作后报告点击、清除时报告划掉，都经 IStatusBarService
 * （与 SystemUI 相同，system uid 有 STATUS_BAR_SERVICE），自动取消等规则由 NotificationManagerService 处理。
 * 打开的界面出现在主屏幕上。
 */
internal class Notifications(private val context: Context) {
    private val service: INotificationManager
        get() = INotificationManager.Stub.asInterface(ServiceManager.getService(Context.NOTIFICATION_SERVICE))

    fun list(): JSONObject {
        val items = active().sortedByDescending { it.postTime }.map(::json)
        return Results.ok().put(NOTIFICATIONS, JSONArray(items)).put(COUNT, items.size)
    }

    fun open(key: String): JSONObject {
        val sbn = find(key) ?: return notFound(key)
        val intent = sbn.notification.contentIntent ?: return Results.failure(ToolError.UNSUPPORTED, "这条通知没有点击动作")
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS).toBundle()
        try {
            intent.send(context, 0, null, null, null, null, options)
        } catch (e: PendingIntent.CanceledException) {
            return Results.failure(ToolError.FAILED, "这条通知的点击动作已经失效")
        }
        statusBar.onNotificationClick(sbn.key, visibility(sbn))
        return Results.ok()
    }

    fun dismiss(key: String): JSONObject {
        val sbn = find(key) ?: return notFound(key)
        if (!sbn.isClearable) return Results.failure(ToolError.UNSUPPORTED, "常驻通知不能清除")
        statusBar.onNotificationClear(sbn.packageName, sbn.user.identifier, sbn.key, NotificationStats.DISMISSAL_OTHER,
            NotificationStats.DISMISS_SENTIMENT_NEUTRAL, visibility(sbn), false)
        return Results.ok()
    }

    private fun notFound(key: String) = Results.failure(ToolError.NOT_FOUND, "没有 key 为 $key 的通知")

    private fun active(): Array<StatusBarNotification> =
        service.getActiveNotificationsWithAttribution(context.packageName, null) ?: emptyArray()

    private fun find(key: String): StatusBarNotification? {
        if (key.isEmpty()) throw Results.invalid("key 为空")
        return active().firstOrNull { it.key == key }
    }

    private val statusBar: IStatusBarService
        get() = IStatusBarService.Stub.asInterface(ServiceManager.getService(Context.STATUS_BAR_SERVICE))

    private fun visibility(sbn: StatusBarNotification): NotificationVisibility =
        NotificationVisibility.obtain(sbn.key, 0, 1, true)

    companion object {
        /** 一条通知的字段；等待用的通知事件（NotificationEvents）也用它。 */
        fun json(sbn: StatusBarNotification): JSONObject {
            val extras = sbn.notification.extras
            return JSONObject().put(KEY, sbn.key).put(PACKAGE, sbn.packageName)
                .put(TITLE, extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: JSONObject.NULL)
                .put(TEXT, (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                    ?.toString() ?: JSONObject.NULL)
                .put(SUB_TEXT, extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: JSONObject.NULL)
                .put(TIME_MS, sbn.postTime).put(ONGOING, sbn.isOngoing).put(CLEARABLE, sbn.isClearable)
                .put(OPENABLE, sbn.notification.contentIntent != null)
        }

        // 结果字段
        private const val NOTIFICATIONS = "notifications"
        private const val COUNT = "count"
        private const val KEY = "key"
        private const val PACKAGE = "package"
        private const val TITLE = "title"
        private const val TEXT = "text"
        private const val SUB_TEXT = "sub_text"
        private const val TIME_MS = "time_ms"
        private const val ONGOING = "ongoing"
        private const val CLEARABLE = "clearable"
        private const val OPENABLE = "openable"
    }
}
