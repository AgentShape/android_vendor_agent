package moe.uni.agent.tools

import moe.uni.agent.framework.ToolError
import moe.uni.agent.llm.Responses
import moe.uni.agent.tools.device.DeviceTools
import moe.uni.agent.tools.file.FileTools
import moe.uni.agent.tools.js.ExecJs
import moe.uni.agent.tools.screen.ScreenTools
import moe.uni.agent.tools.shell.ShellTools
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** 工具列表：汇总成发给模型的 schema，按名字分发。错误统一转成 {code, message}（ADR-0030 第 2 条）。 */
class ToolRegistry(tools: List<Tool>) {
    private val byName = tools.associateBy { it.name }
    private val required = tools.associate { tool -> tool.name to Schema().also(tool::parameters).requiredNames }

    /** 这次提供的各工具组的说明，按工具顺序去重；没有提供的工具组不出现。 */
    val groupDescriptions: List<String> = tools.map { it.groupDescription }.filter { it.isNotEmpty() }.distinct()

    /** 顺序固定，请求前缀才能命中 prompt cache。 */
    val schemas: JSONArray = JSONArray(tools.map { tool -> Schema().also(tool::parameters).toJson(tool.name, tool.description) })

    fun find(name: String): Tool? = byName[name]

    /** call 是模型输出里的 function_call 条目。 */
    fun run(call: JSONObject, context: ToolContext): ToolResult = invoke(call.getString(Responses.NAME), context) {
        try {
            JSONObject(call.getString(Responses.ARGUMENTS))
        } catch (e: JSONException) {
            throw ToolFailure(ToolError.INVALID_ARGUMENT, "参数不是 JSON 对象")
        }
    }

    /** exec_js 的 tool() 用：按名字调用，参数已经是对象（ADR-0029 第 6 条）。 */
    fun invoke(name: String, args: JSONObject, context: ToolContext): ToolResult = invoke(name, context) { args }

    private fun invoke(name: String, context: ToolContext, args: () -> JSONObject): ToolResult = try {
        val tool = byName[name] ?: throw ToolFailure(ToolError.NOT_FOUND, "没有名为 $name 的工具")
        val a = args()
        required.getValue(name).firstOrNull { !a.has(it) || a.isNull(it) }?.let {
            throw ToolFailure(ToolError.INVALID_ARGUMENT, "缺少参数 $it")
        }
        tool.run(context, a)
    } catch (e: Exception) {
        val failure = ToolOutput.failureOf(e)
        ToolResult.json(ToolOutput.failure(failure.code, failure.message.orEmpty()))
    }

    companion object {
        /** 全部工具，界面按名字查类别和摘要用。 */
        val CATALOG = ToolRegistry(ShellTools.ALL + FileTools.ALL + ExecJs.WITH_SHELL + ScreenTools.ALL + DeviceTools.ALL)

        /** 一次运行提供给模型的工具：用户没有开启 root shell 时不提供 exec 和 task（ADR-0029 第 4 条）。 */
        fun forRun(rootShell: Boolean): ToolRegistry =
            if (rootShell) CATALOG else ToolRegistry(FileTools.ALL + ExecJs.WITHOUT_SHELL + ScreenTools.ALL + DeviceTools.ALL)
    }
}
