package moe.uni.agent.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** 每次系统需要会话时新建一个 [AgentSession]（ADR-0033 第 1 条）。 */
class AgentSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AgentSession(this)
}
