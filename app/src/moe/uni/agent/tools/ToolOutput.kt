package moe.uni.agent.tools

import android.os.DeadObjectException
import android.os.RemoteException
import android.os.ServiceSpecificException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.util.concurrent.CancellationException
import moe.uni.agent.AgentFailure
import moe.uni.agent.agentd.ExecStatus
import moe.uni.agent.agentd.IAgentd
import moe.uni.agent.framework.ToolError
import org.json.JSONException
import org.json.JSONObject

/**
 * JSON 工具结果的公共字段和统一错误（ADR-0030 第 1、2 条）：成功是 {"ok":true,…}，
 * 失败是 {"ok":false,"error":{"code":…,"message":…}}。code 取自 ToolError；message 不含异常原文，原始异常只写日志。
 */
object ToolOutput {
    const val OK = "ok"
    const val ERROR = "error"
    const val CODE = "code"
    const val MESSAGE = "message"

    private const val TAG = "ToolOutput"

    /** agentd 错误码中小于它的是 errno（ADR-0020）。 */
    private const val ERRNO_MAX = 10000

    fun ok(): JSONObject = JSONObject().put(OK, true)

    fun failure(code: String, message: String): JSONObject = JSONObject().put(OK, false).put(ERROR, error(code, message))

    fun failure(e: ToolError): JSONObject = failure(e.code, e.message)

    fun error(code: String, message: String): JSONObject = JSONObject().put(CODE, code).put(MESSAGE, message)

    fun error(e: ToolError): JSONObject = error(e.code, e.message)

    /** 交给模型的文本。Android 的 org.json 会把 / 转义成 \/，路径和组件名因此难读又费 token，这里还原（仍是合法的 JSON）。 */
    fun text(value: JSONObject): String = value.toString().replace("\\/", "/")

    /**
     * 工具执行中抛出的异常转成错误。ServiceSpecificException 按 agentd 的错误码解释：AgentFramework 的错误码另有含义，
     * 调用 AgentFramework 的工具自己转换。不认识的异常记日志，只给模型通用的说明。
     */
    fun failureOf(e: Exception): ToolFailure = when (e) {
        is CancellationException -> throw e
        is ToolFailure -> e
        is ServiceSpecificException -> agentd(e)
        is AgentFailure -> ToolFailure(ToolError.UNAVAILABLE, describe(e))
        is DeadObjectException -> ToolFailure(ToolError.UNAVAILABLE, "与系统服务的连接断开了").also { Log.w(TAG, "tool failed", e) }
        is RemoteException -> ToolFailure(ToolError.UNAVAILABLE, "调用系统服务失败").also { Log.w(TAG, "tool failed", e) }
        is SecurityException -> ToolFailure(ToolError.PERMISSION_DENIED, "没有权限执行这个操作").also { Log.w(TAG, "tool failed", e) }
        is JSONException -> ToolFailure(ToolError.INVALID_ARGUMENT, "参数的类型不对").also { Log.i(TAG, "bad arguments", e) }
        else -> ToolFailure(ToolError.FAILED, "工具内部出错").also { Log.w(TAG, "tool failed", e) }
    }

    /** agentd 的错误（ADR-0020）：原始说明是给开发者的英文，只写日志。 */
    private fun agentd(e: ServiceSpecificException): ToolFailure {
        Log.i(TAG, "agentd error ${e.errorCode}: ${e.message}")
        return when (e.errorCode) {
            IAgentd.ERROR_INVALID_ARGUMENT -> ToolFailure(ToolError.INVALID_ARGUMENT, "agentd 拒绝了参数")
            IAgentd.ERROR_TOO_LARGE -> ToolFailure(ToolError.INVALID_ARGUMENT, "数据超过单次 ${IAgentd.MAX_CHUNK_BYTES / 1024}KiB 的上限")
            IAgentd.ERROR_NO_SUCH_ID -> ToolFailure(ToolError.NOT_FOUND, "没有这个 task")
            else -> if (e.errorCode in 1 until ERRNO_MAX) errno(e.errorCode) else ToolFailure(ToolError.FAILED, "agentd 出错")
        }
    }

    /** 系统调用错误：errno 名加说明，path 是相关的路径。 */
    fun errno(errno: Int, path: String = ""): ToolFailure {
        val code = when (errno) {
            OsConstants.ENOENT -> ToolError.NOT_FOUND
            OsConstants.EACCES, OsConstants.EPERM, OsConstants.EROFS -> ToolError.PERMISSION_DENIED
            OsConstants.EEXIST, OsConstants.ENOTEMPTY, OsConstants.EBUSY -> ToolError.CONFLICT
            OsConstants.ENOTDIR, OsConstants.EISDIR, OsConstants.EINVAL, OsConstants.ENAMETOOLONG, OsConstants.ELOOP ->
                ToolError.INVALID_ARGUMENT
            OsConstants.ETIMEDOUT -> ToolError.TIMEOUT
            else -> ToolError.FAILED
        }
        val text = ERRNO_TEXT[errno] ?: Os.strerror(errno)
        return ToolFailure(code, "${OsConstants.errnoName(errno)}：$text" + if (path.isNotEmpty()) "（$path）" else "")
    }

    private val ERRNO_TEXT = mapOf(
        OsConstants.ENOENT to "文件或目录不存在",
        OsConstants.EACCES to "没有权限",
        OsConstants.EPERM to "操作不被允许",
        OsConstants.EROFS to "只读文件系统",
        OsConstants.EEXIST to "已经存在",
        OsConstants.ENOTEMPTY to "目录不为空",
        OsConstants.EBUSY to "资源正忙",
        OsConstants.ENOTDIR to "不是目录",
        OsConstants.EISDIR to "是目录",
        OsConstants.EINVAL to "参数无效",
        OsConstants.ENAMETOOLONG to "名字太长",
        OsConstants.ELOOP to "符号链接层数过多",
        OsConstants.ENOSPC to "存储空间不足",
        OsConstants.ETIMEDOUT to "超时",
    )

    /** 工具执行中遇到的基础设施失败，给模型的说明。 */
    private fun describe(e: AgentFailure): String = when (e.reason) {
        AgentFailure.Reason.AGENTD_UNAVAILABLE -> "agentd 不可用"
        AgentFailure.Reason.AGENT_FRAMEWORK_UNAVAILABLE -> "AgentFramework 不可用"
        AgentFailure.Reason.SCREEN_SETUP -> "无法创建虚拟屏幕" + if (e.detail.isNotEmpty()) "：${e.detail}" else ""
        else -> "运行环境不可用"
    }

    fun stateName(s: ExecStatus) = when (s.state) {
        ExecStatus.RUNNING -> "running"
        ExecStatus.EXITED -> "exited"
        ExecStatus.TIMED_OUT -> "timed_out"
        else -> "killed"
    }
}
