package moe.uni.agent.framework

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import android.util.Log
import moe.uni.agent.framework.a11y.ScreenAccessibility
import moe.uni.agent.framework.control.ScreenControl

/**
 * AgentFramework 常驻进程（ADR-0022 第 2 条）：启动时启用自己的无障碍服务，并尝试创建虚拟屏幕；
 * 没有关联时由 AgentFramework 自己请 agentd 创建（ADR-0029 第 7 条）。
 */
class FrameworkApp : Application() {
    override fun onCreate() {
        super.onCreate()
        screen = ScreenControl(this)
        enableAccessibility()
        screen.handler.post {
            try {
                screen.setup()
            } catch (e: Exception) {
                Log.i(TAG, "虚拟屏幕暂未创建：${e.message}")
            }
        }
    }

    /** 把自己的无障碍服务加进已启用列表；本包里类名不同的旧条目（重构前的路径）一并去掉。 */
    private fun enableAccessibility() {
        val own = ComponentName(this, ScreenAccessibility::class.java)
        val resolver = contentResolver
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val others = enabled.split(SEPARATOR).filter { it.isNotEmpty() && ComponentName.unflattenFromString(it)?.packageName != packageName }
        val value = (others + own.flattenToString()).joinToString(SEPARATOR)
        if (value != enabled) Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
        Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    companion object {
        const val TAG = "AgentFramework"

        /** ENABLED_ACCESSIBILITY_SERVICES 中各服务的分隔符。 */
        private const val SEPARATOR = ":"

        lateinit var screen: ScreenControl
            private set
    }
}
