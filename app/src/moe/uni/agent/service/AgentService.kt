package moe.uni.agent.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors
import moe.uni.agent.AgentFailure
import moe.uni.agent.agentd.IAgentd
import moe.uni.agent.backend.AgentdClient
import moe.uni.agent.backend.FrameworkBinding
import moe.uni.agent.backend.ScreenClient
import moe.uni.agent.conversation.Conversation
import moe.uni.agent.framework.IAgentDevice
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.conversation.Conversations
import moe.uni.agent.llm.ResponsesProvider
import moe.uni.agent.loop.AgentLoop
import moe.uni.agent.prompt.Prompts
import moe.uni.agent.settings.SettingsStore
import moe.uni.agent.storage.UserStorage
import moe.uni.agent.tools.ToolContext
import moe.uni.agent.tools.ToolRegistry
import moe.uni.agent.tools.file.FileOps
import moe.uni.agent.tools.js.ScriptEngine
import moe.uni.agent.tools.js.ScriptRunner
import moe.uni.agent.tools.shell.Executor
import moe.uni.agent.ui.MainActivity
import moe.uni.agent.ui.MirrorActivity
import moe.uni.agent.watch.NotificationEffects
import moe.uni.agent.watch.ScreenWatch
import moe.uni.agent.watch.WatchedScreen

/**
 * 常驻 app 里的 Service（ADR-0020）：持有 app → LLM（agent loop）、app → agentd（能力调用）
 * 和 app → AgentFramework（屏幕控制，ADR-0022）三条连接。界面只和它通信。
 * 同一时间只运行一个会话的 agent loop（ADR-0026 第 2 条）。
 */
