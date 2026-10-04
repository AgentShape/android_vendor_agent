package moe.uni.agent.framework;

/**
 * AgentFramework 的系统能力入口（ADR-0029 第 6 条）：每个方法是写死逻辑的一次性操作，对调用方没有外泄的中间状态。
 * 只允许 IAgentScreen.CALLER_PACKAGE 调用。
 *
 * 结果是 JSON 对象的字符串，只有数据，都带 ok 字段；操作本身失败时返回
 * {"ok":false,"error":{"code":...,"message":...}}，code 是 ToolError 的取值（ADR-0030）。
 * 参数无效时抛 ServiceSpecificException(ERROR_INVALID_ARGUMENT)。
 */
interface IAgentDevice {
    /** 提供本接口的 bound Service；包名见 IAgentScreen.PACKAGE_NAME。 */
    const String SERVICE_CLASS = "moe.uni.agent.framework.service.DeviceService";

    const int ERROR_INVALID_ARGUMENT = 1;

    /** filter 为空时列出全部；includeSystem 为 false 时只列用户安装的应用。 */
    String listApps(String filter, boolean includeSystem);

    /** apk 是调用方打开的安装包，size 是它的字节数。同步等待安装结果。 */
    String installApp(in ParcelFileDescriptor apk, long size);

    /** 同步等待卸载结果。 */
    String uninstallApp(String packageName);

    /** namespace 为 system、secure 或 global。 */
    String getSetting(String namespace, String key);

    /** 名字包含 filter 的全部设置项；filter 为空时列出全部。 */
    String listSettings(String namespace, String filter);

    String putSetting(String namespace, String key, String value);

    /** 构建、ABI、电量、网络、存储、内存、语言和时区等。 */
    String deviceInfo();

    /** 当前的全部通知，最新的在前。key 用于打开和清除。 */
    String listNotifications();

    /** 执行通知的点击动作（contentIntent）。 */
    String openNotification(String key);

    /** 清除通知；常驻通知不能清除。 */
    String dismissNotification(String key);

    String getClipboard();

    String setClipboard(String text);
}
