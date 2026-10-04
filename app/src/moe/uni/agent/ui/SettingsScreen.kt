package moe.uni.agent.ui

import android.app.Activity
import android.util.Log
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.uni.agent.R
import moe.uni.agent.service.AgentService
import moe.uni.agent.settings.SettingsStore

/**
 * 模型端点设置（ADR-0003）。API key 只在这里输入。
 * 分成"模型服务""推理""权限"三组（ADR-0032 第 7 条）：输入框在组标题下，开关和选择项是分段列表。
 * 填写问题显示在对应输入框下，保存结果用 Snackbar。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val config = remember { runCatching { store.config() }.getOrNull() }
    val hasKey = remember { runCatching { store.hasKey() }.getOrDefault(false) }
    var base by rememberSaveable { mutableStateOf(config?.baseUrl.orEmpty()) }
    var model by rememberSaveable { mutableStateOf(config?.model.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var reasoning by rememberSaveable { mutableStateOf(config?.reasoning ?: true) }
    var effort by rememberSaveable { mutableStateOf(config?.effort.orEmpty()) }
    var rootShell by rememberSaveable { mutableStateOf(config?.rootShell ?: false) }
    var contextWindow by rememberSaveable { mutableStateOf((config?.contextWindow ?: SettingsStore.DEFAULT_CONTEXT_WINDOW).toString()) }
    /** 上次保存时的填写问题，改动对应的输入框后清除。 */
    var problem by remember { mutableStateOf<SettingsStore.Problem?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    // 填写 API key 的页面禁止截屏和出现在最近任务的缩略图里。
    val window = (context as Activity).window
    DisposableEffect(Unit) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    fun save() {
        val secret = key
        key = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { store.save(base, model, secret, reasoning, effort, contextWindow, rootShell) }
            }
            result.onSuccess {
                problem = null
                snackbar.showSnackbar(context.getString(R.string.saved))
            }.onFailure {
                if (it is SettingsStore.Invalid) {
                    problem = it.problem
                    // 推理强度是选择项，不会填错；万一出现也要让用户看到。
                    if (it.problem.field() == Field.EFFORT) snackbar.showSnackbar(context.getString(it.problem.message()))
                } else {
                    Log.w(TAG, "save failed", it)
                    snackbar.showSnackbar(context.getString(R.string.save_failed))
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { PageBar(stringResource(R.string.settings), onBack, scrollBehavior) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = Layout.PageMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = Spacing.l, end = Spacing.l, bottom = Spacing.xl),
            ) {
                SectionHeader(R.string.section_model)
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    SettingField(
                        base, { base = it; problem = problem.clearedBy(Field.BASE_URL) }, R.string.base_url,
                        hint = stringResource(R.string.base_url_hint), problem = problem.takeIfFor(Field.BASE_URL),
                    )
                    SettingField(model, { model = it; problem = problem.clearedBy(Field.MODEL) }, R.string.model, problem = problem.takeIfFor(Field.MODEL))
                    SettingField(
                        key, { key = it; problem = problem.clearedBy(Field.KEY) }, R.string.api_key, problem = problem.takeIfFor(Field.KEY),
                        placeholder = if (hasKey) stringResource(R.string.api_key_saved) else null,
                        visualTransformation = PasswordVisualTransformation(), keyboardType = KeyboardType.Password,
                    )
                    SettingField(
                        contextWindow, { contextWindow = it; problem = problem.clearedBy(Field.CONTEXT_WINDOW) }, R.string.context_window,
                        hint = stringResource(R.string.context_window_hint), problem = problem.takeIfFor(Field.CONTEXT_WINDOW),
                        keyboardType = KeyboardType.Number,
                    )
                }

                SectionHeader(R.string.section_reasoning)
                Column(verticalArrangement = Arrangement.spacedBy(SegmentGap)) {
                    SwitchItem(R.string.reasoning, R.string.reasoning_detail, reasoning, { reasoning = it }, index = 0, count = 2)
                    EffortItem(effort, { effort = it }, index = 1, count = 2)
                }

                SectionHeader(R.string.section_permissions)
                SwitchItem(R.string.root_shell, R.string.root_shell_detail, rootShell, { rootShell = it }, index = 0, count = 1)

                Spacer(Modifier.size(Spacing.xl))
                val running = AgentService.running
                Button(
                    onClick = { save() },
                    shapes = ButtonDefaults.shapes(),
                    enabled = !running,
                    contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
                    modifier = Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MediumContainerHeight),
                ) { Text(stringResource(R.string.save), style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
                if (running) {
                    Text(
                        stringResource(R.string.save_running), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.l, top = Spacing.s),
                    )
                }
            }
        }
    }
}

