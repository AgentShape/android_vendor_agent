package moe.uni.agent.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.uni.agent.R
import moe.uni.agent.service.AgentService
import moe.uni.agent.watch.ScreenWatch

/**
 * 助手面板（ADR-0033 第 3 条）：悬浮在当前 app 底部的 surfaceContainerHigh 卡片，外面是一层很淡的遮罩，点遮罩关闭；
 * 屏幕底边一条流动的渐变光带。还没有会话时只有输入框；发送后面板向上长高，显示这个会话（顶栏有"在 App 中继续"和关闭），最高约屏幕的 60%。
 * 状态由助手会话持有：[conversation] 为 null 表示还没发送，[shown] 每次唤起加一，用来重新聚焦输入框。
 */
@Composable
fun AssistPanel(
    service: AgentService?,
    conversation: String?,
    text: String,
    onTextChange: (String) -> Unit,
    shown: Int,
    onSend: (String) -> Unit,
    onOpenInApp: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        // 遮罩：点了关闭面板，不显示涟漪。
        Box(
            Modifier.fillMaxSize().background(colors.scrim.copy(alpha = SCRIM_ALPHA))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
        GlowBand(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(GLOW_HEIGHT))
        Surface(
            color = colors.surfaceContainerHigh,
            shape = MaterialTheme.shapes.extraLarge,
            // 顶部不进状态栏：会话很长、又弹出键盘时，面板高度以剩下的空间为限，消息列表跟着缩短。
            modifier = Modifier.align(Alignment.BottomCenter).statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(start = Spacing.l, end = Spacing.l, top = Spacing.l, bottom = Spacing.l)
                .widthIn(max = Layout.PageMaxWidth).fillMaxWidth(),
        ) {
            Column {
                if (conversation != null) Conversation(service, conversation, onOpenInApp, onDismiss)
                Prompt(service, conversation, text, onTextChange, shown, onSend)
            }
        }
    }
}

/** 面板里的会话：顶栏（标题、在 App 中继续、关闭）和消息列表。消息列表最高约屏幕的 60%，空间不够时再缩短。 */
@Composable
private fun ColumnScope.Conversation(service: AgentService?, id: String, onOpenInApp: (String) -> Unit, onDismiss: () -> Unit) {
    val state by remember(id) { observe(id) }.collectAsState(initial = null)
    val running = AgentService.running && AgentService.runningId == id
    val watchProblem = (ScreenWatch.instance?.state?.value as? ScreenWatch.State.Idle)?.unavailable?.takeIf { running }
    Row(Modifier.fillMaxWidth().padding(start = Spacing.xl, end = Spacing.xs, top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(
            state?.title.orEmpty().ifEmpty { stringResource(R.string.app_name) }, style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        val hookOpen = stringResource(R.string.hook_assist_open)
        IconButton(onClick = { onOpenInApp(id) }, modifier = Modifier.semantics { contentDescription = hookOpen }) {
            Icon(Glyphs.OpenInNew, null)
        }
        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, stringResource(R.string.close)) }
    }
    val s = state ?: return
    val maxHeight = (LocalConfiguration.current.screenHeightDp * MAX_HEIGHT_FRACTION).dp
    Messages(
        s.items, s.notice, running, watchProblem, onRetry = { service?.retry() },
        modifier = Modifier.weight(1f, fill = false).fillMaxWidth().heightIn(max = maxHeight), fill = false,
        hook = R.string.hook_assist_result,
    )
}

/** 输入框：唤起后自动聚焦并弹出键盘。另一个会话在运行时（面板显示的就是它）只能停止。 */
@Composable
private fun Prompt(service: AgentService?, conversation: String?, text: String, onTextChange: (String) -> Unit, shown: Int, onSend: (String) -> Unit) {
    val runningHere = AgentService.running && AgentService.runningId == conversation
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(shown) {
        focus.requestFocus()
        keyboard?.show()
    }
    PromptField(
        text, onTextChange, stringResource(R.string.assist_hint), runningHere, sendEnabled = !AgentService.running,
        onSend = onSend, onStop = { service?.stop() }, hooks = PromptHooks.Assist,
        textModifier = Modifier.focusRequester(focus),
    )
}

/**
 * 唤起光带（ADR-0033 第 3 条）：屏幕底边一条横向流动的渐变，向上淡出。配色是 ADR-0023 流光的那组颜色，作为助手的标志，
 * 不随主题变化。首尾同色，平铺后循环没有接缝。
 */
@Composable
private fun GlowBand(modifier: Modifier) {
    val shift by rememberInfiniteTransition(label = "glow").animateFloat(
        0f, 1f, infiniteRepeatable(tween(GLOW_PERIOD_MS, easing = LinearEasing)), label = "shift",
    )
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val start = -size.width * shift
        drawRect(Brush.horizontalGradient(GLOW_COLORS, startX = start, endX = start + size.width, tileMode = TileMode.Repeated))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = GLOW_ALPHA))), blendMode = BlendMode.DstIn)
    }
}

private val GLOW_COLORS = listOf(
    Color(0xFFBC82F3), Color(0xFFF5B9EA), Color(0xFF8D9FFF), Color(0xFFFF6778), Color(0xFFFFBA71), Color(0xFFC686FF), Color(0xFFBC82F3),
)
private val GLOW_HEIGHT = 96.dp
private const val GLOW_ALPHA = 0.7f
private const val GLOW_PERIOD_MS = 6_000

/** 遮罩很淡：用户仍能看清下面的 app（ADR-0033 第 3 条）。 */
private const val SCRIM_ALPHA = 0.2f

/** 面板里的会话最高占屏幕高度的比例。 */
private const val MAX_HEIGHT_FRACTION = 0.6f
