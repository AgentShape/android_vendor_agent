package moe.uni.agent.ui

import androidx.annotation.StringRes
import moe.uni.agent.AgentFailure
import moe.uni.agent.R
import moe.uni.agent.settings.SettingsStore
import moe.uni.agent.watch.ScreenWatch

/** 失败原因对应的界面文字。 */
@StringRes
fun AgentFailure.Reason.message(): Int = when (this) {
    AgentFailure.Reason.NOT_CONFIGURED -> R.string.failure_not_configured
    AgentFailure.Reason.KEY_UNREADABLE -> R.string.failure_key_unreadable
    AgentFailure.Reason.NETWORK -> R.string.failure_network
    AgentFailure.Reason.HOST_UNRESOLVED -> R.string.failure_host_unresolved
    AgentFailure.Reason.TIMEOUT -> R.string.failure_timeout
    AgentFailure.Reason.TLS -> R.string.failure_tls
    AgentFailure.Reason.CONNECTION_LOST -> R.string.failure_connection_lost
    AgentFailure.Reason.UNAUTHORIZED -> R.string.failure_unauthorized
    AgentFailure.Reason.NOT_FOUND -> R.string.failure_not_found
    AgentFailure.Reason.RATE_LIMITED -> R.string.failure_rate_limited
    AgentFailure.Reason.SERVER -> R.string.failure_server
    AgentFailure.Reason.REJECTED -> R.string.failure_rejected
    AgentFailure.Reason.BAD_RESPONSE -> R.string.failure_bad_response
    AgentFailure.Reason.MODEL_FAILED -> R.string.failure_model_failed
    AgentFailure.Reason.CONTEXT_OVERFLOW -> R.string.failure_context_overflow
    AgentFailure.Reason.AGENTD_UNAVAILABLE -> R.string.failure_agentd
    AgentFailure.Reason.AGENT_FRAMEWORK_UNAVAILABLE -> R.string.failure_agent_framework
    AgentFailure.Reason.SCREEN_SETUP -> R.string.failure_screen_setup
    AgentFailure.Reason.UNEXPECTED -> R.string.failure_unexpected
}

/** 附加信息是否给用户看：主机名、HTTP 状态和服务端说明有用；CDM 关联的原始输出只给日志和模型。 */
val AgentFailure.visibleDetail: String
    get() = if (reason == AgentFailure.Reason.SCREEN_SETUP) "" else detail

/** 设置填写问题对应的界面文字。 */
@StringRes
fun SettingsStore.Problem.message(): Int = when (this) {
    SettingsStore.Problem.BASE_URL_INVALID -> R.string.invalid_base_url
    SettingsStore.Problem.BASE_URL_NOT_HTTPS -> R.string.invalid_base_url_https
    SettingsStore.Problem.MODEL_MISSING -> R.string.invalid_model
    SettingsStore.Problem.EFFORT_INVALID -> R.string.invalid_effort
    SettingsStore.Problem.KEY_MISSING -> R.string.invalid_key
    SettingsStore.Problem.CONTEXT_WINDOW_INVALID -> R.string.invalid_context_window
}

/** 观察窗发不出气泡的原因对应的界面文字（ADR-0031 第 6 条）。 */
@StringRes
fun ScreenWatch.Event.Unavailable.Reason.message(): Int = when (this) {
    ScreenWatch.Event.Unavailable.Reason.NO_PERMISSION -> R.string.watch_no_permission
    ScreenWatch.Event.Unavailable.Reason.BUBBLES_OFF -> R.string.watch_bubbles_off
    ScreenWatch.Event.Unavailable.Reason.SHORTCUT_FAILED, ScreenWatch.Event.Unavailable.Reason.POST_FAILED -> R.string.watch_failed
}
