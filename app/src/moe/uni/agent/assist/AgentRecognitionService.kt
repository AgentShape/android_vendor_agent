package moe.uni.agent.assist

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * 语音识别服务的占位（ADR-0033 第 8 条）：助手角色要求 voice_interaction 里声明 recognitionService，
 * v1 不做语音，收到请求直接报 ERROR_CLIENT。
 */
class AgentRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
