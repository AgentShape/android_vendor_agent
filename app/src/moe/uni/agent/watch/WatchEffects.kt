package moe.uni.agent.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import moe.uni.agent.R
import moe.uni.agent.conversation.Conversations
import moe.uni.agent.watch.ScreenWatch.Effect
import moe.uni.agent.watch.ScreenWatch.Event.Unavailable.Reason

/** 执行状态机给出的效果（ADR-0031 第 4 条）。返回 null 表示成功，否则是失败原因，由 ScreenWatch 作为 Unavailable 回报。 */
interface WatchEffects {
    fun execute(effect: Effect): Reason?

    companion object {
        /** 全局只有一个观察窗（ADR-0031 第 1 条）：一条固定 id 的通知、一个固定 id 的 conversation shortcut。 */
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "screen_watch"
        const val SHORTCUT_ID = "screen_watch"

        /** PendingIntent 的请求码：气泡展开的镜像 Activity、通知的 deleteIntent（ADR-0031 第 2 条）。 */
        const val REQUEST_BUBBLE = 1
        const val REQUEST_DISMISS = 2
    }
}

/**
 * 用通知气泡实现观察窗（ADR-0031 第 2 条）：MessagingStyle 通知加 long-lived conversation shortcut 才能出气泡；
 * 气泡只出气泡不占通知栏，不自动展开，展开的是 MirrorActivity；用户划掉气泡时 deleteIntent 发给 WatchReceiver。
 */
class NotificationEffects(
    context: Context,
    /** 打开对话的 intent（MainActivity）；shortcut 用它。 */
    private val open: Intent,
    /** 气泡展开的镜像 Activity 的 intent，参数是会话 id。由 AgentService 注入，watch 包不依赖 ui。 */
    private val viewer: (session: String) -> Intent,
) : WatchEffects {
    private val context = context.applicationContext
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val shortcuts = context.getSystemService(ShortcutManager::class.java)

    /** 应用图标（ADR-0031 第 2 条的实现方默认）；应用没有自己的图标时就是系统的默认图标，与启动器一致。 */
    private val icon = Icon.createWithResource(context, context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.sym_def_app_icon)

    init {
        // 单独的渠道；气泡本身就是提示，不响铃不振动。
        notifications.createNotificationChannel(
            NotificationChannel(WatchEffects.CHANNEL_ID, context.getString(R.string.watch_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.watch_channel_description)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    override fun execute(effect: Effect): Reason? = when (effect) {
        is Effect.Post -> post(effect.session)
        is Effect.Update -> notify(effect.session)
        Effect.Cancel -> {
            notifications.cancel(WatchEffects.NOTIFICATION_ID)
            null
        }
    }

    private fun post(session: String): Reason? {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Reason.NO_PERMISSION
        }
        // SELECTED（只允许选定的对话）时本渠道没有被单独允许，通知会作为普通通知进通知栏而不是气泡，同样视为关闭。
        if (!notifications.areBubblesEnabled() || notifications.bubblePreference != NotificationManager.BUBBLE_PREFERENCE_ALL) {
            return Reason.BUBBLES_OFF
        }
        // 用户在气泡菜单选过"不要以气泡显示此对话"：系统给这个对话建了子渠道并关掉气泡，之后只会落到通知栏。
        if (notifications.getNotificationChannel(WatchEffects.CHANNEL_ID, WatchEffects.SHORTCUT_ID)?.allowBubbles == NotificationChannel.ALLOW_BUBBLE_OFF) {
            return Reason.BUBBLES_OFF
        }
        try {
            shortcuts.pushDynamicShortcut(shortcut())
        } catch (e: Exception) {
            Log.w(TAG, "pushDynamicShortcut failed", e)
            return Reason.SHORTCUT_FAILED
        }
        return notify(session)
    }

    /** 发出或更新通知；更新时只有标题和跳转目标（会话）不同。 */
    private fun notify(session: String): Reason? = try {
        notifications.notify(WatchEffects.NOTIFICATION_ID, notification(session))
        null
    } catch (e: Exception) {
        Log.w(TAG, "notify failed", e)
        Reason.POST_FAILED
    }

    /**
     * 固定 id 的 conversation shortcut：long-lived，不设 person，打开 MainActivity。
     * 不能排除在启动器之外：本树 ShortcutPackage 直接丢弃 SURFACE_LAUNCHER 排除的 shortcut（ADR-0031 第 2 条修订）。
     * 不设图标：气泡用 BubbleMetadata 的图标，取不到时 SystemUI 用应用图标。
     */
    private fun shortcut(): ShortcutInfo = ShortcutInfo.Builder(context, WatchEffects.SHORTCUT_ID)
        .setShortLabel(context.getString(R.string.watch_shortcut))
        .setIntent(open)
        .setLongLived(true)
        .build()

    private fun notification(session: String): Notification {
        val title = Conversations.title(session)?.ifEmpty { null } ?: context.getString(R.string.app_name)
        // 发送者是 agent，不是 bot（全是 bot 的不算 conversation）。
        val agent = Person.Builder().setName(context.getString(R.string.app_name)).setBot(false).build()
        val user = Person.Builder().setName(context.getString(R.string.watch_user)).build()
        val style = Notification.MessagingStyle(user)
            .setConversationTitle(title)
            .addMessage(context.getString(R.string.watch_message), System.currentTimeMillis(), agent)
        // 气泡的 PendingIntent 要可变：SystemUI 用 fillInIntent 加上 NEW_DOCUMENT、MULTIPLE_TASK。
        val expanded = PendingIntent.getActivity(
            context, WatchEffects.REQUEST_BUBBLE, viewer(session),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismissed = PendingIntent.getBroadcast(
            context, WatchEffects.REQUEST_DISMISS, Intent(context, WatchReceiver::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        // 用户划掉气泡时 SystemUI 只把它移进溢出区、保留通知，发的是 BubbleMetadata 的 deleteIntent（BubbleData.maybeSendDeleteIntent）；
        // 通知本身被移除（"不要以气泡显示此对话"、镜像 Activity 结束）时发的才是通知的 deleteIntent。两处都指向 WatchReceiver。
        val bubble = Notification.BubbleMetadata.Builder(expanded, icon)
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            .setSuppressNotification(true)
            .setAutoExpandBubble(false)
            .setDeleteIntent(dismissed)
            .build()
        return Notification.Builder(context, WatchEffects.CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setStyle(style)
            .setShortcutId(WatchEffects.SHORTCUT_ID)
            .setBubbleMetadata(bubble)
            .setDeleteIntent(dismissed)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val TAG = "WatchEffects"

        /**
         * 气泡展开区域的期望高度（dp）。不设时 SystemUI 只给最小高度 180dp（bubble_expanded_default_height）；
         * 超过可用高度就铺满（BubblePositioner.getExpandedViewHeight 返回 MAX_HEIGHT），镜像要尽可能大（ADR-0031 第 2 条）。
         */
        const val BUBBLE_HEIGHT_DP = Int.MAX_VALUE
    }
}
