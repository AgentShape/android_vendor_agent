package moe.uni.agent.tools.device

import android.os.ParcelFileDescriptor
import android.os.ServiceSpecificException
import moe.uni.agent.framework.IAgentDevice
import moe.uni.agent.framework.ToolError
import moe.uni.agent.storage.UserStorage
import moe.uni.agent.tools.Schema
import moe.uni.agent.tools.Tool
import moe.uni.agent.tools.ToolContext
import moe.uni.agent.tools.ToolFailure
import moe.uni.agent.tools.ToolResult
import org.json.JSONObject

/**
 * 系统能力（ADR-0029 第 6 条）：由 AgentFramework 以写死的逻辑完成，每个工具对调用方是一次性的操作。
 * 结果是 AgentFramework 返回的 JSON（只有数据，错误是统一的 {code, message}，ADR-0030），原样交给模型。
 * 启动 activity 由屏幕工具 launch_app 提供。
 */
object DeviceTools {
    // 工具名
    const val APP_LIST = "app_list"
    const val APP_INSTALL = "app_install"
    const val APP_UNINSTALL = "app_uninstall"
    const val SETTINGS_GET = "settings_get"
    const val SETTINGS_LIST = "settings_list"
    const val SETTINGS_PUT = "settings_put"
    const val DEVICE_INFO = "device_info"
    const val NOTIFICATION_LIST = "notification_list"
    const val NOTIFICATION_OPEN = "notification_open"
    const val NOTIFICATION_DISMISS = "notification_dismiss"
    const val CLIPBOARD_GET = "clipboard_get"
    const val CLIPBOARD_SET = "clipboard_set"

    // 参数
    const val FILTER = "filter"
    const val INCLUDE_SYSTEM = "include_system"
    const val PATH = "path"
    const val PACKAGE = "package"
    const val NAMESPACE = "namespace"
    const val KEY = "key"
    const val VALUE = "value"
    const val TEXT = "text"

    val ALL: List<Tool> = listOf(
        AppList, AppInstall, AppUninstall, SettingsGet, SettingsList, SettingsPut, DeviceInfo,
        NotificationList, NotificationOpen, NotificationDismiss, ClipboardGet, ClipboardSet,
    )
}

/** 系统能力工具的公共部分：调用 AgentFramework，JSON 结果原样返回。 */
private abstract class DeviceTool(override val name: String, override val description: String) : Tool {
    override val kind get() = Tool.Kind.DEVICE

    override fun run(context: ToolContext, args: JSONObject): ToolResult {
        val output = try {
            context.device().call(args)
        } catch (e: ServiceSpecificException) {
            // IAgentDevice 只用 ERROR_INVALID_ARGUMENT，message 由 AgentFramework 写成事实陈述。
            throw ToolFailure(ToolError.INVALID_ARGUMENT, e.message.orEmpty())
        }
        return ToolResult.json(JSONObject(output))
    }

    protected abstract fun IAgentDevice.call(args: JSONObject): String

    protected fun Schema.namespace() = string(DeviceTools.NAMESPACE, "system、secure 或 global", required = true)
}

private object AppList : DeviceTool(DeviceTools.APP_LIST, "列出已安装的应用：包名、名称、版本、是否系统应用、是否可启动。") {
    override fun parameters(schema: Schema) = with(schema) {
        string(DeviceTools.FILTER, "按包名或名称过滤（包含即可，不区分大小写），为空时列出全部")
        boolean(DeviceTools.INCLUDE_SYSTEM, "是否包含系统应用，默认只列用户安装的")
    }
    override fun summary(args: JSONObject) = args.optString(DeviceTools.FILTER)
    override fun IAgentDevice.call(args: JSONObject) =
        listApps(args.optString(DeviceTools.FILTER), args.optBoolean(DeviceTools.INCLUDE_SYSTEM))
}

private object AppInstall : DeviceTool(DeviceTools.APP_INSTALL, "安装用户存储中的 APK，等待安装完成后返回结果；已安装的应用会被更新。") {
    override fun parameters(schema: Schema) = with(schema) { string(DeviceTools.PATH, "APK 的绝对路径（/sdcard/...）", required = true) }
    override fun summary(args: JSONObject) = args.optString(DeviceTools.PATH)
    override fun IAgentDevice.call(args: JSONObject): String {
        val file = UserStorage.resolve(args.getString(DeviceTools.PATH))
        if (!file.exists()) throw ToolFailure(ToolError.NOT_FOUND, "文件不存在：${file.path}")
        if (!file.isFile) throw ToolFailure(ToolError.INVALID_ARGUMENT, "不是普通文件：${file.path}")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { installApp(it, file.length()) }
    }
}

