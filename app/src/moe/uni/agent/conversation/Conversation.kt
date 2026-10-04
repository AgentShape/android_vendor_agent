package moe.uni.agent.conversation

import moe.uni.agent.AgentFailure
import moe.uni.agent.conversation.db.ConversationDao
import moe.uni.agent.conversation.db.ConversationEntity
import moe.uni.agent.conversation.db.ItemEntity
import moe.uni.agent.conversation.db.ItemType
import moe.uni.agent.llm.Responses
import moe.uni.agent.prompt.Prompts
import moe.uni.agent.tools.ToolOutput
import moe.uni.agent.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 运行中的一个会话（ADR-0027 第 3 条）：由 agent loop 的线程独占。开始时从数据库读出全部记录，
 * 之后在内存里追加并同时写入数据库；每次请求直接用内存中的记录组装，不再读库。界面订阅数据库，不读这个对象。
 */
class Conversation internal constructor(private val dao: ConversationDao, row: ConversationEntity, rows: List<ItemEntity>) {
    /** 会话标识，也是 agentd session、exec_js 运行时和截图的归属。 */
    val id: String = row.id

    private class Entry(val row: ItemEntity, val json: JSONObject)

    private val entries = rows.map { Entry(it, JSONObject(it.body)) }.toMutableList()

    /** 上下文的起点（item.seq）与之前内容的摘要（ADR-0028）。 */
    var contextStart: Long = row.contextStart
        private set
    var summary: String? = row.summary
        private set

    /** 上一次请求的 input_tokens 加 output_tokens，用来判断是否需要压缩。 */
    var contextTokens: Long = row.contextTokens
        private set

    fun appendUser(text: String) = append(listOf(message(Responses.ROLE_USER, text)))

    /** 一次模型回复的全部记录。 */
    fun appendReply(output: JSONArray) = append((0 until output.length()).map { output.getJSONObject(it) })

    /** 截图不入库（ADR-0022 第 12 条）：只记张数，编码好的 data URL 只放在内存里。 */
    fun toolResult(callId: String, result: ToolResult) {
        val json = output(callId, result.output)
        val row = ItemEntity(conversationId = id, type = ItemType.FUNCTION_OUTPUT, callId = callId, body = json.toString(),
            ok = result.ok, screenshots = result.images.size)
        if (result.images.isNotEmpty()) ScreenshotStore.put(id, callId, result.images)
        insert(listOf(row), listOf(json))
    }

    /** 还没有结果的工具调用。 */
    fun pending(): List<JSONObject> {
        val done = entries.filter { it.row.type == ItemType.FUNCTION_OUTPUT }.map { it.row.callId }.toSet()
        return entries.filter { it.row.type == ItemType.FUNCTION_CALL && it.row.callId !in done }.map { it.json }
    }

    /** 给所有还没有结果的工具调用补上错误结果（ADR-0030 第 2 条），否则下一次请求会被协议拒绝。 */
    fun completePending(code: String, message: String) {
        for (call in pending()) toolResult(call.getString(Responses.CALL_ID), ToolResult.json(ToolOutput.failure(code, message)))
    }

    fun setFailure(value: AgentFailure?) = dao.setFailure(id, value?.reason, value?.detail)

    /** 最后一项是助手的回复（而不是用户消息或工具结果），这时继续请求没有意义。 */
    fun endsWithReply(): Boolean = entries.lastOrNull()?.row?.type == ItemType.ASSISTANT

    /** 最后一条用户消息，给"重试"用。 */
    fun lastUserText(): String? = entries.lastOrNull { it.row.type == ItemType.USER }?.let { text(it.json) }

    fun recordTokens(tokens: Long) {
        contextTokens = tokens
        dao.setContextTokens(id, tokens)
    }

    /** 本次请求要发送的上下文：摘要（如果有）加上起点之后的记录。 */
    fun input(): JSONArray = view(contextStart, Long.MAX_VALUE, summary, retain = true)

    /** 当前上下文里可以作为压缩切分点的记录（一条用户消息或一次模型回复的开头），按顺序。 */
    fun stepStarts(): List<Long> = entries.filter { it.row.seq >= contextStart && it.row.stepStart }.map { it.row.seq }

    /** 当前上下文的第一条记录；切在这里等于什么都没压缩。 */
    fun firstSeq(): Long? = entries.firstOrNull { it.row.seq >= contextStart }?.row?.seq

    /** 摘要请求的输入：previous 加上 [from, to) 的记录，末尾是要求写摘要的用户消息。不改变截图的保留状态。 */
    fun summaryInput(from: Long, to: Long, previous: String?): JSONArray =
        view(from, to, previous, retain = false).put(message(Responses.ROLE_USER, Prompts.SUMMARY_REQUEST))

