package moe.uni.agent.loop

import moe.uni.agent.AgentFailure
import moe.uni.agent.conversation.Conversation
import moe.uni.agent.llm.Provider
import moe.uni.agent.tools.ToolRegistry

/**
 * 上下文压缩（ADR-0028 第 2 条）：保留最近一步，其余交给模型写成摘要。
 * 摘要请求本身超长时，在中间的切分点把范围二分，先压缩前一半，再带着得到的摘要压缩后一半。
 */
internal class Compactor(
    private val conversation: Conversation,
    private val provider: Provider,
    private val tools: ToolRegistry,
    private val instructions: () -> String,
) {
    /**
     * required 为 true 表示服务端已经报告超长，没有可压缩的内容时失败；
     * 为 false 是接近上限时的主动压缩，没有可压缩的内容就照常请求。
     */
    fun compact(required: Boolean) {
        val points = conversation.stepStarts()
        // 切在最后一个切分点；它就是上下文的第一条时没有可压缩的内容。
        val cut = points.lastOrNull()?.takeIf { it != conversation.firstSeq() } ?: if (required) throw overflow() else return
        conversation.compact(summarize(conversation.contextStart, cut, conversation.summary, points), cut)
    }

    private fun summarize(from: Long, to: Long, previous: String?, points: List<Long>): String {
        val reply = try {
            provider.respondText(conversation.summaryInput(from, to, previous), tools.schemas, instructions())
        } catch (e: AgentFailure) {
            if (e.reason != AgentFailure.Reason.CONTEXT_OVERFLOW) throw e
            val inside = points.filter { it > from && it < to }
            val mid = inside.getOrNull(inside.size / 2) ?: throw overflow()
            return summarize(mid, to, summarize(from, mid, previous, points), points)
        }
        return reply.text.takeIf { it.isNotBlank() } ?: throw AgentFailure(AgentFailure.Reason.MODEL_FAILED)
    }

    /** 单独一步就超过窗口、无法再分。 */
    private fun overflow() = AgentFailure(AgentFailure.Reason.CONTEXT_OVERFLOW)
}
