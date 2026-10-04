package moe.uni.agent.framework.device

import android.os.ServiceSpecificException
import moe.uni.agent.framework.IAgentDevice
import org.json.JSONObject

/** IAgentDevice 结果的公共字段（ADR-0030 第 1 条，与 AgentApp 的 ToolOutput 一致）。 */
internal object Results {
    const val OK = "ok"
    const val ERROR = "error"
    const val CODE = "code"
    const val MESSAGE = "message"

    fun ok(): JSONObject = JSONObject().put(OK, true)

    /** code 是 ToolError 的取值，message 只陈述事实。 */
    fun failure(code: String, message: String): JSONObject =
        JSONObject().put(OK, false).put(ERROR, JSONObject().put(CODE, code).put(MESSAGE, message))

    fun invalid(message: String) = ServiceSpecificException(IAgentDevice.ERROR_INVALID_ARGUMENT, message)
}
