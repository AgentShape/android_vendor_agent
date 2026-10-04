package moe.uni.agent.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import moe.uni.agent.R
import moe.uni.agent.conversation.Conversations
import moe.uni.agent.service.AgentService

/**
 * 会话列表（ADR-0032 第 2、5 条，列表-详情布局的列表一侧）：顶栏是应用名和"任务""设置"，下面按时间分组的会话，
 * 右下角"新对话"。手机上是单独的一页，宽窗口上和会话页并排。打开或新建会话后调用 [onOpen]。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConversationsPane(
    service: AgentService?,
    onOpen: () -> Unit,
    onTasks: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onTasks) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.hook_tasks)) }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.hook_settings)) }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // 测试钩子 new：驱动脚本启动后先点 new，app 停在列表页时也要找得到。手机上列表和会话页不会同时显示。
            val hookNew = stringResource(R.string.hook_new)
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.new_chat)) },
                icon = { Icon(Icons.Filled.Add, null) },
                onClick = { service?.newConversation(); onOpen() },
                modifier = Modifier.semantics { contentDescription = hookNew },
            )
        },
    ) { padding ->
        ConversationList(service, onOpen, Modifier.padding(padding).fillMaxSize())
    }
}

/** 按更新时间分组的会话：每组是一段分段列表，当前会话 secondaryContainer。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversationList(service: AgentService?, onOpen: () -> Unit, modifier: Modifier) {
    val list by remember { Conversations.list() }.collectAsState(initial = emptyList())
    val current = Conversations.current.value
    val runningId = AgentService.runningId
    val groups = remember(list) { group(list) }
    var renaming by remember { mutableStateOf<Conversations.Summary?>(null) }
    var deleting by remember { mutableStateOf<Conversations.Summary?>(null) }
    val colors = MaterialTheme.colorScheme

    if (list.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            EmptyState(Glyphs.Description, stringResource(R.string.no_conversations), null, Modifier.padding(Spacing.xxl))
        }
    } else {
        // 列表按更新时间倒序。LazyColumn 按 key 保持位置，最新的会话换了（新建、在别的会话里发送）时新的那项会被挤到
        // 可见区上方，所以回到顶部。
        val listState = rememberLazyListState()
        val newest = list.firstOrNull()?.id
        LaunchedEffect(newest) { listState.scrollToItem(0) }
        LazyColumn(
            modifier,
            state = listState,
            // 底部留出 FAB 的位置。
            contentPadding = PaddingValues(start = Spacing.l, end = Spacing.l, bottom = FAB_CLEARANCE),
        ) {
            for ((group, items) in groups) {
                item(key = group) {
                    Text(
                        stringResource(group.label), style = MaterialTheme.typography.titleSmall, color = colors.primary,
                        modifier = Modifier.padding(start = Spacing.l, top = Spacing.l, bottom = Spacing.s),
                    )
                }
                itemsIndexed(items, key = { _, it -> it.id }) { i, summary ->
                    ConversationItem(
                        summary, segmentShape(i, items.size), selected = summary.id == current, running = summary.id == runningId,
                        onOpen = { service?.open(summary.id); onOpen() },
                        onRename = { renaming = summary },
                        onDelete = { deleting = summary },
                        modifier = Modifier.padding(top = if (i > 0) SegmentGap else 0.dp),
                    )
                }
            }
        }
    }

    renaming?.let { summary ->
        RenameDialog(summary.title, onDismiss = { renaming = null }) {
            service?.rename(summary.id, it)
            renaming = null
        }
    }
    deleting?.let { summary ->
        val context = LocalContext.current
        val refused = stringResource(R.string.delete_running)
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, summary.title.ifEmpty { stringResource(R.string.untitled) })) },
            confirmButton = {
                TextButton(onClick = {
                    if (summary.id == AgentService.runningId) Toast.makeText(context, refused, Toast.LENGTH_SHORT).show()
                    else service?.delete(summary.id)
                    deleting = null
                }) { Text(stringResource(R.string.delete), color = colors.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * 一个会话：标题和更新时间。点击打开；右侧"更多"或长按弹出重命名、删除。正在运行的会话在"更多"前显示加载指示器。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConversationItem(
    summary: Conversations.Summary,
    shape: Shape,
    selected: Boolean,
    running: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val more = stringResource(R.string.more)
    ListItem(
        selected = selected,
        onClick = onOpen,
        onLongClick = { menu = true },
        onLongClickLabel = more,
        shapes = ListItemDefaults.shapes(shape = shape, selectedShape = shape),
        colors = ListItemDefaults.colors(
            containerColor = colors.surfaceContainer,
            selectedContainerColor = colors.secondaryContainer,
            selectedContentColor = colors.onSecondaryContainer,
            selectedSupportingContentColor = colors.onSecondaryContainer,
            selectedTrailingContentColor = colors.onSecondaryContainer,
        ),
        contentPadding = PaddingValues(start = Spacing.l, end = Spacing.xs, top = Spacing.m, bottom = Spacing.m),
        supportingContent = { Text(updatedText(summary.updated)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (running) {
                    val label = stringResource(R.string.running)
                    LoadingIndicator(Modifier.size(RUNNING_INDICATOR).semantics { contentDescription = label })
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, more) }
                    ConversationMenu(menu, running, onDismiss = { menu = false }, onRename = onRename, onDelete = onDelete)
                }
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(summary.title.ifEmpty { stringResource(R.string.untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConversationMenu(expanded: Boolean, running: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.rename)) },
            leadingIcon = { Icon(Icons.Filled.Edit, null) },
            onClick = { onDismiss(); onRename() },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete)) },
            leadingIcon = { Icon(Icons.Filled.Delete, null) },
            // 正在运行的会话不能删除，用默认的禁用样式。
            colors = if (running) MenuDefaults.itemColors() else MenuDefaults.itemColors(
                textColor = colors.error, leadingIconColor = colors.error,
            ),
            enabled = !running,
            onClick = { onDismiss(); onDelete() },
        )
    }
}

@Composable
private fun RenameDialog(title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    // 打开时聚焦并全选原标题，直接输入即可替换。
    var text by remember { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Edit, null) },
        title = { Text(stringResource(R.string.rename_title)) },
        text = {
            OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus))
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.text) }, enabled = text.text.isNotBlank()) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** 更新时间：今天的显示时刻，更早的显示日期。 */
@Composable
private fun updatedText(updated: Long): String {
    val context = LocalContext.current
    val today = DateUtils.isToday(updated)
    return DateUtils.formatDateTime(context, updated, if (today) DateUtils.FORMAT_SHOW_TIME else DateUtils.FORMAT_SHOW_DATE)
}

/** 运行中的指示器，和图标一样大。 */
private val RUNNING_INDICATOR = 24.dp

/** 列表底部留给 Extended FAB（56dp 高、16dp 边距）的空间。 */
private val FAB_CLEARANCE = 88.dp

/** 按更新时间分组：今天、昨天、7 天内、更早。list 已按更新时间倒序。 */
private enum class Group(val label: Int) {
    TODAY(R.string.group_today), YESTERDAY(R.string.group_yesterday), WEEK(R.string.group_week), EARLIER(R.string.group_earlier)
}

private fun group(list: List<Conversations.Summary>): List<Pair<Group, List<Conversations.Summary>>> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return list.groupBy {
        val day = Instant.ofEpochMilli(it.updated).atZone(zone).toLocalDate()
        when {
            !day.isBefore(today) -> Group.TODAY
            day == today.minusDays(1) -> Group.YESTERDAY
            day.isAfter(today.minusDays(WEEK_DAYS)) -> Group.WEEK
            else -> Group.EARLIER
        }
    }.toList().sortedBy { it.first.ordinal }
}

private const val WEEK_DAYS = 7L
