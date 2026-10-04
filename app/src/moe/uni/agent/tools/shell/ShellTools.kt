package moe.uni.agent.tools.shell

import moe.uni.agent.agentd.IAgentd
import moe.uni.agent.agentd.TaskInfo
import moe.uni.agent.framework.ToolError
import moe.uni.agent.tools.Schema
import moe.uni.agent.tools.Tool
import moe.uni.agent.tools.ToolContext
import moe.uni.agent.tools.ToolFailure
import moe.uni.agent.tools.ToolOutput
import moe.uni.agent.tools.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/** exec 与 task（ADR-0011、ADR-0016）：工具名、参数和结果字段，以及工具列表。 */
object ShellTools {
    // 工具名
    const val EXEC = "exec"
    const val TASK_START = "task_start"
    const val TASK_LIST = "task_list"
    const val TASK_READ = "task_read"
    const val TASK_SIGNAL = "task_signal"
    const val TASK_STOP = "task_stop"

    // 参数
    const val COMMAND = "command"
    const val CWD = "cwd"
    const val TIMEOUT_MS = "timeout_ms"
    const val TASK_ID = "task_id"
    const val CURSOR = "cursor"
    const val MAX_BYTES = "max_bytes"
    const val SIGNAL = "signal"

    // 结果字段（exec_js 的 sh() 返回同样的对象）
    const val STATE = "state"
    const val EXIT_CODE = "exit_code"
    const val OUTPUT = "output"
    const val TRUNCATED = "truncated"
    const val SAVED_OUTPUT_PATH = "saved_output_path"
    const val TASKS = "tasks"
    const val STARTED_MS = "started_ms"
    const val ALIVE = "alive"
    const val NEXT_CURSOR = "next_cursor"
    const val DROPPED = "dropped"
    const val CLOSED = "closed"

    const val ROOT_SHELL_OFF = "用户没有开启 root shell"

    /** exec 和 task 的工具组说明。 */
    val GROUP_DESCRIPTION = """
        exec 和 task：
        - 每次都是新的 ${Shell.displayName} -c，以 root 执行，不保留状态，cd 不延续。
        - HOME=${IAgentd.HOME_DIR}（其中的文件不能执行）；TMPDIR=${IAgentd.TMP_DIR}。
        - 用户存储之外的路径只能用 exec 访问。
        - 没有 Python 环境；需要写脚本时用 shell 或 exec_js。
    """.trimIndent()

    val ALL: List<Tool> = listOf(Exec, TaskStart, TaskList, TaskRead, TaskSignal, TaskStop)
}

/** shell 类工具的公共部分：exec 和 task_start 的参数，按命令或 task id 显示摘要。 */
private abstract class ShellTool(override val name: String, override val description: String) : Tool {
    override val kind get() = Tool.Kind.SHELL
    override val groupDescription get() = ShellTools.GROUP_DESCRIPTION

    override fun summary(args: JSONObject): String =
        args.optString(ShellTools.COMMAND).ifEmpty { if (args.has(ShellTools.TASK_ID)) "#" + args.optInt(ShellTools.TASK_ID) else "" }

    protected fun Schema.command() {
        string(ShellTools.COMMAND, "shell 命令", required = true)
        string(ShellTools.CWD, "工作目录，默认 HOME（${IAgentd.HOME_DIR}）")
        integer(ShellTools.TIMEOUT_MS, "超时毫秒；0 为默认（exec 10 分钟，task 不限时），-1 不限时")
    }

    /** 只有用户开启 root shell 时才会提供这些工具（ADR-0029 第 4 条）。 */
    protected fun shell(context: ToolContext): Executor = context.shell ?: throw ToolFailure(ToolError.PERMISSION_DENIED, ShellTools.ROOT_SHELL_OFF)

    protected fun Schema.taskId() = integer(ShellTools.TASK_ID, "task id", required = true)

