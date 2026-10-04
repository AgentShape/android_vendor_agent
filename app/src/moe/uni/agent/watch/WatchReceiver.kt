package moe.uni.agent.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 观察窗通知的 deleteIntent（ADR-0031 第 2 条）：用户划掉气泡，或系统移除了通知。不导出，只有本应用的 PendingIntent 能发。
 * AgentService 还没建立时状态机就是初始的 Idle，Dismissed 本来也没有效果。
 */
class WatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScreenWatch.instance?.dispatch(ScreenWatch.Event.Dismissed)
    }
}
