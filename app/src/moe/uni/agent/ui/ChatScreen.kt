package moe.uni.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import moe.uni.agent.R
import moe.uni.agent.conversation.ChatItem
import moe.uni.agent.conversation.Conversations
import moe.uni.agent.service.AgentService
import moe.uni.agent.tools.Tool
import moe.uni.agent.tools.ToolRegistry
import moe.uni.agent.watch.ScreenWatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.json.JSONObject

/**
 * 会话页（ADR-0026 第 3 条、ADR-0032 第 6 条）：顶栏、消息列表、输入区。宽窗口上消息和输入区限宽居中。
 * 手机上顶栏左边是返回会话列表；宽窗口上列表就在旁边，[onBack] 为 null，也不显示"新对话"（[showNew]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(service: AgentService?, onBack: (() -> Unit)?, onSend: (String) -> Unit, showNew: Boolean = true) {
    val id = Conversations.current.value
    // 在第一次读到之前为 null：不显示问候语，避免切换会话时闪一下空白页。
    val state by remember(id) { observe(id) }.collectAsState(initial = null)
    val title = state?.title.orEmpty()
    val items = state?.items.orEmpty()
    val notice = state?.notice
    val runningHere = AgentService.running && AgentService.runningId == id
    val busyElsewhere = AgentService.running && !runningHere
    // 观察窗发不出气泡的原因（ADR-0031 第 6 条），只在运行中的会话里提示。
    val watchProblem = (ScreenWatch.instance?.state?.value as? ScreenWatch.State.Idle)?.unavailable?.takeIf { runningHere }
    // 消息列表滚到顶栏下面时顶栏换成 surfaceContainer 色调。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = {
                    // 测试钩子沿用 menu：驱动脚本按 menu → settings/tasks 进入设置和任务，流程不变。
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.hook_menu)) }
                    }
                },
                title = { Text(title.ifEmpty { stringResource(R.string.app_name) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    if (showNew) {
                        IconButton(onClick = { service?.newConversation() }) { Icon(Icons.Filled.Add, stringResource(R.string.hook_new)) }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            InputBar(
                runningHere = runningHere, busyElsewhere = busyElsewhere,
                onSend = onSend, onStop = { service?.stop() },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state == null) {
                // 读取中
            } else if (items.isEmpty() && notice == null) {
                EmptyState(
                    Glyphs.SmartToy, stringResource(R.string.greeting), stringResource(R.string.greeting_detail),
                    Modifier.align(Alignment.Center).widthIn(max = Layout.PageMaxWidth).padding(Spacing.xxl),
                )
            } else {
                // 换了会话就重建列表状态，打开时在底部。
                key(id) {
                    Messages(items, notice, runningHere, watchProblem, onRetry = { service?.retry() })
                }
            }
        }
    }
}

/** 列表下方的失败提示：文字来自 strings.xml，detail 另起一行；会话本身读不出来时不能重试。 */
internal data class Notice(@param:StringRes val message: Int, val detail: String, val retry: Boolean)

internal data class ConversationState(val title: String, val items: List<ChatItem>, val notice: Notice?)

private const val TAG = "ChatScreen"

/** 订阅会话的变化（ADR-0027 第 3 条）；新对话时是空的。读取出错时显示提示，原始异常写日志。 */
internal fun observe(id: String?): Flow<ConversationState> {
    if (id == null) return flowOf(ConversationState("", emptyList(), null))
    return Conversations.observe(id).map { view ->
        ConversationState(view.title, view.items, view.failure?.let { Notice(it.reason.message(), it.visibleDetail, retry = true) })
    }.catch {
        Log.w(TAG, "read $id failed", it)
        emit(ConversationState("", emptyList(), Notice(R.string.read_failed, "", retry = false)))
    }
}

/** 连续的工具调用归成一组。 */
private sealed interface Entry {
    data class Message(val item: ChatItem) : Entry
    data class Tools(val tools: List<ChatItem.Tool>) : Entry
}

private fun entries(items: List<ChatItem>): List<Entry> {
    val out = ArrayList<Entry>()
    var group = ArrayList<ChatItem.Tool>()
    for (item in items) {
        if (item is ChatItem.Tool) {
            group.add(item)
            continue
        }
        if (group.isNotEmpty()) out.add(Entry.Tools(group)).also { group = ArrayList() }
        out.add(Entry.Message(item))
    }
    if (group.isNotEmpty()) out.add(Entry.Tools(group))
    return out
}

/**
 * 消息列表。会话页铺满（[fill]），宽窗口上两侧留白；助手面板里按内容高度、最高到外面给的约束（[fill] 为 false）。
 * [hook] 是列表的测试钩子：会话页 result，助手面板 assist_result。
 */
