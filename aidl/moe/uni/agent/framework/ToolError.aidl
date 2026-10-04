package moe.uni.agent.framework;

/**
 * 工具错误（ADR-0030 第 2 条）：code 是下面的固定取值之一，message 只陈述事实，不带补救建议和异常原文。
 * 错误代码只在这里定义，AgentFramework 和 AgentApp 共用；IAgentDevice 的 JSON 结果用同样的 code。
 */
parcelable ToolError {
    const String INVALID_ARGUMENT = "invalid_argument";
    const String NOT_FOUND = "not_found";
    const String PERMISSION_DENIED = "permission_denied";
    const String UNAVAILABLE = "unavailable";
    const String CONFLICT = "conflict";
    const String UNSUPPORTED = "unsupported";
    const String FAILED = "failed";
    const String TIMEOUT = "timeout";
    const String CANCELLED = "cancelled";
    const String INTERRUPTED = "interrupted";
    const String TRUNCATED = "truncated";

    String code;
    String message;
}
