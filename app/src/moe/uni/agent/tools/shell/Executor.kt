package moe.uni.agent.tools.shell

import android.os.ParcelFileDescriptor
import android.system.OsConstants
import java.io.File
import java.util.UUID
import moe.uni.agent.agentd.ExecRequest
import moe.uni.agent.agentd.ExecStatus
import moe.uni.agent.agentd.IAgentd
import moe.uni.agent.agentd.IAgentdSession
import moe.uni.agent.framework.ToolError
import moe.uni.agent.storage.UserStorage
import moe.uni.agent.tools.ToolOutput
import org.json.JSONObject

/**
 * 以 root 在新 shell 中执行（ADR-0011、ADR-0020）：走当前会话的 agentd session。
 * exec 工具、exec_js 的 sh() 和首次 CDM 关联都用它；停止时整组 kill 正在运行的 exec。
 */
class Executor(private val session: IAgentdSession, val agentd: IAgentd) {
    @Volatile private var cancelled = false
    @Volatile private var running = 0

    fun cancel() {
        cancelled = true
        running.takeIf { it != 0 }?.let { runCatching { session.signal(it, OsConstants.SIGKILL) } }
    }

    fun exec(command: String, cwd: String = "", timeoutMs: Long = 0): JSONObject = exec(request(command, cwd, timeoutMs))

    /** 执行一条命令并收集输出，返回 exec 工具的结果对象。 */
    fun exec(request: ExecRequest): JSONObject {
        val handle = session.exec(request)
        running = handle.id
        if (cancelled) cancel()
        val capture = OutputCapture(File(File(UserStorage.home, OUTPUTS_DIR).apply { mkdirs() }, "${UUID.randomUUID()}$OUTPUT_SUFFIX"))
        ParcelFileDescriptor.AutoCloseInputStream(handle.output).use { input ->
            val buf = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                capture.append(buf, n)
            }
        }
        running = 0
        // 读到 EOF 时状态已经记录好（ADR-0020）。
        val status = session.status(handle.id)
        val (text, truncated) = capture.finish()
        val ok = status.state == ExecStatus.EXITED && status.exitCode == 0
        val result = if (ok) ToolOutput.ok() else JSONObject().put(ToolOutput.OK, false).put(ToolOutput.ERROR, error(status))
        return result
            .put(ShellTools.STATE, ToolOutput.stateName(status))
            .put(ShellTools.EXIT_CODE, status.exitCode)
            .put(ShellTools.SIGNAL, status.signal)
            .put(ShellTools.OUTPUT, text)
            .put(ShellTools.TRUNCATED, truncated)
            .put(ShellTools.SAVED_OUTPUT_PATH, if (truncated) capture.file.path else JSONObject.NULL)
    }

    /** 命令没有正常结束（ADR-0030 第 2 条）；被停止按钮整组 kill 的算作 cancelled。 */
    private fun error(status: ExecStatus): JSONObject = when {
        status.state == ExecStatus.EXITED -> ToolOutput.error(ToolError.FAILED, "命令以退出码 ${status.exitCode} 结束")
        status.state == ExecStatus.TIMED_OUT -> ToolOutput.error(ToolError.TIMEOUT, "命令超时，已终止")
        cancelled -> ToolOutput.error(ToolError.CANCELLED, "用户停止了运行，命令已终止")
        else -> ToolOutput.error(ToolError.FAILED, "命令被信号 ${status.signal} 终止")
    }

    /** 启动常驻 task，返回 task id（ADR-0016）。 */
    fun startTask(request: ExecRequest): Int = session.startTask(request)

    companion object {
        /** cwd 为空时由 agentd 使用 HOME（ADR-0021）；timeoutMs 为 0 用默认值。 */
        fun request(command: String, cwd: String, timeoutMs: Long) = ExecRequest().apply {
            argv = Shell.argv(command)
            this.cwd = cwd
            this.timeoutMs = timeoutMs
        }

        /** 被截断的完整输出放在 agent 目录下，模型用文件工具读取（ADR-0029 第 2 条）。 */
        private const val OUTPUTS_DIR = ".outputs"
        private const val OUTPUT_SUFFIX = ".out"
        private const val BUFFER_BYTES = 8192
    }
}
