package moe.uni.agent.loop

import java.util.concurrent.CancellationException
import moe.uni.agent.AgentFailure
import moe.uni.agent.conversation.Conversation
import moe.uni.agent.framework.ToolError
import moe.uni.agent.llm.Provider
import moe.uni.agent.llm.Reply
import moe.uni.agent.llm.Responses
import moe.uni.agent.tools.ToolContext
import moe.uni.agent.tools.ToolRegistry

/**
 * 一次 agent loop（ADR-0003）：请求模型，执行它调用的工具，把结果写回会话，直到模型不再调用工具。
 * 不限步数，要中止就调用 stop()。上下文接近窗口上限时先压缩（ADR-0028）。
 * 只在一个线程上运行；stop() 可以从任何线程调用。
 */
class AgentLoop(
    private val conversation: Conversation,
    private val provider: Provider,
    private val tools: ToolRegistry,
    private val context: ToolContext,
    private val instructions: () -> String,
    /** 模型的上下文窗口（token）。 */
    contextWindow: Long,
) {
    @Volatile private var stopped = false
    private val compactor = Compactor(conversation, provider, tools, instructions)
    private val compactAt = (contextWindow * COMPACT_RATIO).toLong()

    /**
     * 会话已经由 begin 准备好。onText 逐段收到流式回复中途的文字，回复写进会话后调用 onReply，界面据此清掉流式文字。
     */
    fun run(onText: (String) -> Unit, onReply: () -> Unit) {
        try {
            while (true) {
                checkStopped()
                if (conversation.contextTokens > compactAt) compactor.compact(required = false)
                val reply = request(onText)
                conversation.appendReply(reply.output)
                conversation.recordTokens(reply.tokens)
                onReply()
                if (reply.truncated || reply.stopped) {
                    // 截断的参数可能恰好也能解析，但内容不完整，一律不执行（ADR-0014）。
                    conversation.completePending(ToolError.TRUNCATED, TRUNCATED)
                    // 因为输出上限被截断时让模型重新发出；其他原因（例如内容过滤）重发也没有用（ADR-0028 第 4 条）。
                    if (reply.truncated) continue else return
                }
                val calls = conversation.pending()
                if (calls.isEmpty()) return
                for (call in calls) {
                    checkStopped()
                    conversation.toolResult(call.getString(Responses.CALL_ID), tools.run(call, context))
                }
            }
        } catch (_: CancellationException) {
            conversation.completePending(ToolError.CANCELLED, STOPPED)
        } catch (e: Exception) {
            conversation.completePending(ToolError.FAILED, FAILED)
            conversation.setFailure(AgentFailure.of(e))
        }
    }

    fun stop() {
        stopped = true
        provider.cancel()
        context.cancel()
    }

    /** 服务端报告上下文超长时先压缩，再重发一次（ADR-0028 第 3 条）。 */
    private fun request(onText: (String) -> Unit): Reply = try {
        provider.respond(conversation.input(), tools.schemas, instructions(), onText)
    } catch (e: AgentFailure) {
        if (e.reason != AgentFailure.Reason.CONTEXT_OVERFLOW) throw e
        compactor.compact(required = true)
        provider.respond(conversation.input(), tools.schemas, instructions(), onText)
    }

    private fun checkStopped() {
        if (stopped) throw CancellationException()
    }

    companion object {
        /** 上下文超过窗口的这个比例时压缩（实现方默认，ADR-0028 第 1 条）。 */
        private const val COMPACT_RATIO = 0.8

        // 补给没有结果的工具调用的错误说明，发给模型；怎么处理写在提示词里（ADR-0030 第 6 条）。
        private const val INTERRUPTED = "执行被中断，结果未知"
        private const val TRUNCATED = "回复被截断，工具调用的参数可能不完整，没有执行"
        private const val STOPPED = "用户停止了运行，这次调用可能已经执行"
        private const val FAILED = "执行出错，结果未知"

        /**
         * 一次运行开始：上次运行被打断（例如进程崩溃）时留下的工具调用补上结果，追加用户消息，清掉上次的失败。
         * 在准备连接之前调用，这样准备失败时用户的消息也已经保存，可以重试。
         */
        fun begin(conversation: Conversation, prompt: String?) {
            conversation.completePending(ToolError.INTERRUPTED, INTERRUPTED)
            prompt?.let(conversation::appendUser)
            conversation.setFailure(null)
        }
    }
}
