package moe.uni.agent.ui

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.uni.agent.AgentFailure
import moe.uni.agent.R
import moe.uni.agent.agentd.ExecStatus
import moe.uni.agent.agentd.IAgentd
import moe.uni.agent.agentd.TaskInfo
import moe.uni.agent.service.AgentService

/**
 * 常驻任务列表（ADR-0016）：每项可以终止，终止前确认。经 AgentService 访问 agentd。
 * 下拉或顶栏按钮刷新，失败用 Snackbar 提示（ADR-0032 第 8 条）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TasksScreen(service: AgentService?, onBack: () -> Unit) {
    var tasks by remember { mutableStateOf<List<TaskInfo>?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf<TaskInfo?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun refresh(action: ((IAgentd) -> Unit)? = null) {
        val svc = service ?: return
        scope.launch {
            refreshing = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val agentd = svc.agentd()
                    action?.invoke(agentd)
                    agentd.listTasks().toList()
                }
            }
            refreshing = false
            result.onSuccess { tasks = it }.onFailure {
                Log.w(TAG, "task operation failed", it)
                snackbar.showSnackbar(context.getString(if (it is AgentFailure) it.reason.message() else R.string.task_failed))
            }
        }
    }
    LaunchedEffect(service) { refresh() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            PageBar(stringResource(R.string.tasks), onBack, scrollBehavior) {
                IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.refresh)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = tasks
        PullToRefreshBox(isRefreshing = refreshing && list != null, onRefresh = { refresh() }, modifier = Modifier.padding(padding).fillMaxSize()) {
            if (list == null) {
                LoadingIndicator(Modifier.align(Alignment.Center))
            } else {
                TaskList(list, onStop = { stopping = it })
            }
        }
    }

    stopping?.let { task ->
        AlertDialog(
            onDismissRequest = { stopping = null },
            title = { Text(stringResource(R.string.stop_task_title)) },
            text = { Text(stringResource(R.string.stop_task_message, task.id)) },
            confirmButton = {
                TextButton(onClick = {
                    stopping = null
                    refresh { it.stopTask(task.id) }
                }) { Text(stringResource(R.string.stop_task), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { stopping = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun TaskList(list: List<TaskInfo>, onStop: (TaskInfo) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // 宽窗口上两侧留白，内容不超过 PageMaxWidth。
        val side = maxOf(Spacing.l, (maxWidth - Layout.PageMaxWidth) / 2)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = Spacing.s, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(SegmentGap),
        ) {
            if (list.isEmpty()) {
                // 空列表也放在 LazyColumn 里，才能下拉刷新。
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(Glyphs.Terminal, stringResource(R.string.no_tasks), null)
                    }
                }
            }
            itemsIndexed(list, key = { _, task -> task.id }) { i, task ->
                TaskItem(task, segmentShape(i, list.size)) { onStop(task) }
            }
        }
    }
}

/** 一个任务：状态（运行中是 primary 色）、命令摘要、编号和开始时间，右侧"终止"。 */
@Composable
private fun TaskItem(task: TaskInfo, shape: Shape, onStop: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainer, shape = shape, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stateText(task), style = MaterialTheme.typography.labelMedium, color = stateColor(task))
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    printable(task.summary), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface, maxLines = SUMMARY_LINES, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(Spacing.xs))
                Text(
                    "#${task.id} · ${DateFormat.getDateTimeInstance().format(Date(task.startTimeMs))}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(Spacing.s))
            TextButton(onClick = onStop) { Text(stringResource(R.string.stop_task), color = colors.error) }
        }
    }
}

@Composable
private fun stateText(task: TaskInfo): String {
    val leader = when (task.status.state) {
        ExecStatus.RUNNING -> stringResource(R.string.task_running)
        ExecStatus.EXITED -> stringResource(R.string.task_exited, task.status.exitCode)
        ExecStatus.TIMED_OUT -> stringResource(R.string.task_timed_out)
        else -> stringResource(R.string.task_signaled, task.status.signal)
    }
    return if (task.status.state != ExecStatus.RUNNING && task.alive) stringResource(R.string.task_orphans, leader) else leader
}

/** 运行中 primary；组长已退出但后台还有进程 tertiary；其余 onSurfaceVariant。 */
@Composable
private fun stateColor(task: TaskInfo): Color = when {
    task.status.state == ExecStatus.RUNNING -> MaterialTheme.colorScheme.primary
    task.alive -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private const val SUMMARY_LINES = 3

private const val TAG = "TasksScreen"