class AgentService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val agentd = AgentdClient()
    private lateinit var screen: ScreenClient
    private lateinit var device: FrameworkBinding<IAgentDevice>
    @Volatile private var loop: AgentLoop? = null
    @Volatile private var stopped = false

    /** 当前会话的 exec_js 运行时（ADR-0024）；换了会话就重建。只在执行线程上使用。 */
    private var script: ScriptEngine? = null
    private var scriptTag: String? = null

    inner class LocalBinder : Binder() {
        val service: AgentService get() = this@AgentService
    }

    override fun onCreate() {
        super.onCreate()
        Conversations.init(this)
        UserStorage.ensure()
        // 观察窗（ADR-0031）：全进程一个状态机；Activity 的 intent 在这里注入，watch 包不依赖 ui。
        if (ScreenWatch.instance == null) {
            ScreenWatch(NotificationEffects(this, Intent(Intent.ACTION_MAIN).setClass(this, MainActivity::class.java)) { session ->
                Intent(this, MirrorActivity::class.java).putExtra(MirrorActivity.EXTRA_SESSION, session)
            })
        }
        screen = ScreenClient(this).apply { bind() }
        device = FrameworkBinding(this, IAgentDevice.SERVICE_CLASS, IAgentDevice.Stub::asInterface).apply { bind() }
    }

    override fun onDestroy() {
        screen.unbind()
        device.unbind()
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    /** 观察窗的镜像用（ADR-0031）：不经 WatchedScreen、不做 setup 的 AgentFramework 连接。会等待绑定，不在主线程调用。 */
    fun mirrorScreen(): IAgentScreen = screen.bound()

    /** 任务页用。 */
    fun agentd(): IAgentd = agentd.agentd()

    /** 在正在查看的会话里发送；新对话时新建会话。 */
    fun send(prompt: String) {
        sendTo(Conversations.current.value, prompt)
    }

    /**
     * 在指定会话里发送，[id] 为 null 时新建会话；返回会话 id。已有会话在运行时不发送，返回 null。
     * 助手面板用（ADR-0033 第 6 条）：面板有自己的会话，不跟随 app 里正在查看的会话。
     * [assisted] 表示从助手面板发起：系统提示说明这一点；以主屏为目标的调用之前收起面板并显示状态胶囊（第 5 条）。
     */
    fun sendTo(id: String?, prompt: String, assisted: Boolean = false): String? {
        if (running) return null
        return (id ?: Conversations.newId()).also { start(it, prompt, assisted) }
    }

    /** 出错后重试：接着现有历史继续；最后一项已经是回复时重发最后一条用户消息（在 run 里判断）。 */
    fun retry() {
        if (running) return
        start(Conversations.current.value ?: return, null)
    }

    /** 停止正在运行的会话。断开 HTTP 连接和终止 exec 都可能阻塞，不放在主线程。 */
    fun stop() {
        stopped = true
        Thread { loop?.stop() }.start()
    }

    fun newConversation() = Conversations.select(null)

    fun open(id: String) = Conversations.select(id)

    fun rename(id: String, title: String) = Conversations.rename(id, title)

    /** 正在运行的会话不能删除；它的 agentd session 一并关闭，其中仍在运行的 exec 会被整组 kill。 */
    fun delete(id: String) {
        if (id == runningId) return
        Conversations.delete(id)
        Thread { agentd.close(id) }.start()
    }

    private fun start(id: String, prompt: String?, assisted: Boolean = false) {
        running = true
        runningId = id
        stopped = false
        Conversations.select(id)
        executor.execute { run(id, prompt, assisted) }
    }

    private fun run(id: String, prompt: String?, assisted: Boolean) {
        var conversation: Conversation? = null
        val assist = if (assisted) AssistRun(assistPanel, id, resources) else null
        try {
            val c = Conversations.open(id, prompt).also { conversation = it }
            AgentLoop.begin(c, prompt ?: c.lastUserText()?.takeIf { c.endsWithReply() })
            val (config, key) = SettingsStore(this).require()
            val provider = ResponsesProvider(config, key)
            // root shell 只在用户开启时提供，也只有这时才打开 agentd session（ADR-0029 第 4 条）。
            val shell = if (config.rootShell) Executor(agentd.session(id), agentd.agentd()) else null
            val files = FileOps()
            // 经观察窗的装饰器：以虚拟屏幕为目标的调用会发气泡（ADR-0031 第 5 条），工具和 JS 不知道它的存在。
            val watched = checkNotNull(ScreenWatch.instance)
            // 助手面板发起的运行再套一层：主屏调用前收起面板、显示状态胶囊（ADR-0033 第 5 条）。
            val connect: () -> IAgentScreen = {
                val w = WatchedScreen(screen.connected(), watched, id)
                assist?.let { AssistedScreen(w, it) } ?: w
            }
            val tools = ToolRegistry.forRun(config.rootShell)
            // exec_js 的 tool() 与模型使用同一套工具和上下文。
            lateinit var context: ToolContext
            val scripts = ScriptRunner(shell, files, connect, { script(id) }) { name, args -> tools.invoke(name, args, context) }
            context = ToolContext(shell, files, connect, device::await, scripts)
            val loop = AgentLoop(c, provider, tools, context, { Prompts.instructions(tools.groupDescriptions, assisted) }, config.contextWindow)
                .also { loop = it }
            if (stopped) loop.stop()
            loop.run({ draft += it }, { draft = "" })
        } catch (e: Exception) {
            // 还没进入 loop 就失败（设置不完整、agentd 不可用等）：用户的消息已由 begin 保存，可以重试。
            conversation?.setFailure(AgentFailure.of(e))
        } finally {
            ScreenWatch.instance?.dispatch(ScreenWatch.Event.RunEnded(id))
            loop = null
            draft = ""
            runningId = null
            running = false
            assist?.end(screen::bound)
        }
    }

    private fun script(tag: String): ScriptEngine {
        script?.let { if (scriptTag == tag) return it; it.close() }
        return ScriptEngine(this).also { script = it; scriptTag = tag }
    }

    /** 助手面板（ADR-0033 第 5 条），由 assist 包的系统助手服务注册；service 不依赖 assist。 */
    interface AssistPanel {
        /** 收起正在显示的面板，返回时它已不在屏幕上；面板没有显示时返回 false。不在主线程调用。 */
        fun collapse(): Boolean

        /** 重新弹出面板，显示这个会话。 */
        fun reopen(conversation: String)
    }

    companion object {
        /** 当前的助手面板；还不是系统助手或服务已关闭时为 null。 */
        @Volatile var assistPanel: AssistPanel? = null

        /** 界面据此切换"发送/停止"按钮。 */
        var running by mutableStateOf(false)
            private set

        /** 正在运行的会话。 */
        var runningId by mutableStateOf<String?>(null)
            private set

        /** 正在流式接收、还没写进会话的回复文字。 */
        var draft by mutableStateOf("")
            private set
    }
}