/** 组标题：primary 色的 titleSmall，和列表文字对齐。 */
@Composable
private fun SectionHeader(@StringRes title: Int) {
    Text(
        stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = Spacing.l, top = Spacing.xl, bottom = Spacing.s),
    )
}

/** 输入框：有填写问题时标红并在下面显示问题，否则显示说明。 */
@Composable
private fun SettingField(
    value: String,
    onChange: (String) -> Unit,
    @StringRes label: Int,
    problem: SettingsStore.Problem?,
    hint: String? = null,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val supporting = problem?.let { stringResource(it.message()) } ?: hint
    OutlinedTextField(
        value, onChange, label = { Text(stringResource(label)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = problem != null,
        supportingText = supporting?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

/** 开关项：整行可点，开关只显示状态。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwitchItem(@StringRes title: Int, @StringRes detail: Int, checked: Boolean, onChange: (Boolean) -> Unit, index: Int, count: Int) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        colors = segmentColors(),
        supportingContent = { Text(stringResource(detail)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    ) { Text(stringResource(title)) }
}

/** 推理强度：点开菜单从固定的几个值里选，当前值打勾。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EffortItem(effort: String, onSelect: (String) -> Unit, index: Int, count: Int) {
    var menu by remember { mutableStateOf(false) }
    val defaultLabel = stringResource(R.string.effort_default)
    fun label(value: String) = value.ifEmpty { defaultLabel }
    Box {
        SegmentedListItem(
            onClick = { menu = true },
            shapes = ListItemDefaults.segmentedShapes(index, count),
            colors = segmentColors(),
            supportingContent = { Text(label(effort)) },
            trailingContent = { Icon(Icons.Filled.ArrowDropDown, null) },
        ) { Text(stringResource(R.string.effort)) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for (value in SettingsStore.EFFORTS) {
                DropdownMenuItem(
                    text = { Text(label(value)) },
                    trailingIcon = if (value == effort) ({ Icon(Icons.Filled.Check, null) }) else null,
                    onClick = { menu = false; onSelect(value) },
                )
            }
        }
    }
}

/** 分段列表的底色：页面是 surface，分段用 surfaceContainer 才分得出来。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun segmentColors() = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)

/** 填写问题对应的输入框。 */
private enum class Field { BASE_URL, MODEL, KEY, CONTEXT_WINDOW, EFFORT }

private fun SettingsStore.Problem.field(): Field = when (this) {
    SettingsStore.Problem.BASE_URL_INVALID, SettingsStore.Problem.BASE_URL_NOT_HTTPS -> Field.BASE_URL
    SettingsStore.Problem.MODEL_MISSING -> Field.MODEL
    SettingsStore.Problem.KEY_MISSING -> Field.KEY
    SettingsStore.Problem.CONTEXT_WINDOW_INVALID -> Field.CONTEXT_WINDOW
    SettingsStore.Problem.EFFORT_INVALID -> Field.EFFORT
}

private fun SettingsStore.Problem?.takeIfFor(field: Field) = this?.takeIf { it.field() == field }

private fun SettingsStore.Problem?.clearedBy(field: Field) = this?.takeIf { it.field() != field }

private const val TAG = "SettingsScreen"
