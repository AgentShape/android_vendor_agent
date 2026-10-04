package moe.uni.agent.framework;

import moe.uni.agent.framework.IAgentEvents;
import moe.uni.agent.framework.ScreenMirror;
import moe.uni.agent.framework.ScreenResult;

/**
 * AgentFramework 的屏幕控制入口（ADR-0022）。只允许 moe.uni.agent 调用。
 *
 * display 为 -1 时表示 agent 的虚拟屏幕，0 为主屏幕。element 是最近一次返回状态（或 query）中的节点编号，
 * 小于等于 0 表示不指定。动作执行后等 UI 稳定，按 observe 返回新的状态和截图。
 * 无法执行时通过 ServiceSpecificException 返回下面的错误码（message 只陈述事实）；
 * 动作本身失败时返回 ok=false 的结果，error.code 是 ToolError 的取值（ADR-0030）。
 */
interface IAgentScreen {
    /** AgentFramework 的包名和提供本接口的 bound Service（ADR-0022 第 2 条）。 */
    const String PACKAGE_NAME = "moe.uni.agent.framework";
    const String SERVICE_CLASS = "moe.uni.agent.framework.service.ScreenService";

    /** 唯一允许的调用方（AgentApp）。 */
    const String CALLER_PACKAGE = "moe.uni.agent";

    /** display 参数中指代 agent 的虚拟屏幕的值（ADR-0022 实施方默认；两端共用，ADR-0025 第 2 条）。 */
    const int DISPLAY_VIRTUAL = -1;

    /** 没有属于 moe.uni.agent.framework 的 APP_STREAMING 关联，需要先创建（ADR-0022 第 3 条）。 */
    const int ERROR_NO_ASSOCIATION = 1;
    const int ERROR_INVALID_ARGUMENT = 2;
    /** 屏幕不可用：主屏幕灭屏或锁屏、虚拟屏幕未创建等。 */
    const int ERROR_UNAVAILABLE = 3;

    /**
     * 动作方法的 observe 位：返回屏幕信息和节点树、截图。0 表示只返回动作结果（屏幕脚本的中间动作，ADR-0024）：
     * 这时不等 UI 稳定，推迟到这块屏幕的下一次调用之前（ADR-0034 第 3 条）。
     */
    const int OBSERVE_TREE = 1;
    const int OBSERVE_SCREENSHOT = 2;

    /** 确保虚拟设备和虚拟屏幕已经创建；已创建时直接返回。 */
    void setup();

    /** 所有屏幕、每块屏幕上的 task、可启动的 app，放在 data 里。没有截图。 */
    ScreenResult list();

    ScreenResult state(int display);

    /**
     * 构建新的节点树（同时成为该屏幕最近一次状态），返回屏幕信息的字段加上匹配 selector 的节点（nodes），JSON 对象（ADR-0024）。
     * selector 为 JSON 对象，空对象匹配全部节点。settle 为 false 时不补推迟的稳定等待（exec_js 等待中的查询，ADR-0034 第 3 条）。
     */
    String query(int display, String selector, boolean settle);

    ScreenResult tap(int display, int element, int x, int y, int count, boolean longPress, int observe);

    /** action 是 AccessibilityAction 的名字（如 ACTION_EXPAND）或自定义 action 的标签。 */
    ScreenResult nodeAction(int display, int element, String action, int observe);

    ScreenResult setText(int display, int element, String text, int observe);

    /** selection 为 text、cursor_before 或 cursor_after。 */
    ScreenResult selectText(int display, int element, String text, String prefix, String suffix, String selection, int observe);

    /** direction 为 up、down、left、right；pages 可以是小数。element 不指定时滑动整个屏幕。 */
    ScreenResult scroll(int display, int element, String direction, float pages, int observe);

    ScreenResult swipe(int display, int fromX, int fromY, int toX, int toY, int durationMs, int observe);

    /** key 是去掉 KEYCODE_ 前缀的名字，组合键用 + 连接，如 CTRL+A。 */
    ScreenResult pressKey(int display, String key, int observe);

    /** 在焦点输入框的光标处插入（ADR-0022 第 11 条）。 */
    ScreenResult typeText(int display, String text, int observe);

    /** intentUri 非空时按它启动，否则启动 packageName 的入口 activity。 */
    ScreenResult launchApp(int display, String packageName, String intentUri, int observe);

    ScreenResult moveTask(int taskId, int display, int observe);

    /**
     * 创建 display 的只读镜像（ADR-0031 第 3 条）。token 是调用方的 Binder，调用方死亡时自动释放；
     * 同一个 token 重复创建先释放旧的。第一版只接受虚拟屏幕，其他返回 ERROR_INVALID_ARGUMENT；
     * 虚拟屏幕未创建时返回 ERROR_UNAVAILABLE。
     */
    ScreenMirror createMirror(int display, IBinder token);

    /** 释放 token 对应的镜像；没有时直接返回。 */
    void releaseMirror(IBinder token);

    /**
     * 在 display 顶部显示状态胶囊（ADR-0033 第 5 条）：加载指示器和一行文字，再次调用更新文字。
     * 画在无障碍 overlay 层，不挡触摸，不在节点树里，这块屏幕的截图会排除它。失败只记日志。
     */
    void showStatus(int display, String text);

    /** 隐藏 display 的状态胶囊；没有时直接返回。 */
    void hideStatus(int display);

    /**
     * 注册等待用的事件监听（ADR-0034 第 4 条）。调用方死亡时自动注销；同一个 listener 重复注册只算一次。
     * 有监听时才产生事件。
     */
    void addEventListener(IAgentEvents listener);

    /** 注销监听；没有注册过时直接返回。 */
    void removeEventListener(IAgentEvents listener);
}
