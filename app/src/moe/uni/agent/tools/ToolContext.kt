package moe.uni.agent.tools

import moe.uni.agent.framework.IAgentDevice
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.tools.file.FileOps
import moe.uni.agent.tools.js.ScriptRunner
import moe.uni.agent.tools.shell.Executor

/** 一次 agent loop 里工具共用的连接和运行时，都属于当前会话（ADR-0020、ADR-0024）。 */
class ToolContext(
    /** root shell；用户没有开启时为 null（ADR-0029 第 4 条）。 */
    val shell: Executor?,
    val files: FileOps,
    /** 已经创建好虚拟屏幕的 AgentFramework 连接。 */
    val screen: () -> IAgentScreen,
    /** AgentFramework 的系统能力（ADR-0029 第 6 条）。 */
    val device: () -> IAgentDevice,
    val scripts: ScriptRunner,
) {
    /** 停止：终止正在执行的 exec（整组 kill）和 exec_js。可以从任何线程调用。 */
    fun cancel() {
        shell?.cancel()
        scripts.cancel()
    }
}