@Composable
internal fun Messages(
    items: List<ChatItem>, notice: Notice?, running: Boolean, watchProblem: ScreenWatch.Event.Unavailable.Reason?, onRetry: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    fill: Boolean = true,
    @StringRes hook: Int = R.string.hook_result,
) {
    val draft = if (running) AgentService.draft else ""
    val entries = remember(items) { entries(items) }
    val toolRunning = (items.lastOrNull() as? ChatItem.Tool)?.let { it.output == null } ?: false
    val listState = rememberLazyListState()
    // 位于底部时跟随新输出滚动，不在底部时不移动。是否在底部只由用户的滚动（拖动和惯性）决定，
    // 内容变长和程序滚动都不改变它。
    var follow by remember { mutableStateOf(true) }
    val userScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                follow = !listState.canScrollForward
                return Offset.Zero
            }
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                follow = !listState.canScrollForward
                return Velocity.Zero
            }
        }
    }
    // 发出新消息（和打开会话）时回到底部并恢复跟随。
    val sent = items.count { it is ChatItem.User }
    LaunchedEffect(sent) {
        follow = true
        listState.scrollBy(100_000f)
    }
    LaunchedEffect(items, draft, notice, running, watchProblem) {
        if (follow) listState.scrollBy(100_000f)
    }

    val hookResult = stringResource(hook)
    BoxWithConstraints(modifier) {
        // 宽窗口上两侧留白，内容不超过 ChatMaxWidth；留白处也能拖动列表。
        val side = maxOf(Spacing.l, (maxWidth - Layout.ChatMaxWidth) / 2)
        LazyColumn(
            state = listState,
            modifier = (if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth()).nestedScroll(userScroll)
                .semantics { contentDescription = hookResult },
            contentPadding = PaddingValues(horizontal = side, vertical = Spacing.l),
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            items(entries) { entry ->
                when (entry) {
                    is Entry.Message -> when (val item = entry.item) {
                        is ChatItem.User -> UserBubble(item.text)
                        is ChatItem.Assistant -> AssistantReply(item.text, streaming = false)
                        is ChatItem.Tool -> Unit
                    }
                    is Entry.Tools -> ToolGroup(entry.tools, running, last = entry === entries.last())
                }
            }
            if (watchProblem != null) {
                item { WatchHint(watchProblem) }
            }
            if (draft.isNotEmpty()) {
                item { AssistantReply(draft, streaming = true) }
            } else if (running && !toolRunning) {
                // 等模型回复时显示；工具执行中由工具行显示进度。
                item { Thinking() }
            }
            if (!running && notice != null) {
                item { NoticeLine(notice, onRetry) }
            }
        }
    }
}

/** 工具行下方的一行提示：观察窗不可用。 */
@Composable
private fun WatchHint(reason: ScreenWatch.Event.Unavailable.Reason) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Info, null, Modifier.size(SMALL_ICON), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(reason.message()), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

