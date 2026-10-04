package moe.uni.agent.backend

import android.content.Context
import android.os.ServiceSpecificException
import moe.uni.agent.AgentFailure
import moe.uni.agent.framework.IAgentScreen

/**
 * AgentFramework 的屏幕连接（ADR-0022 第 2 条）。虚拟屏幕由 AgentFramework 自己初始化，没有关联时它自己请 agentd 创建
 * （ADR-0029 第 7 条）；这里只确认它已经就绪。
 */
class ScreenClient(context: Context) {
    private val binding = FrameworkBinding(context, IAgentScreen.SERVICE_CLASS, IAgentScreen.Stub::asInterface)

    fun bind() = binding.bind()

    fun unbind() = binding.unbind()

    /**
     * 不做 setup 的连接：观察窗的镜像用（ADR-0031 第 5 条）。虚拟屏幕由 agent 的调用创建，镜像不去创建它；
     * 也不经 WatchedScreen，不算 agent 活跃。绑定还没完成时会等待，不在主线程调用。
     */
    fun bound(): IAgentScreen = binding.await()

    /** 已经创建好虚拟屏幕的连接。 */
    fun connected(): IAgentScreen = binding.await().also {
        try {
            it.setup()
        } catch (e: ServiceSpecificException) {
            if (e.errorCode != IAgentScreen.ERROR_NO_ASSOCIATION) throw e
            throw AgentFailure(AgentFailure.Reason.SCREEN_SETUP, e.message.orEmpty(), e)
        }
    }
}