    protected fun request(args: JSONObject) =
        Executor.request(args.getString(ShellTools.COMMAND), args.optString(ShellTools.CWD), args.optLong(ShellTools.TIMEOUT_MS, 0))
}

private object Exec : ShellTool(
    ShellTools.EXEC,
    "以 root 在新 shell 中执行前台命令，结束后返回退出状态和输出（只保留尾部 2000 行/50KiB，完整输出另存）。" +
        "命令结束时残留的后台进程会被终止；要常驻请用 ${ShellTools.TASK_START}。",
) {
    override fun parameters(schema: Schema) = schema.command()
    override fun run(context: ToolContext, args: JSONObject) = ToolResult.json(shell(context).exec(request(args)))
}

private object TaskStart : ShellTool(
    ShellTools.TASK_START, "启动常驻 task，立即返回 task_id。task 不随对话结束，用 ${ShellTools.TASK_STOP} 终止。",
) {
    override fun parameters(schema: Schema) = schema.command()
    override fun run(context: ToolContext, args: JSONObject) =
        ToolResult.json(ToolOutput.ok().put(ShellTools.TASK_ID, shell(context).startTask(request(args))))
}

private object TaskList : ShellTool(ShellTools.TASK_LIST, "列出所有 task。") {
    override fun run(context: ToolContext, args: JSONObject) =
        ToolResult.json(ToolOutput.ok().put(ShellTools.TASKS, JSONArray(shell(context).agentd.listTasks().map(::taskJson))))

    private fun taskJson(t: TaskInfo) = JSONObject().put(ShellTools.TASK_ID, t.id).put(ShellTools.COMMAND, t.summary)
        .put(ShellTools.STARTED_MS, t.startTimeMs).put(ShellTools.STATE, ToolOutput.stateName(t.status))
        .put(ShellTools.EXIT_CODE, t.status.exitCode).put(ShellTools.ALIVE, t.alive)
}

private object TaskRead : ShellTool(
    ShellTools.TASK_READ, "读取 task 输出。cursor 是输出流的字节偏移，用返回的 next_cursor 继续；dropped 是已被覆盖、读不到的字节数。",
) {
    override fun parameters(schema: Schema) = with(schema) {
        taskId()
        integer(ShellTools.CURSOR, "默认 0")
        integer(ShellTools.MAX_BYTES, "默认 ${OutputCapture.LIMIT}")
    }

    override fun run(context: ToolContext, args: JSONObject): ToolResult {
        val out = shell(context).agentd.readTask(args.getInt(ShellTools.TASK_ID), args.optLong(ShellTools.CURSOR),
            args.optInt(ShellTools.MAX_BYTES, OutputCapture.LIMIT))
        return ToolResult.json(ToolOutput.ok().put(ShellTools.OUTPUT, out.data.toString(Charsets.UTF_8))
            .put(ShellTools.NEXT_CURSOR, out.nextCursor).put(ShellTools.DROPPED, out.dropped).put(ShellTools.CLOSED, out.closed))
    }
}

private object TaskSignal : ShellTool(ShellTools.TASK_SIGNAL, "向 task 的整个进程组发信号，例如 15=TERM、19=STOP、18=CONT。") {
    override fun parameters(schema: Schema) = with(schema) {
        taskId()
        integer(ShellTools.SIGNAL, "信号编号", required = true)
    }

    override fun run(context: ToolContext, args: JSONObject): ToolResult {
        shell(context).agentd.signalTask(args.getInt(ShellTools.TASK_ID), args.getInt(ShellTools.SIGNAL))
        return ToolResult.json(ToolOutput.ok())
    }
}

private object TaskStop : ShellTool(ShellTools.TASK_STOP, "终止并移除 task。") {
    override fun parameters(schema: Schema) = schema.taskId()

    override fun run(context: ToolContext, args: JSONObject): ToolResult {
        shell(context).agentd.stopTask(args.getInt(ShellTools.TASK_ID))
        return ToolResult.json(ToolOutput.ok())
    }
}
