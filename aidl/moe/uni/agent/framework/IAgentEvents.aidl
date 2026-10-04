package moe.uni.agent.framework;

/**
 * AgentFramework 推给 AgentApp 的事件（ADR-0034 第 4 条）：节点树的变化、语义事件和通知，供 exec_js 的等待使用。
 * event 是一个 JSON 对象，字段见 AgentFramework 的 control/ResultFields.kt（WaitEventFields）。oneway，按发送顺序到达。
 */
oneway interface IAgentEvents {
    void onEvent(String event);
}
