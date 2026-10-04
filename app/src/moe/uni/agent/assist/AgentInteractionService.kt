package moe.uni.agent.assist

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import moe.uni.agent.service.AgentService

/**
 * 系统助手（ADR-0033 第 1 条）。持有 ASSISTANT 角色后，角落滑动、长按 Home、电源键长按、助手键都经 SystemUI 的
 * AssistManager 调到这里，由系统绑定 [AgentSessionService] 显示面板。
 * 就绪后作为 [AgentService.AssistPanel] 注册：agent 操作主屏时收起面板，运行结束后重新弹出（第 5 条）。
 */
class AgentInteractionService : VoiceInteractionService(), AgentService.AssistPanel {
    private val main = Handler(Looper.getMainLooper())

    override fun onReady() {
        super.onReady()
        AgentService.assistPanel = this
    }

    override fun onShutdown() {
        if (AgentService.assistPanel === this) AgentService.assistPanel = null
        super.onShutdown()
    }

    override fun collapse(): Boolean = AgentSession.active?.collapse() ?: false

    override fun reopen(conversation: String) {
        main.post { showSession(Bundle().apply { putString(AgentSession.EXTRA_CONVERSATION, conversation) }, 0) }
    }
}
