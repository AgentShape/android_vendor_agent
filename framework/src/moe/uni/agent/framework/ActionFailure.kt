package moe.uni.agent.framework

/**
 * 动作本身失败：返回 ok=false 的结果，同样附上当前状态（ADR-0022 第 10 条）。
 * code 是 ToolError 的取值，message 只陈述事实（ADR-0030 第 2 条）。
 */
class ActionFailure(val code: String, message: String) : Exception(message) {
    fun toError(): ToolError = error(code, message.orEmpty())

    companion object {
        fun error(code: String, message: String) = ToolError().also {
            it.code = code
            it.message = message
        }
    }
}
