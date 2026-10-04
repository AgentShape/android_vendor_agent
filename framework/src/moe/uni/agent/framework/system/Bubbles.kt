package moe.uni.agent.framework.system

import android.app.INotificationManager
import android.app.NotificationManager
import android.content.Context
import android.os.ServiceManager
import android.util.Log
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.IAgentScreen

/**
 * AgentApp 的气泡偏好（ADR-0031 第 6 条）。应用级默认是 BUBBLE_PREFERENCE_NONE，setBubblesAllowed 只允许 system 等调用，
 * 所以由 AgentFramework（system uid）替 AgentApp 设为 ALL。只成功做一次，记在自己的 SharedPreferences 里，
 * 之后不覆盖用户在系统设置里的选择。失败只记日志，下次 setup 再试。
 */
class Bubbles(private val context: Context) {
    fun allowOnce() {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_INITIALIZED, false)) return
            val uid = context.packageManager.getPackageUid(IAgentScreen.CALLER_PACKAGE, 0)
            INotificationManager.Stub.asInterface(ServiceManager.getService(Context.NOTIFICATION_SERVICE))
                .setBubblesAllowed(IAgentScreen.CALLER_PACKAGE, uid, NotificationManager.BUBBLE_PREFERENCE_ALL)
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            Log.i(FrameworkApp.TAG, "已允许 ${IAgentScreen.CALLER_PACKAGE} 的气泡")
        } catch (e: Exception) {
            Log.w(FrameworkApp.TAG, "设置气泡偏好失败", e)
        }
    }

    companion object {
        /** AgentFramework 自己的 SharedPreferences 文件名和"气泡偏好已初始化"的键（ADR-0031 第 6 条）。 */
        private const val PREFS = "bubbles"
        private const val KEY_INITIALIZED = "initialized"
    }
}
