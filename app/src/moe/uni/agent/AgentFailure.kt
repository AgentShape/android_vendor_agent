package moe.uni.agent

import android.util.Log

/**
 * 一次运行因基础设施而失败（模型服务、agentd、AgentFramework、设置）。界面按 reason 显示 strings.xml 里的说明，
 * detail 是附加信息（主机名、HTTP 状态和服务端给出的说明），可以为空；不含异常原文，原始异常只写日志。
 * 工具执行中遇到时由 ToolOutput.failureOf 转成给模型的错误。
 */
class AgentFailure(val reason: Reason, val detail: String = "", cause: Throwable? = null) : Exception("$reason $detail", cause) {
    companion object {
        private const val TAG = "AgentFailure"

        /** 运行结束时的失败：已知的原样返回，其余记日志后作为 UNEXPECTED；原始异常都写进日志。 */
        fun of(e: Exception): AgentFailure {
            Log.w(TAG, "run failed", e)
            return e as? AgentFailure ?: AgentFailure(Reason.UNEXPECTED, cause = e)
        }
    }

    enum class Reason {
        /** 设置里没有填 API 地址、模型或 API key。 */
        NOT_CONFIGURED,

        /** 保存的 API key 无法解密（例如 Keystore 的密钥已丢失）。 */
        KEY_UNREADABLE,
        NETWORK,
        HOST_UNRESOLVED,
        TIMEOUT,
        TLS,
        CONNECTION_LOST,
        UNAUTHORIZED,
        NOT_FOUND,
        RATE_LIMITED,
        SERVER,
        REJECTED,
        BAD_RESPONSE,
        MODEL_FAILED,

        /** 对话超过了模型的上下文窗口，压缩后仍然放不下（ADR-0028）。 */
        CONTEXT_OVERFLOW,
        AGENTD_UNAVAILABLE,
        AGENT_FRAMEWORK_UNAVAILABLE,

        /** 找不到 AgentFramework 的 CDM 关联，无法创建虚拟屏幕。 */
        SCREEN_SETUP,

        /** 其余异常：界面只显示通用提示。 */
        UNEXPECTED,
    }
}
