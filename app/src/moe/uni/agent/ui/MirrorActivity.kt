package moe.uni.agent.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import moe.uni.agent.R
import moe.uni.agent.conversation.Conversations
import moe.uni.agent.service.AgentService
import moe.uni.agent.watch.ScreenWatch

/**
 * 观察窗：气泡展开后的镜像 Activity（ADR-0031 第 2 条）。顶部一行会话标题和"打开对话"，下面是虚拟屏幕的镜像。
 * 只由气泡启动，其他方式拉起时直接结束。展开和收起就是 onResume/onStop，上报给状态机。
 */
class MirrorActivity : ComponentActivity() {
    private var service by mutableStateOf<AgentService?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as AgentService.LocalBinder).service
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 防止被外部 intent 拉起：只接受气泡启动的实例。
        if (!isLaunchedFromBubble) {
            finish()
            return
        }
        Conversations.init(applicationContext)
        bindService(Intent(this, AgentService::class.java), connection, Context.BIND_AUTO_CREATE)
        setContent { AgentTheme { Content() } }
    }

    override fun onResume() {
        super.onResume()
        ScreenWatch.instance?.dispatch(ScreenWatch.Event.ViewerShown)
    }

    override fun onStop() {
        // 旋转、深色模式等配置变化会重建 Activity，气泡并没有收起。
        if (!isChangingConfigurations) ScreenWatch.instance?.dispatch(ScreenWatch.Event.ViewerHidden)
        super.onStop()
    }

    override fun onDestroy() {
        if (isLaunchedFromBubble) unbindService(connection)
        super.onDestroy()
    }

    /**
     * 观察窗的界面（ADR-0032 第 10 条）：顶部一行是会话标题、运行中的指示器和"打开对话"（tonal 按钮）；
     * 下面的镜像放在 surfaceContainer 的大圆角容器里，按虚拟屏幕的比例确定尺寸，不留黑边。
     * 连接中显示加载指示器，失败时显示说明。
     */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun Content() {
        // 气泡续用时（新的 loop）Activity 不会重建，所以会话以状态机为准；状态机没有会话时用气泡 intent 里的。
        val session = ScreenWatch.instance?.state?.value?.session ?: intent.getStringExtra(EXTRA_SESSION)
        val title by remember(session) {
            session?.let { id -> Conversations.observe(id).map { it.title }.catch { emit("") } } ?: flowOf("")
        }.collectAsState(initial = "")
        val running = AgentService.running && AgentService.runningId == session
        val colors = MaterialTheme.colorScheme
        Column(Modifier.fillMaxSize().background(colors.surface)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = HEADER_HEIGHT).padding(start = Spacing.l, end = Spacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.ifEmpty { stringResource(R.string.app_name) }, style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (running) {
                    val label = stringResource(R.string.running)
                    LoadingIndicator(Modifier.size(RUNNING_INDICATOR).semantics { contentDescription = label })
                    Spacer(Modifier.width(Spacing.s))
                }
                FilledTonalButton(onClick = { open(session) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Glyphs.OpenInNew, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.watch_open))
                }
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(start = Spacing.m, end = Spacing.m, bottom = Spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                Mirror()
            }
        }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun Mirror() {
        var state by remember { mutableStateOf<MirrorState>(MirrorState.Connecting) }
        val shown = state as? MirrorState.Shown
        val colors = MaterialTheme.colorScheme
        val shape = MaterialTheme.shapes.large
        val radius = with(LocalDensity.current) { shape.topStart.toPx(Size.Unspecified, this) }
        // 尺寸已知前占满可用区域；之后按虚拟屏幕的比例缩小，SurfaceView 随之 surfaceChanged，镜像重新 FIT_CENTER。
        val bounds = if (shown != null && shown.width > 0 && shown.height > 0) {
            Modifier.aspectRatio(shown.width.toFloat() / shown.height)
        } else {
            Modifier.fillMaxSize()
        }
        Box(bounds.clip(shape).background(colors.surfaceContainer), contentAlignment = Alignment.Center) {
            // 连上 AgentService 之后才创建 SurfaceView，surface 创建时就能取到 AgentFramework 连接。
            val s = service
            if (s != null) {
                key(s) {
                    AndroidView(factory = { MirrorView(it, s::mirrorScreen, radius) { state = it } }, modifier = Modifier.fillMaxSize())
                }
            }
            // SurfaceView 在窗口下面，盖在上面的界面能挡住还没有内容的 surface。
            if (shown == null) {
                Box(Modifier.fillMaxSize().background(colors.surfaceContainer), contentAlignment = Alignment.Center) {
                    if (state == MirrorState.Failed) {
                        Text(
                            stringResource(R.string.watch_mirror_failed), style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(Spacing.xl),
                        )
                    } else {
                        val label = stringResource(R.string.watch_connecting)
                        LoadingIndicator(Modifier.semantics { contentDescription = label })
                    }
                }
            }
        }
    }

    /**
     * 打开对话：回到 MainActivity 的任务并选中该会话。没有收起气泡的接口，会话页会被展开的气泡挡住，
     * 所以结束本 Activity：SystemUI 随之移除气泡和通知（DISMISS_TASK_FINISHED），状态机经通知的 deleteIntent 进入 Suppressed。
     */
    private fun open(session: String?) {
        startActivity(
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CONVERSATION, session)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        /** 气泡 intent 里的会话 id（WatchEffects 设置，ADR-0031 第 5 条）。 */
        const val EXTRA_SESSION = "moe.uni.agent.extra.SESSION"

        private val HEADER_HEIGHT = 64.dp
        private val RUNNING_INDICATOR = 24.dp
    }
}
