package moe.uni.agent.backend

import android.os.ServiceManager
import moe.uni.agent.AgentFailure
import moe.uni.agent.agentd.IAgentd
import moe.uni.agent.agentd.IAgentdSession

/**
 * agentd 的连接（ADR-0020）：一个对话一个 session，tag 是会话 id。agentd 重启后旧的连接和 session 一并失效，
 * 重新连接时丢弃。换了会话时关闭旧 session，其中仍在运行的 exec 会被整组 kill。
 */
class AgentdClient {
    private var agentd: IAgentd? = null
    private var session: IAgentdSession? = null
    private var sessionTag: String? = null

    @Synchronized
    fun agentd(): IAgentd {
        agentd?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        session = null
        val binder = ServiceManager.getService(IAgentd.SERVICE_NAME) ?: throw AgentFailure(AgentFailure.Reason.AGENTD_UNAVAILABLE)
        return IAgentd.Stub.asInterface(binder).also { agentd = it }
    }

    @Synchronized
    fun session(tag: String): IAgentdSession {
        val service = agentd()
        session?.let {
            if (sessionTag == tag && it.asBinder().isBinderAlive) return it
            runCatching { it.close() }
        }
        return service.openSession(tag).also { session = it; sessionTag = tag }
    }

    /** 会话被删除时关闭它的 session。 */
    @Synchronized
    fun close(tag: String) {
        if (sessionTag != tag) return
        session?.let { runCatching { it.close() } }
        session = null
        sessionTag = null
    }
}
