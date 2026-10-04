package moe.uni.agent.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException
import moe.uni.agent.conversation.Conversations
import moe.uni.agent.service.AgentService

/** 唯一的 Activity：会话列表、会话、设置、任务四个页面（ADR-0026 第 3 条、ADR-0032）。只和 AgentService 通信（ADR-0020）。 */
class MainActivity : ComponentActivity() {
    private var service by mutableStateOf<AgentService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as AgentService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    /** 观察窗的气泡要用通知权限（ADR-0031 第 6 条）；结果不用处理，拒绝时发气泡失败，会话页给提示。 */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 绑定是异步的，界面先读会话列表。
        Conversations.init(applicationContext)
        // 重建时 intent 还是原来那个，不再切换会话。
        if (savedInstanceState == null) select(intent)
        savedInstanceState?.getString(STATE_PAGE)?.let { page = Page.valueOf(it) }
        // 先作为 started service 启动，界面离开后 Service 仍在常驻进程里运行。
        startService(Intent(this, AgentService::class.java))
        bindService(Intent(this, AgentService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent { AgentTheme { App() } }
    }

    /** 当前页面。放在 Activity 里，观察窗的"打开对话"（onNewIntent）也能切到会话页；重建时从 savedInstanceState 恢复。 */
    private var page by mutableStateOf(Page.Chat)

    @Composable
    private fun App() {
        BoxWithConstraints {
            val wide = maxWidth >= Layout.ExpandedWidth
            // 宽窗口上列表和会话并排，是同一个页面。
            val screen = if (wide && page == Page.Chat) Page.List else page
            PageHost(screen, onBack = { page = Page.List }) { current ->
                when (current) {
                    Page.List -> if (wide) ListDetail() else ConversationsPane(
                        service, onOpen = { page = Page.Chat }, onTasks = { page = Page.Tasks }, onSettings = { page = Page.Settings },
                    )
                    Page.Chat -> ChatScreen(service, onBack = { page = Page.List }, onSend = ::send)
                    Page.Settings -> SettingsScreen(onBack = { page = Page.List })
                    Page.Tasks -> TasksScreen(service, onBack = { page = Page.List })
                }
            }
        }
    }

    /**
     * 宽窗口（ADR-0032 第 2 条）：左边会话列表，右边会话页。新建会话由列表的 FAB 负责，会话页顶栏不再放"新对话"和返回。
     */
    @Composable
    private fun ListDetail() {
        Row(Modifier.fillMaxSize()) {
            ConversationsPane(
                service, onOpen = { page = Page.Chat }, onTasks = { page = Page.Tasks }, onSettings = { page = Page.Settings },
                modifier = Modifier.width(LIST_PANE_WIDTH).fillMaxHeight(),
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1f)) { ChatScreen(service, onBack = null, onSend = ::send, showNew = false) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PAGE, page.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        select(intent)
    }

    /** 观察窗的"打开对话"带来的会话：选中并切到会话页。 */
    private fun select(intent: Intent) {
        val id = intent.getStringExtra(EXTRA_CONVERSATION) ?: return
        Conversations.select(id)
        page = Page.Chat
    }

    /**
     * 发送消息。第一次发送时若没有通知权限就用系统对话框请求，拒绝也照常发送（ADR-0031 第 6 条）。
     * 被拒绝过一次后 shouldShowRequestPermissionRationale 为 true，不再请求。
     */
    private fun send(prompt: String) {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED && !shouldShowRequestPermissionRationale(permission)) {
            notificationPermission.launch(permission)
        }
        service?.send(prompt)
    }

    override fun onDestroy() {
        unbindService(connection)
        super.onDestroy()
    }

    /** 列表-详情（ADR-0032 第 2 条）：启动时直接进入当前会话，返回到会话列表；设置、任务从列表进入。 */
    private enum class Page(val depth: Int) { List(0), Chat(1), Settings(1), Tasks(1) }

    /**
     * 页面切换（ADR-0032 第 4 条）：从列表进入会话、设置、任务用 X 轴共享轴过渡，返回时反向；返回手势拖动时当前页随进度缩小、
     * 向手势一侧移动并变成圆角（预测性返回），松手后才真正返回，取消则复原。
     */
    @Composable
    private fun PageHost(page: Page, onBack: () -> Unit, content: @Composable (Page) -> Unit) {
        var progress by remember { mutableFloatStateOf(0f) }
        var fromLeft by remember { mutableStateOf(true) }
        PredictiveBackHandler(enabled = page != Page.List) { events ->
            try {
                events.collect {
                    progress = BACK_EASING.transform(it.progress)
                    fromLeft = it.swipeEdge == BackEventCompat.EDGE_LEFT
                }
                onBack()
            } catch (e: CancellationException) {
                progress = 0f
                throw e
            }
        }
        // 返回完成后退出的页面保持松手时的样子，过渡结束再复位。
        val transition = updateTransition(page, label = "page")
        LaunchedEffect(transition.currentState, transition.targetState) {
            if (transition.currentState == transition.targetState) progress = 0f
        }
        val motion = MaterialTheme.motionScheme
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
            transition.AnimatedContent(
                transitionSpec = {
                    val forward = targetState.depth > initialState.depth
                    val sign = if (forward) 1 else -1
                    val slide = motion.defaultSpatialSpec<IntOffset>()
                    val fade = motion.defaultEffectsSpec<Float>()
                    (slideInHorizontally(slide) { sign * it / SHARED_AXIS_FRACTION } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(slide) { -sign * it / SHARED_AXIS_FRACTION } + fadeOut(fade))
                },
            ) { current ->
                val back = if (current == Page.List) Modifier else Modifier.graphicsLayer {
                    val p = progress
                    val scale = 1f - BACK_SCALE * p
                    scaleX = scale
                    scaleY = scale
                    translationX = (if (fromLeft) 1f else -1f) * (size.width / BACK_SHIFT_DIVISOR - BACK_MARGIN.toPx()) * p
                    shape = RoundedCornerShape(BACK_CORNER * p)
                    clip = p > 0f
                }
                Box(Modifier.fillMaxSize().then(back)) { content(current) }
            }
        }
    }

    companion object {
        /** 要打开的会话 id（MirrorActivity 的"打开对话"，ADR-0031 第 2 条）。 */
        const val EXTRA_CONVERSATION = "moe.uni.agent.extra.CONVERSATION"

        private const val STATE_PAGE = "page"

        /** 宽窗口上列表一侧的宽度（Material 列表-详情布局的固定宽度窗格）。 */
        private val LIST_PANE_WIDTH = 360.dp

        /** 共享轴过渡移动页面宽度的 1/10。 */
        private const val SHARED_AXIS_FRACTION = 10

        /** 预测性返回：最多缩小到 90%，向手势一侧移动 宽度/20 - 8dp，圆角到 28dp（Material 的应用内预测性返回）。 */
        private const val BACK_SCALE = 0.1f
        private const val BACK_SHIFT_DIVISOR = 20f
        private val BACK_MARGIN = 8.dp
        private val BACK_CORNER = 28.dp
        private val BACK_EASING = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
    }
}