    /** 压缩完成：start 之前的记录由 summary 代替；本地记录不删除。 */
    fun compact(summary: String, start: Long) {
        this.summary = summary
        contextStart = start
        contextTokens = 0
        dao.compact(id, summary, start)
    }

    private fun append(items: List<JSONObject>) {
        val rows = items.mapIndexed { i, item ->
            ItemEntity(conversationId = id, type = typeOf(item), callId = item.optString(Responses.CALL_ID).ifEmpty { null },
                body = item.toString(), stepStart = i == 0)
        }
        insert(rows, items)
    }

    private fun insert(rows: List<ItemEntity>, items: List<JSONObject>) {
        val seqs = dao.append(id, rows, System.currentTimeMillis())
        rows.forEachIndexed { i, row -> entries.add(Entry(row.copy(seq = seqs[i]), items[i])) }
    }

    /**
     * [from, to) 的记录。带过截图的工具结果改成 input_text + input_image 数组，逐张按保留策略计数，
     * 被替换的换成占位文字。retain 为 false 时只读取，不改变保留状态（摘要请求用）。
     */
    private fun view(from: Long, to: Long, summary: String?, retain: Boolean): JSONArray {
        val list = entries.filter { it.row.seq in from until to }
        val result = JSONArray()
        summary?.let { result.put(message(Responses.ROLE_USER, Prompts.summaryMessage(it))) }
        val keys = list.flatMap { e -> (0 until e.row.screenshots).map { ScreenshotStore.key(e.row.callId!!, it) } }
        val images = if (retain) ScreenshotStore.retain(id, keys) else ScreenshotStore.peek(id, keys)
        for (e in list) {
            if (e.row.screenshots == 0) {
                result.put(e.json)
                continue
            }
            val callId = e.row.callId!!
            val content = JSONArray().put(inputText(e.json.optString(Responses.OUTPUT)))
            for (i in 0 until e.row.screenshots) {
                content.put(images[ScreenshotStore.key(callId, i)]?.let(::inputImage) ?: inputText(SCREENSHOT_OMITTED))
            }
            result.put(output(callId, "").put(Responses.OUTPUT, content))
        }
        return result
    }

    companion object {
        private const val TITLE_MAX = 30
        private const val SCREENSHOT_OMITTED = "[screenshot omitted]"

        /** 标题取第一条用户消息的第一行，截到 TITLE_MAX 个字符（ADR-0026 第 1 条）。 */
        internal fun titleOf(text: String): String {
            val line = text.trim().lineSequence().firstOrNull().orEmpty().trim()
            return if (line.length > TITLE_MAX) line.take(TITLE_MAX) + "…" else line
        }

        internal fun typeOf(item: JSONObject): ItemType = when (item.optString(Responses.TYPE)) {
            Responses.TYPE_FUNCTION_CALL -> ItemType.FUNCTION_CALL
            Responses.TYPE_FUNCTION_CALL_OUTPUT -> ItemType.FUNCTION_OUTPUT
            Responses.TYPE_REASONING -> ItemType.REASONING
            else -> when (item.optString(Responses.ROLE)) {
                Responses.ROLE_USER -> ItemType.USER
                Responses.ROLE_ASSISTANT -> ItemType.ASSISTANT
                else -> ItemType.OTHER
            }
        }

        fun message(role: String, text: String): JSONObject = JSONObject().put(Responses.ROLE, role).put(Responses.CONTENT, text)

        private fun output(callId: String, output: String) = JSONObject().put(Responses.TYPE, Responses.TYPE_FUNCTION_CALL_OUTPUT)
            .put(Responses.CALL_ID, callId).put(Responses.OUTPUT, output)

        private fun inputText(text: String) = JSONObject().put(Responses.TYPE, Responses.TYPE_INPUT_TEXT).put(Responses.TEXT, text)

        private fun inputImage(url: String) = JSONObject().put(Responses.TYPE, Responses.TYPE_INPUT_IMAGE)
            .put(Responses.IMAGE_URL, url).put(Responses.DETAIL, Responses.DETAIL_ORIGINAL)

        /** content 可能是字符串，也可能是 Responses 的内容数组。 */
        internal fun text(item: JSONObject): String {
            val content = item.opt(Responses.CONTENT)
            return if (content is JSONArray) {
                (0 until content.length()).joinToString("\n") { content.getJSONObject(it).optString(Responses.TEXT) }
            } else content?.toString().orEmpty()
        }
    }
}
