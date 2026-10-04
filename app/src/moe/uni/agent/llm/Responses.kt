package moe.uni.agent.llm

/** OpenAI Responses 协议的字段名和取值（ADR-0003），请求、流式事件和历史条目共用。 */
object Responses {
    // 请求
    const val MODEL = "model"
    const val STORE = "store"
    const val STREAM = "stream"
    const val INPUT = "input"
    const val TOOLS = "tools"
    const val PARALLEL_TOOL_CALLS = "parallel_tool_calls"
    const val INSTRUCTIONS = "instructions"
    const val INCLUDE = "include"
    const val REASONING = "reasoning"
    const val EFFORT = "effort"
    const val INCLUDE_ENCRYPTED_REASONING = "reasoning.encrypted_content"
    const val TOOL_CHOICE = "tool_choice"
    const val TOOL_CHOICE_NONE = "none"

    // 历史条目
    const val TYPE = "type"
    const val ROLE = "role"
    const val CONTENT = "content"
    const val TEXT = "text"
    const val NAME = "name"
    const val ARGUMENTS = "arguments"
    const val CALL_ID = "call_id"
    const val OUTPUT = "output"

    const val TYPE_FUNCTION = "function"
    const val TYPE_FUNCTION_CALL = "function_call"
    const val TYPE_FUNCTION_CALL_OUTPUT = "function_call_output"
    const val TYPE_REASONING = "reasoning"
    const val TYPE_MESSAGE = "message"
    const val TYPE_OUTPUT_TEXT = "output_text"
    const val TYPE_INPUT_TEXT = "input_text"
    const val TYPE_INPUT_IMAGE = "input_image"

    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"

    const val IMAGE_URL = "image_url"
    const val DETAIL = "detail"
    const val DETAIL_ORIGINAL = "original"

    // 响应与流式事件
    const val RESPONSE = "response"
    const val STATUS = "status"
    const val STATUS_FAILED = "failed"
    const val STATUS_INCOMPLETE = "incomplete"
    const val ERROR = "error"
    const val MESSAGE = "message"
    const val CODE = "code"
    const val CODE_CONTEXT_LENGTH_EXCEEDED = "context_length_exceeded"
    const val USAGE = "usage"
    const val INPUT_TOKENS = "input_tokens"
    const val OUTPUT_TOKENS = "output_tokens"
    const val INCOMPLETE_DETAILS = "incomplete_details"
    const val REASON = "reason"
    const val REASON_MAX_OUTPUT_TOKENS = "max_output_tokens"
    const val DELTA = "delta"
    const val ITEM = "item"
    const val OUTPUT_INDEX = "output_index"

    const val EVENT_TEXT_DELTA = "response.output_text.delta"
    const val EVENT_ITEM_DONE = "response.output_item.done"
    const val EVENT_COMPLETED = "response.completed"
    const val EVENT_INCOMPLETE = "response.incomplete"
    const val EVENT_FAILED = "response.failed"
    const val EVENT_ERROR = "error"
    const val SSE_DONE = "[DONE]"
    const val SSE_DATA = "data:"
}