private object AppUninstall : DeviceTool(DeviceTools.APP_UNINSTALL, "卸载应用，等待卸载完成后返回结果。") {
    override fun parameters(schema: Schema) = with(schema) { string(DeviceTools.PACKAGE, "包名", required = true) }
    override fun summary(args: JSONObject) = args.optString(DeviceTools.PACKAGE)
    override fun IAgentDevice.call(args: JSONObject) = uninstallApp(args.getString(DeviceTools.PACKAGE))
}

private object SettingsGet : DeviceTool(DeviceTools.SETTINGS_GET, "读取一项系统设置；不存在时 value 为 null。") {
    override fun parameters(schema: Schema) = with(schema) {
        namespace()
        string(DeviceTools.KEY, "设置项的名字", required = true)
    }
    override fun summary(args: JSONObject) = "${args.optString(DeviceTools.NAMESPACE)} ${args.optString(DeviceTools.KEY)}"
    override fun IAgentDevice.call(args: JSONObject) = getSetting(args.getString(DeviceTools.NAMESPACE), args.getString(DeviceTools.KEY))
}

private object SettingsList : DeviceTool(DeviceTools.SETTINGS_LIST, "列出名字包含 filter 的系统设置项和值，用来查找设置项的名字。") {
    override fun parameters(schema: Schema) = with(schema) {
        namespace()
        string(DeviceTools.FILTER, "名字包含的文字（不区分大小写），为空时列出全部")
    }
    override fun summary(args: JSONObject) = "${args.optString(DeviceTools.NAMESPACE)} ${args.optString(DeviceTools.FILTER)}"
    override fun IAgentDevice.call(args: JSONObject) = listSettings(args.getString(DeviceTools.NAMESPACE), args.optString(DeviceTools.FILTER))
}

private object SettingsPut : DeviceTool(DeviceTools.SETTINGS_PUT, "写入一项系统设置，返回写入后的值。") {
    override fun parameters(schema: Schema) = with(schema) {
        namespace()
        string(DeviceTools.KEY, "设置项的名字", required = true)
        string(DeviceTools.VALUE, "新值", required = true)
    }
    override fun summary(args: JSONObject) =
        "${args.optString(DeviceTools.NAMESPACE)} ${args.optString(DeviceTools.KEY)}=${args.optString(DeviceTools.VALUE)}"
    override fun IAgentDevice.call(args: JSONObject) =
        putSetting(args.getString(DeviceTools.NAMESPACE), args.getString(DeviceTools.KEY), args.getString(DeviceTools.VALUE))
}

private object DeviceInfo : DeviceTool(DeviceTools.DEVICE_INFO, "设备信息：型号、Android 版本、ABI、电量、网络、存储、内存、语言和时区。屏幕信息见 screen_list。") {
    override fun IAgentDevice.call(args: JSONObject): String = deviceInfo()
}

private object NotificationList : DeviceTool(DeviceTools.NOTIFICATION_LIST, "列出当前的全部通知（最新的在前）：key、应用、标题、正文、是否可打开、是否可清除。") {
    override fun IAgentDevice.call(args: JSONObject): String = listNotifications()
}

private object NotificationOpen : DeviceTool(DeviceTools.NOTIFICATION_OPEN, "打开通知（执行它的点击动作），界面出现在主屏幕上。") {
    override fun parameters(schema: Schema) = with(schema) { string(DeviceTools.KEY, "通知的 key（见 ${DeviceTools.NOTIFICATION_LIST}）", required = true) }
    override fun summary(args: JSONObject) = args.optString(DeviceTools.KEY)
    override fun IAgentDevice.call(args: JSONObject) = openNotification(args.getString(DeviceTools.KEY))
}

private object NotificationDismiss : DeviceTool(DeviceTools.NOTIFICATION_DISMISS, "清除通知；常驻通知不能清除。") {
    override fun parameters(schema: Schema) = with(schema) { string(DeviceTools.KEY, "通知的 key（见 ${DeviceTools.NOTIFICATION_LIST}）", required = true) }
    override fun summary(args: JSONObject) = args.optString(DeviceTools.KEY)
    override fun IAgentDevice.call(args: JSONObject) = dismissNotification(args.getString(DeviceTools.KEY))
}

private object ClipboardGet : DeviceTool(DeviceTools.CLIPBOARD_GET, "读取剪贴板的文字；为空时 text 为 null。") {
    override fun IAgentDevice.call(args: JSONObject): String = clipboard
}

private object ClipboardSet : DeviceTool(DeviceTools.CLIPBOARD_SET, "把文字写进剪贴板。") {
    override fun parameters(schema: Schema) = with(schema) { string(DeviceTools.TEXT, "文字", required = true) }
    override fun summary(args: JSONObject) = args.optString(DeviceTools.TEXT)
    override fun IAgentDevice.call(args: JSONObject) = setClipboard(args.getString(DeviceTools.TEXT))
}
