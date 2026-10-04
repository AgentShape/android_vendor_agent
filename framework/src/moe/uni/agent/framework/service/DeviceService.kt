package moe.uni.agent.framework.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import moe.uni.agent.framework.IAgentDevice
import moe.uni.agent.framework.device.Apps
import moe.uni.agent.framework.device.Clipboard
import moe.uni.agent.framework.device.DeviceInfo
import moe.uni.agent.framework.device.Notifications
import moe.uni.agent.framework.device.SystemSettings

/** IAgentDevice 的 bound Service（ADR-0029 第 6 条），保护方式与 ScreenService 相同。 */
class DeviceService : Service() {
    override fun onBind(intent: Intent?): IBinder = binder

    private val apps by lazy { Apps(this) }
    private val settings by lazy { SystemSettings(this) }
    private val info by lazy { DeviceInfo(this) }
    private val notifications by lazy { Notifications(this) }
    private val clip by lazy { Clipboard(this) }

    private val binder = object : IAgentDevice.Stub() {
        override fun listApps(filter: String, includeSystem: Boolean) =
            dispatch("listApps") { apps.list(filter, includeSystem).toString() }
        override fun installApp(apk: ParcelFileDescriptor, size: Long) = dispatch("installApp") { apps.install(apk, size).toString() }
        override fun uninstallApp(packageName: String) = dispatch("uninstallApp") { apps.uninstall(packageName).toString() }
        override fun getSetting(namespace: String, key: String) = dispatch("getSetting") { settings.get(namespace, key).toString() }
        override fun listSettings(namespace: String, filter: String) =
            dispatch("listSettings") { settings.list(namespace, filter).toString() }
        override fun putSetting(namespace: String, key: String, value: String) =
            dispatch("putSetting") { settings.put(namespace, key, value).toString() }
        override fun deviceInfo() = dispatch("deviceInfo") { info.get().toString() }
        override fun listNotifications() = dispatch("listNotifications") { notifications.list().toString() }
        override fun openNotification(key: String) = dispatch("openNotification") { notifications.open(key).toString() }
        override fun dismissNotification(key: String) = dispatch("dismissNotification") { notifications.dismiss(key).toString() }
        override fun getClipboard() = dispatch("getClipboard") { clip.get().toString() }
        override fun setClipboard(text: String) = dispatch("setClipboard") { clip.set(text).toString() }
    }
}
