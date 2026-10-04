package moe.uni.agent.conversation

import moe.uni.agent.conversation.db.ItemEntity
import moe.uni.agent.conversation.db.ItemType
import moe.uni.agent.llm.Responses
import org.json.JSONObject

/** 给界面显示的一项：工具调用与它的结果合并成一项，推理过程不显示。 */
sealed interface ChatItem {
    data class User(val text: String) : ChatItem

    data class Assistant(val text: String) : ChatItem

    /** output 和 ok 为 null 表示还在执行。 */
    data class Tool(val id: String, val name: String, val arguments: String, val output: String?, val ok: Boolean?) : ChatItem

    companion object {
        /** 会话的全部记录（按顺序）转成界面的消息列表。 */
        fun from(items: List<ItemEntity>): List<ChatItem> {
            val outputs = items.filter { it.type == ItemType.FUNCTION_OUTPUT }.associateBy { it.callId }
            return items.mapNotNull { item ->
                when (item.type) {
                    ItemType.USER -> User(Conversation.text(JSONObject(item.body)))
                    ItemType.ASSISTANT -> Conversation.text(JSONObject(item.body)).takeIf { it.isNotBlank() }?.let(::Assistant)
                    ItemType.FUNCTION_CALL -> {
                        val call = JSONObject(item.body)
                        val output = outputs[item.callId]
                        Tool(item.callId.orEmpty(), call.optString(Responses.NAME), call.optString(Responses.ARGUMENTS),
                            output?.let { JSONObject(it.body).optString(Responses.OUTPUT) }, output?.ok)
                    }
                    ItemType.FUNCTION_OUTPUT, ItemType.REASONING, ItemType.OTHER -> null
                }
            }
        }
    }
}
