package moe.uni.agent.llm

import org.json.JSONArray

/**
 * 一次回复。truncated 表示因为输出上限被截断（incomplete，原因 max_output_tokens），其中的工具调用参数可能不完整；
 * stopped 表示因为其他原因没有完成（例如内容过滤），不应重发。tokens 是 input_tokens 加 output_tokens，端点没有返回时为 0。
 */
class Reply(val output: JSONArray, val truncated: Boolean, val stopped: Boolean, val tokens: Long) {
    /** 回复里的文字。 */
    val text: String
        get() = (0 until output.length()).map { output.getJSONObject(it) }
            .filter { it.optString(Responses.TYPE) == Responses.TYPE_MESSAGE }
            .flatMap { m -> m.optJSONArray(Responses.CONTENT)?.let { c -> (0 until c.length()).map { c.getJSONObject(it) } }.orEmpty() }
            .filter { it.optString(Responses.TYPE) == Responses.TYPE_OUTPUT_TEXT }
            .joinToString("") { it.optString(Responses.TEXT) }
}

/** 模型接入（ADR-0003）。 */
interface Provider {
    /** onText 收到流式回复中途的文字，只给界面显示；写进历史的以最终的 Reply 为准。 */
    fun respond(input: JSONArray, tools: JSONArray, instructions: String, onText: (String) -> Unit): Reply

    /** 不允许调用工具的请求（tool_choice: none），给上下文压缩用；工具列表照常发送，保持请求前缀一致（ADR-0028）。 */
    fun respondText(input: JSONArray, tools: JSONArray, instructions: String): Reply

    /** 可以从任何线程调用：断开正在进行的请求。 */
    fun cancel()
}