/** 用户消息：靠右的 primaryContainer 气泡，靠近输入区的一角收窄成"尾巴"。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserBubble(text: String) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.largeIncreased.copy(bottomEnd = MaterialTheme.shapes.extraSmall.bottomEnd)
    Row(Modifier.fillMaxWidth().padding(start = BUBBLE_INDENT), horizontalArrangement = Arrangement.End) {
        Surface(color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, shape = shape) {
            SelectionContainer {
                Text(
                    printable(text), Modifier.padding(horizontal = Spacing.l, vertical = Spacing.m),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

/** 助手回复：不加气泡，Markdown；生成中末尾有光标，生成完有"复制"。 */
@Composable
private fun AssistantReply(text: String, streaming: Boolean) {
    val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Markdown(if (streaming) "$text$CURSOR" else text, style)
        }
        if (!streaming) {
            val context = LocalContext.current
            val copied = stringResource(R.string.copied)
            // 图标按钮的触控区是 48dp，向左挪半个内边距，让图标和正文左对齐。
            IconButton(onClick = { copy(context, text, copied) }, modifier = Modifier.offset(x = -Spacing.s)) {
                Icon(Glyphs.Copy, stringResource(R.string.copy), Modifier.size(SMALL_ICON), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private const val CURSOR = " ▍"

private fun copy(context: Context, text: String, toast: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

/** 等模型回复：Expressive 的加载指示器（形状轮换）。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Thinking() {
    val label = stringResource(R.string.thinking)
    LoadingIndicator(Modifier.semantics { contentDescription = label })
}

@Composable
private fun NoticeLine(notice: Notice, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.errorContainer, contentColor = colors.onErrorContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, null)
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(stringResource(notice.message), style = MaterialTheme.typography.bodyMedium)
                if (notice.detail.isNotEmpty()) {
                    Text(
                        printable(notice.detail), style = MaterialTheme.typography.bodySmall, maxLines = DETAIL_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (notice.retry) {
                Spacer(Modifier.width(Spacing.s))
                TextButton(onClick = onRetry, colors = ButtonDefaults.textButtonColors(contentColor = colors.onErrorContainer)) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}

private const val DETAIL_LINES = 3

/**
 * 一组连续的工具调用，画成 Expressive 的分段列表：多于一个时第一段是组标题，可以整体折叠；最后一组在运行中默认展开。
 */
@Composable
private fun ToolGroup(tools: List<ChatItem.Tool>, running: Boolean, last: Boolean) {
    val active = running && last
    if (tools.size == 1) {
        ToolRow(tools[0], active, segmentShape(0, 1))
        return
    }
    var toggled by rememberSaveable(tools[0].id) { mutableStateOf<Boolean?>(null) }
    val expanded = toggled ?: active
    val count = if (expanded) tools.size + 1 else 1
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().animateContentSize(motion.defaultSpatialSpec()),
        verticalArrangement = Arrangement.spacedBy(SegmentGap),
    ) {
        Surface(color = colors.surfaceContainer, shape = segmentShape(0, count), modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().clickable { toggled = !expanded }.heightIn(min = TOOL_ROW_HEIGHT)
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Build, null, Modifier.size(SMALL_ICON), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.m))
                Text(
                    stringResource(R.string.tool_group, tools.size), style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface, modifier = Modifier.weight(1f),
                )
                if (active && tools.last().output == null) {
                    ToolProgress()
                    Spacer(Modifier.width(Spacing.s))
                }
                val rotation by animateFloatAsState(if (expanded) 180f else 0f, motion.fastSpatialSpec(), label = "chevron")
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.rotate(rotation), tint = colors.onSurfaceVariant)
            }
        }
        if (expanded) {
            tools.forEachIndexed { i, tool -> ToolRow(tool, active, segmentShape(i + 1, count)) }
        }
    }
}

/** 一个工具调用：图标、工具名和摘要（两行）、状态，点开看参数和结果。 */
@Composable
private fun ToolRow(tool: ChatItem.Tool, active: Boolean, shape: Shape) {
    var expanded by rememberSaveable(tool.id) { mutableStateOf(false) }
    val definition = ToolRegistry.CATALOG.find(tool.name)
    val summary = remember(tool.name, tool.arguments) {
        val args = runCatching { JSONObject(tool.arguments) }.getOrNull()
        printable(args?.let { definition?.summary(it) }.orEmpty())
    }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.heightIn(min = TOOL_ROW_HEIGHT)
                    .padding(horizontal = Spacing.l, vertical = Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(kindIcon(definition?.kind), null, Modifier.size(SMALL_ICON), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text(tool.name, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
                    if (summary.isNotEmpty()) {
                        Text(
                            summary, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                            color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(Spacing.m))
                ToolStatus(tool, active)
            }
            if (expanded) {
                Column(Modifier.padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.l)) {
                    Section(stringResource(R.string.tool_arguments), pretty(tool.arguments))
                    tool.output?.let { Section(stringResource(R.string.tool_result), pretty(it)) }
                }
            }
        }
    }
}

@Composable
private fun ToolStatus(tool: ChatItem.Tool, active: Boolean) {
    val output = tool.output
    when {
        output == null && active -> ToolProgress()
        tool.ok == true -> Icon(
            Icons.Filled.CheckCircle, stringResource(R.string.tool_ok), Modifier.size(SMALL_ICON), tint = MaterialTheme.colorScheme.primary,
        )
        else -> Icon(Icons.Filled.Warning, stringResource(R.string.tool_failed), Modifier.size(SMALL_ICON), tint = MaterialTheme.colorScheme.error)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolProgress() {
    val label = stringResource(R.string.tool_running)
    LoadingIndicator(Modifier.size(TOOL_PROGRESS).semantics { contentDescription = label })
}

/** 模型调用了不存在的工具时 kind 为 null。 */
private fun kindIcon(kind: Tool.Kind?): ImageVector = when (kind) {
    Tool.Kind.SHELL -> Glyphs.Terminal
    Tool.Kind.FILE -> Glyphs.Description
    Tool.Kind.SCREEN -> Glyphs.Smartphone
    Tool.Kind.DEVICE -> Icons.Filled.Settings
    Tool.Kind.SCRIPT -> Glyphs.Code
    null -> Icons.Filled.Build
}

@Composable
private fun Section(label: String, text: String) {
    Spacer(Modifier.size(Spacing.s))
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.size(Spacing.xs))
    SelectionContainer { CodeBlock(printable(text.take(SECTION_MAX))) }
}

private const val SECTION_MAX = 8000

/** JSON 结果缩进显示；多行的字符串字段（节点树、命令输出等）拿出来按原文放在后面。 */
private fun pretty(text: String): String {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return text
    val blocks = json.keys().asSequence().toList().mapNotNull { key -> (json.opt(key) as? String)?.takeIf { '\n' in it }?.let { key to it } }
    blocks.forEach { json.remove(it.first) }
    // org.json 会把 / 转义成 \/，显示时还原。
    return (listOf(json.toString(2).replace("\\/", "/")) + blocks.map { (key, value) -> "$key:\n$value" }).joinToString("\n\n")
}

/** 输入区：输入框贴底，宽窗口上限宽居中。别的会话在运行时不能发送，输入框上方给出原因。 */
@Composable
private fun InputBar(runningHere: Boolean, busyElsewhere: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding().imePadding()
            .padding(start = Spacing.l, end = Spacing.l, top = Spacing.s, bottom = Spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = Layout.ChatMaxWidth).fillMaxWidth()) {
            if (busyElsewhere) {
                Row(Modifier.padding(start = Spacing.l, bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, Modifier.size(SMALL_ICON), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.other_running), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                }
            }
            PromptField(
                text, { text = it }, stringResource(R.string.input_hint), runningHere, sendEnabled = !busyElsewhere,
                onSend = { onSend(it); text = "" }, onStop = onStop, hooks = PromptHooks.Chat,
            )
        }
    }
}

