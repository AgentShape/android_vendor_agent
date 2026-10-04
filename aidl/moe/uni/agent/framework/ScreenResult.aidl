package moe.uni.agent.framework;

import moe.uni.agent.framework.ToolError;

/**
 * 屏幕工具的结果（ADR-0022 第 10 条、ADR-0030 第 3 条）：只有数据，给模型的结果由 AgentApp 的工具层组织。
 * JSON 字段的名字见 AgentFramework 的 a11y/NodeFields.kt。
 */
parcelable ScreenResult {
    /** 动作是否成功；失败时 error 不为 null，screen 和 tree 同样是当前状态。 */
    boolean ok;
    @nullable ToolError error;
    /** 动作做了什么（JSON 对象）；失败或不是动作时为 null。 */
    @nullable String action;
    /** 只读结果（JSON 对象），如 list() 的屏幕、task 和 app。 */
    @nullable String data;
    /** 屏幕信息（JSON 对象）；observe 不含 OBSERVE_TREE 时为 null。 */
    @nullable String screen;
    /** 节点树文本（ADR-0022 第 9 条）；observe 不含 OBSERVE_TREE 时为 null。 */
    @nullable String tree;
    /** 返回状态前界面是否已稳定；false 表示等到上限时界面仍在变化，是中间状态（ADR-0034 第 2 条）。不是屏幕状态时为 false。 */
    boolean settled;
    /** 这次调用期间系统回调产生的事件（如安全窗口被系统保护），每项是一个 JSON 对象。 */
    String[] events;
    /** 要截图但没有拿到时的原因。 */
    @nullable ToolError screenshotError;
    /** PNG 截图（memfd），不放进 Parcel 正文；没有截图时为 null。 */
    @nullable ParcelFileDescriptor image;
}
