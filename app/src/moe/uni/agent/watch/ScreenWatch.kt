package moe.uni.agent.watch

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State as ComposeState

/**
 * 观察窗的状态机（ADR-0031 第 4 条）。观察窗的全部状态都在 State 里，不另设"已发过""正在看""待撤销"之类的标志。
 * 对外只有 dispatch 和只读的 state；效果交给 WatchEffects 执行，执行失败作为 Unavailable 事件回到状态机。
 * 每次转移记一行日志（旧状态、事件、新状态、效果），这是唯一的调试手段。
 */
class ScreenWatch(private val effects: WatchEffects) {
    sealed class State {
        /** 状态所属的会话；Idle 没有。 */
        open val session: String? get() = null

        /** 没有观察窗。unavailable 是上一次发气泡失败的原因，供会话页提示；进入其他状态即清除。 */
        data class Idle(val unavailable: Event.Unavailable.Reason? = null) : State()

        /** 已发气泡，用户没有展开；loop 在运行。 */
        data class Posted(override val session: String) : State()

        /** 用户展开了镜像。running=false 表示 loop 已结束，等用户收起后撤。 */
        data class Viewing(override val session: String, val running: Boolean) : State()

        /** 用户在本次运行中划掉了气泡（或结束了镜像 Activity），本次运行不再发。 */
        data class Suppressed(override val session: String) : State()
    }

    sealed class Event {
        /** agent 对虚拟屏幕发起了一次调用（WatchedScreen）。 */
        data class AgentActive(val session: String) : Event()

        /** 该会话的 loop 结束（正常、停止、失败都算）。 */
        data class RunEnded(val session: String) : Event()

        /** 镜像 Activity onResume。 */
        data object ViewerShown : Event()

        /** 镜像 Activity onStop：用户收起，或灭屏、锁屏、切走，不区分。 */
        data object ViewerHidden : Event()

        /** 通知的 deleteIntent：用户划掉气泡，或系统移除了通知。 */
        data object Dismissed : Event()

        /** 发气泡失败，由效果执行器回报。 */
        data class Unavailable(val reason: Reason) : Event() {
            enum class Reason {
                /** 没有 POST_NOTIFICATIONS。 */
                NO_PERMISSION,

                /** 气泡在系统设置里被关闭（全局或本应用）。 */
                BUBBLES_OFF,

                /** conversation shortcut 推送失败。 */
                SHORTCUT_FAILED,

                /** 发通知失败。 */
                POST_FAILED,
            }
        }
    }

    sealed class Effect {
        /** 发气泡。 */
        data class Post(val session: String) : Effect()

        /** 气泡续用：换标题和跳转目标。 */
        data class Update(val session: String) : Effect()

        /** 撤掉气泡。 */
        data object Cancel : Effect()
    }

    private val lock = Object()
    private val current = mutableStateOf<State>(State.Idle())

    init {
        // 全进程一个（ADR-0031 第 1 条）；由 AgentService 创建，界面、镜像 Activity 和 WatchReceiver 经 instance 使用。
        instance = this
    }

    /** 当前状态，界面按它显示（Compose State，只读）。 */
    val state: ComposeState<State> get() = current

    init {
        // 初始状态是 Idle（没有观察窗）；上一个进程留下的气泡撤掉，使实际情况与状态一致。
        effects.execute(Effect.Cancel)
    }

    /**
     * 上报事件。可以从 binder 线程、执行线程和主线程调用；转移和效果的执行按到达顺序串行。
     * 效果失败时作为 Unavailable 接着转移，处理完才更新 state，界面看不到中间状态。
     */
    fun dispatch(event: Event) = synchronized(lock) {
        var state = current.value
        var next: Event? = event
        while (next != null) {
            val (new, out) = transition(state, next)
            Log.i(TAG, "$state + $next -> $new $out")
            state = new
            next = out.firstNotNullOfOrNull(effects::execute)?.let { Event.Unavailable(it) }
        }
        current.value = state
    }

    companion object {
        private const val TAG = "ScreenWatch"

        /** 当前进程的状态机；AgentService 创建之前为 null，此时观察窗就是初始的 Idle。 */
        var instance by mutableStateOf<ScreenWatch?>(null)
            private set

        /**
         * 转移表（ADR-0031 第 4 条），除日志外没有副作用。表中的空格保持原状态、无效果；
         * 不在表里的组合（会话不一致）同样保持，并另记一行日志。
         */
        fun transition(state: State, event: Event): Pair<State, List<Effect>> {
            val keep = state to emptyList<Effect>()
            return when (state) {
                is State.Idle -> when (event) {
                    is Event.AgentActive -> State.Posted(event.session) to listOf(Effect.Post(event.session))
                    // 上次失败的原因只在那次运行里提示。
                    is Event.RunEnded -> State.Idle() to emptyList()
                    else -> keep
                }
                is State.Posted -> when (event) {
                    is Event.AgentActive -> if (event.session == state.session) keep else outside(state, event)
                    is Event.RunEnded -> if (event.session == state.session) State.Idle() to listOf(Effect.Cancel) else outside(state, event)
                    Event.ViewerShown -> State.Viewing(state.session, running = true) to emptyList()
                    Event.ViewerHidden -> keep
                    Event.Dismissed -> State.Suppressed(state.session) to emptyList()
                    is Event.Unavailable -> State.Idle(event.reason) to emptyList()
                }
                is State.Viewing -> if (state.running) {
                    when (event) {
                        is Event.AgentActive -> if (event.session == state.session) keep else outside(state, event)
                        is Event.RunEnded ->
                            if (event.session == state.session) State.Viewing(state.session, running = false) to emptyList()
                            else outside(state, event)
                        Event.ViewerShown -> keep
                        Event.ViewerHidden -> State.Posted(state.session) to emptyList()
                        Event.Dismissed -> State.Suppressed(state.session) to emptyList()
                        is Event.Unavailable -> State.Idle(event.reason) to emptyList()
                    }
                } else {
                    when (event) {
                        // 新的 loop 开始使用虚拟屏幕：气泡直接续用，不撤再发。
                        is Event.AgentActive ->
                            if (event.session == state.session) State.Viewing(state.session, running = true) to emptyList()
                            else State.Viewing(event.session, running = true) to listOf(Effect.Update(event.session))
                        is Event.RunEnded -> if (event.session == state.session) keep else outside(state, event)
                        Event.ViewerShown -> keep
                        Event.ViewerHidden -> State.Idle() to listOf(Effect.Cancel)
                        Event.Dismissed -> State.Idle() to emptyList()
                        is Event.Unavailable -> State.Idle(event.reason) to emptyList()
                    }
                }
                is State.Suppressed -> when (event) {
                    is Event.AgentActive -> if (event.session == state.session) keep else outside(state, event)
                    // 划掉的气泡在溢出区里、通知还在，撤掉它们。
                    is Event.RunEnded -> if (event.session == state.session) State.Idle() to listOf(Effect.Cancel) else outside(state, event)
                    else -> keep
                }
            }
        }

        private fun outside(state: State, event: Event): Pair<State, List<Effect>> {
            Log.w(TAG, "not in table: $state + $event, keep")
            return state to emptyList()
        }
    }
}