/** 输入框和发送、停止按钮的测试钩子：会话页和助手面板各用一套，同时显示时不重复。 */
internal enum class PromptHooks(@param:StringRes val prompt: Int, @param:StringRes val send: Int, @param:StringRes val stop: Int) {
    Chat(R.string.hook_prompt, R.string.hook_send, R.string.hook_stop),
    Assist(R.string.hook_assist_prompt, R.string.hook_assist_send, R.string.hook_assist_stop),
}

/**
 * 胶囊输入框：surfaceContainerHigh 的 extraLarge 圆角，右侧是填充图标按钮——发送（向上箭头）或停止（方块），
 * 两者切换时淡入并放大。发送后收起键盘，清空由调用方在 [onSend] 里做。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PromptField(
    text: String,
    onTextChange: (String) -> Unit,
    hint: String,
    runningHere: Boolean,
    sendEnabled: Boolean,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    hooks: PromptHooks,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
) {
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge, modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = Spacing.xl, end = Spacing.s, top = Spacing.s, bottom = Spacing.s),
            verticalAlignment = Alignment.Bottom,
        ) {
            val hookPrompt = stringResource(hooks.prompt)
            Box(Modifier.weight(1f).heightIn(min = INPUT_MIN_HEIGHT).padding(vertical = Spacing.s), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(hint, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    maxLines = 6,
                    modifier = textModifier.fillMaxWidth().semantics { contentDescription = hookPrompt },
                )
            }
            Spacer(Modifier.width(Spacing.s))
            val motion = MaterialTheme.motionScheme
            AnimatedContent(
                runningHere,
                transitionSpec = {
                    val fade = motion.fastEffectsSpec<Float>()
                    (fadeIn(fade) + scaleIn(motion.fastSpatialSpec(), initialScale = SWAP_SCALE)) togetherWith fadeOut(fade)
                },
                label = "send",
            ) { running ->
                if (running) {
                    val hookStop = stringResource(hooks.stop)
                    FilledIconButton(
                        onClick = onStop, shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()).semantics { contentDescription = hookStop },
                    ) {
                        Icon(Glyphs.Stop, null)
                    }
                } else {
                    val hookSend = stringResource(hooks.send)
                    FilledIconButton(
                        onClick = {
                            onSend(text.trim())
                            focus.clearFocus()
                        },
                        shapes = IconButtonDefaults.shapes(),
                        enabled = text.isNotBlank() && sendEnabled,
                        modifier = Modifier.size(IconButtonDefaults.smallContainerSize()).semantics { contentDescription = hookSend },
                    ) {
                        Icon(Glyphs.ArrowUp, null)
                    }
                }
            }
        }
    }
}

/** 小图标（工具行、提示行、复制）。 */
private val SMALL_ICON = 20.dp

/** 用户气泡左侧至少留出的宽度。 */
private val BUBBLE_INDENT = 48.dp

/** 工具行的最小高度：两行文字，不小于 48dp 触控目标。 */
private val TOOL_ROW_HEIGHT = 56.dp

/** 工具行里的加载指示器。 */
private val TOOL_PROGRESS = 24.dp

/** 发送、停止按钮切换时新按钮从这个比例放大。 */
private const val SWAP_SCALE = 0.6f

/** 输入框文字区的最小高度，和发送按钮一样高。 */
private val INPUT_MIN_HEIGHT = 40.dp
