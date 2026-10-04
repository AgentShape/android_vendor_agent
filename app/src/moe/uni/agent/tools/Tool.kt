package moe.uni.agent.tools

import org.json.JSONObject

/**
 * 一个工具（ADR-0025）：给模型的定义（名字、说明、参数）、执行和界面上的一行摘要都由实现提供。
 * 实现是无状态的单例，执行时需要的连接和运行时从 ToolContext 取，所以工具列表和 schema 是静态的。
 */
interface Tool {
    val name: String
    val description: String
    val kind: Kind

    /**
     * 所属工具组的说明：同组工具共用的说明（运行方式、结果的读法等），由组的公共基类给出，没有时为空。
     * 一次运行提供了这组工具，说明才放进 instructions，每组只放一次（ToolRegistry.groupDescriptions）。
     */
    val groupDescription: String get() = ""

    /** 声明参数；默认没有参数。 */
    fun parameters(schema: Schema) {}

    /** CancellationException 继续向上抛，由 loop 处理停止；其余异常由 ToolRegistry 转成统一的错误（ToolOutput.failureOf）。 */
    fun run(context: ToolContext, args: JSONObject): ToolResult

    /** 界面上一行显示的摘要。 */
    fun summary(args: JSONObject): String = ""

    /** 界面按类别显示图标。 */
    enum class Kind { SHELL, FILE, SCREEN, DEVICE, SCRIPT }
}

/**
 * 工具结果：交给模型的 output 是 JSON 对象（ADR-0030 第 1 条），屏幕工具和 exec_js 另带 PNG 截图（ADR-0022 第 12 条）。
 * 成败就是 JSON 的 ok 字段，界面据此显示。
 */
class ToolResult private constructor(val output: String, val ok: Boolean, val images: List<ByteArray>) {
    companion object {
        fun json(value: JSONObject, images: List<ByteArray> = emptyList()) =
            ToolResult(ToolOutput.text(value), value.optBoolean(ToolOutput.OK), images)
    }
}
