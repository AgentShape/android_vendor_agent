package moe.uni.agent.assist

import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.View
import android.view.WindowManagerGlobal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.android.internal.policy.IKeyguardDismissCallback
import moe.uni.agent.R
import moe.uni.agent.conversation.Conversations
import moe.uni.agent.service.AgentService
import moe.uni.agent.ui.AgentTheme
import moe.uni.agent.ui.AssistPanel
import moe.uni.agent.ui.MainActivity

/**
 * 助手会话（ADR-0033）：系统的 TYPE_VOICE_INTERACTION 窗口里显示助手面板。
 * 会话不是 Activity，Compose 需要的 Lifecycle 和 SavedState owner 由它自己提供：显示时 RESUMED，隐藏时回到 CREATED。
 * 每次唤起新建会话；已有会话在运行时显示那个会话（第 6 条）。锁屏时先请求解锁，解锁后再显示（第 7 条）。
 * agent 要操作主屏时由 [collapse] 收起（关掉 UI，会话仍在）；运行结束后系统助手服务带着会话 id 重新 showSession（第 5 条）。
 */
class AgentSession(context: Context) : VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    private val main = Handler(Looper.getMainLooper())

    init {
        // 输入法由面板的 imePadding 让开，窗口不平移（主题里设 adjustNothing，见 styles.xml）。要在窗口创建之前设置。
        setTheme(R.style.Theme_Agent_AssistSession)
    }

    private var service by mutableStateOf<AgentService?>(null)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as AgentService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    /** 面板的会话，还没发送时为 null。 */
    private var conversation by mutableStateOf<String?>(null)
    private var text by mutableStateOf("")
    /** 每次唤起加一，面板据此重新聚焦输入框。 */
    private var shown by mutableIntStateOf(0)

    /** 面板正显示在屏幕上（主线程读写）。 */
    private var visible = false
    /** 这次显示是运行结束后重新弹出的，显示这个会话（showSession 的参数带来）。 */
    private var reopen: String? = null

    override fun onCreate() {
        super.onCreate()
        active = this
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        Conversations.init(context.applicationContext)
        // 和 MainActivity 一样先作为 started service 启动：面板收起后 loop 仍在常驻进程里运行。
        val intent = Intent(context, AgentService::class.java)
        context.startService(intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    override fun onCreateContentView(): View {
        // Compose 从根视图往上找 owner：窗口的 DecorView 和 ComposeView 都设上。
        window.window?.decorView?.let(::attachOwners)
        return ComposeView(context).apply {
            attachOwners(this)
            setContent {
                AgentTheme {
                    AssistPanel(
                        service, conversation, text, { text = it }, shown,
                        onSend = ::send, onOpenInApp = ::openInApp, onDismiss = ::hide,
                    )
                }
            }
        }
    }

    private fun attachOwners(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    /** 在窗口显示之前调用。锁屏时不显示（onShow 里先解锁），其余情况直接显示。 */
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        reopen = args?.getString(EXTRA_CONVERSATION)
        visible = !locked()
        setUiEnabled(visible)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        if (locked()) {
            unlockThenShow()
            return
        }
        conversation = reopen ?: AgentService.runningId
        text = ""
        registry.currentState = Lifecycle.State.RESUMED
        // 新开的面板聚焦输入框。运行中再次唤起是看进度、停止，运行结束后重新弹出是看结果：这两种情况不能发送，不弹键盘。
        if (reopen == null && !AgentService.running) shown++
    }

    /**
     * agent 要操作主屏：关掉 UI 收起面板，会话保留（ADR-0033 第 5 条）。返回时窗口已经隐藏，再等一小段让合成生效，
     * 之后的截图和点按碰不到面板。面板原本就没显示时返回 false。在 agent loop 的线程上调用。
     */
    fun collapse(): Boolean {
        val was = AtomicBoolean(false)
        val done = CountDownLatch(1)
        main.post {
            if (visible) {
                was.set(true)
                visible = false
                setUiEnabled(false)
            }
            done.countDown()
        }
        done.await(COLLAPSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (was.get()) Thread.sleep(HIDE_SETTLE_MS)
        return was.get()
    }

    override fun onHide() {
        visible = false
        registry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        if (active === this) active = null
        context.unbindService(connection)
        super.onDestroy()
    }

    private fun locked() = context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun send(prompt: String) {
        service?.sendTo(conversation, prompt, assisted = true)?.let {
            conversation = it
            text = ""
        }
    }

    /** 在 App 中继续：打开 MainActivity 的这个会话并收起面板。 */
    private fun openInApp(id: String) {
        context.startActivity(
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONVERSATION, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        hide()
    }

    /** 锁屏时先收起，请求解锁（不安全锁屏直接解开，安全锁屏弹出验证），成功后重新显示；取消或失败则不显示。 */
    private fun unlockThenShow() {
        hide()
        val callback = object : IKeyguardDismissCallback.Stub() {
            override fun onDismissError() = Unit
            override fun onDismissSucceeded() { main.post { show(null, 0) } }
            override fun onDismissCancelled() = Unit
        }
        try {
            checkNotNull(WindowManagerGlobal.getWindowManagerService()).dismissKeyguard(callback, null)
        } catch (e: Exception) {
            Log.w(TAG, "dismissKeyguard failed", e)
        }
    }

    companion object {
        private const val TAG = "AgentSession"

        /** showSession 的参数：重新弹出时显示的会话 id。 */
        const val EXTRA_CONVERSATION = "moe.uni.agent.extra.ASSIST_CONVERSATION"

        /** 当前的会话；系统没有绑定会话服务时为 null。 */
        @Volatile var active: AgentSession? = null
            private set

        private const val COLLAPSE_TIMEOUT_MS = 1_000L
        /** 隐藏窗口后等合成生效的时间（实现方默认）。 */
        private const val HIDE_SETTLE_MS = 200L

    }
}
