package moe.uni.agent.framework.control

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.ceil
import kotlin.math.floor
import moe.uni.agent.framework.ActionFailure
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.ToolError
import moe.uni.agent.framework.a11y.Directions
import moe.uni.agent.framework.a11y.NodeActions
import moe.uni.agent.framework.a11y.Nodes
import moe.uni.agent.framework.display.Screens
import moe.uni.agent.framework.highlight.Highlight
import moe.uni.agent.framework.highlight.Shape
import moe.uni.agent.framework.input.Injector
import moe.uni.agent.framework.input.Keys
import moe.uni.agent.framework.system.Tasks
import org.json.JSONArray
import org.json.JSONObject

/**
 * 12 个屏幕动作的实现（ADR-0022 第 10、11 条）。每个动作收到已经解析好的屏幕 id，成功时返回动作做了什么
 * （字段见 ActionFields，ADR-0030 第 4 条），失败时抛 ActionFailure。动作开始前画流光效果（ADR-0023）。
 */
class Actions(
    private val context: Context,
    private val screens: Screens,
    private val nodes: Nodes,
    private val tasks: Tasks,
    private val injector: Injector,
    private val highlight: Highlight,
) {
    fun tap(d: Int, element: Int, x: Int, y: Int, count: Int, longPress: Boolean): JSONObject {
        val times = count.coerceAtLeast(1)
        val result = JSONObject()
        if (element <= 0) {
            highlight.show(d, Shape.Ring(x.toFloat(), y.toFloat()))
            injector.tap(d, x.toFloat(), y.toFloat(), times, longPress)
            return result.put(ActionFields.METHOD, ActionFields.METHOD_TOUCH).put(ActionFields.POINT, point(x, y))
                .put(ActionFields.COUNT, times).put(ActionFields.LONG, longPress)
        }
        val node = nodes.element(d, element)
        box(d, node)
        result.put(ActionFields.ELEMENT, element)
        val id = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (times == 1 && node.actionList.any { it.id == id }) {
            val name = NodeActions.STANDARD.getValue(id)
            if (!node.performAction(id)) throw ActionFailure(ToolError.FAILED, "元素 $element 的 $name 返回失败")
            return result.put(ActionFields.METHOD, ActionFields.METHOD_ACCESSIBILITY).put(ActionFields.ACTION, name)
                .put(ActionFields.COUNT, times).put(ActionFields.LONG, longPress)
        }
        // 元素不支持对应的 action，或要连续点击：点它在屏幕内部分的中心（第 9b 条第 3 点）。
        val r = onScreen(d, node, element)
        injector.tap(d, r.exactCenterX(), r.exactCenterY(), times, longPress)
        return result.put(ActionFields.METHOD, ActionFields.METHOD_TOUCH).put(ActionFields.POINT, point(r.centerX(), r.centerY()))
            .put(ActionFields.COUNT, times).put(ActionFields.LONG, longPress)
    }

    fun nodeAction(d: Int, element: Int, action: String): JSONObject {
        val node = nodes.element(d, element)
        box(d, node)
        val wanted = action.trim()
        val match = NodeActions.find(node, wanted) ?: throw ActionFailure(ToolError.UNSUPPORTED,
            "元素 $element 没有 action \"$wanted\"（它有：${node.actionList.joinToString("、") { NodeActions.name(it) }}）")
        if (!node.performAction(match.id)) throw ActionFailure(ToolError.FAILED, "元素 $element 的 ${NodeActions.name(match)} 返回失败")
        // 自定义 action 用它的标签（不加引号）。
        val name = NodeActions.STANDARD[match.id] ?: match.label?.toString() ?: match.id.toString()
        return JSONObject().put(ActionFields.ELEMENT, element).put(ActionFields.ACTION, name)
    }

    fun setText(d: Int, element: Int, text: String): JSONObject {
        val node = nodes.element(d, element)
        box(d, node)
        if (!NodeActions.setText(node, text)) throw ActionFailure(ToolError.FAILED, "元素 $element 的 ACTION_SET_TEXT 返回失败")
        return JSONObject().put(ActionFields.ELEMENT, element).put(ActionFields.LENGTH, text.length)
    }

    fun selectText(d: Int, element: Int, text: String, prefix: String, suffix: String, selection: String): JSONObject {
        val node = nodes.element(d, element)
        box(d, node)
        val content = NodeActions.text(node) ?: throw ActionFailure(ToolError.FAILED, "读不到元素 $element 的文本")
        val needle = prefix + text + suffix
        val at = content.indexOf(needle)
        if (at < 0) throw ActionFailure(ToolError.NOT_FOUND, "元素 $element 的文本中找不到指定内容")
        if (content.indexOf(needle, at + 1) >= 0) throw ActionFailure(ToolError.INVALID_ARGUMENT, "元素 $element 的文本中匹配到多处")
        val start = at + prefix.length
        val end = start + text.length
        val (from, to) = when (selection) {
            SELECTION_TEXT -> start to end
            SELECTION_CURSOR_BEFORE -> start to start
            SELECTION_CURSOR_AFTER -> end to end
            else -> throw ActionFailure(ToolError.INVALID_ARGUMENT,
                "selection 只能是 $SELECTION_TEXT、$SELECTION_CURSOR_BEFORE、$SELECTION_CURSOR_AFTER")
        }
        if (!NodeActions.select(node, from, to)) throw ActionFailure(ToolError.FAILED, "元素 $element 的 ACTION_SET_SELECTION 返回失败")
        return JSONObject().put(ActionFields.ELEMENT, element).put(ActionFields.START, from).put(ActionFields.END, to)
    }

    fun scroll(d: Int, element: Int, direction: String, pages: Float): JSONObject {
        val dir = direction.trim().lowercase()
        if (dir !in Directions.ALL) throw ActionFailure(ToolError.INVALID_ARGUMENT, "direction 只能是 ${Directions.ALL.joinToString("、")}")
        if (pages <= 0f) throw ActionFailure(ToolError.INVALID_ARGUMENT, "pages 必须大于 0")
        val result = JSONObject().put(ActionFields.DIRECTION, dir).put(ActionFields.PAGES, pages.toDouble())
        val area = if (element > 0) {
            val node = nodes.element(d, element)
            box(d, node)
            result.put(ActionFields.ELEMENT, element)
            val action = NodeActions.scrollAction(node, dir)
            // 滚动类 action 一次一页，只用于整数页。
            if (action != null && pages == floor(pages)) {
                val name = NodeActions.STANDARD.getValue(action)
                var done = 0
                while (done < pages.toInt() && node.performAction(action)) done++
                if (done == 0) throw ActionFailure(ToolError.FAILED, "元素 $element 的 $name 返回失败")
                return result.put(ActionFields.METHOD, ActionFields.METHOD_ACCESSIBILITY).put(ActionFields.ACTION, name)
                    .put(ActionFields.SCROLLED, done)
            }
            onScreen(d, node, element)
        } else {
            screens.bounds(d)
        }
        // 没有滚动 action 或页数有小数：在区域内滑动。手指方向与内容滚动方向相反，结束前停顿以免惯性滚动。
        val vertical = dir == Directions.UP || dir == Directions.DOWN
        val extent = if (vertical) area.height() else area.width()
        val total = pages * extent
        val count = ceil(total / (extent * SWIPE_FRACTION)).toInt().coerceAtLeast(1)
        val step = total / count
        val sign = if (Directions.isForward(dir)) -1 else 1
        val cx = area.exactCenterX()
        val cy = area.exactCenterY()
        val (fx, fy, tx, ty) = if (vertical) listOf(cx, cy - sign * step / 2, cx, cy + sign * step / 2)
            else listOf(cx - sign * step / 2, cy, cx + sign * step / 2, cy)
        if (element <= 0) highlight.show(d, Shape.Trail(fx, fy, tx, ty))
        repeat(count) { injector.drag(d, fx, fy, tx, ty, SCROLL_SWIPE_MS, SCROLL_HOLD_MS) }
        return result.put(ActionFields.METHOD, ActionFields.METHOD_SWIPE)
            .put(ActionFields.AREA, JSONArray(listOf(area.left, area.top, area.right, area.bottom)))
            .put(ActionFields.SWIPES, count).put(ActionFields.DISTANCE, total.toInt())
    }

    fun swipe(d: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): JSONObject {
        val duration = durationMs.takeIf { it > 0 } ?: SWIPE_MS
        highlight.show(d, Shape.Trail(fromX.toFloat(), fromY.toFloat(), toX.toFloat(), toY.toFloat()))
        injector.drag(d, fromX.toFloat(), fromY.toFloat(), toX.toFloat(), toY.toFloat(), duration, 0)
        return JSONObject().put(ActionFields.FROM, point(fromX, fromY)).put(ActionFields.TO, point(toX, toY))
            .put(ActionFields.DURATION_MS, duration)
    }

    /** 只报告注入结果，界面效果以返回的状态为准。 */
    fun pressKey(d: Int, key: String): JSONObject {
        val combo = Keys.parse(key, screens.isMain(d))
        highlight.show(d, Shape.Edge)
        if (!injector.key(d, combo)) throw ActionFailure(ToolError.FAILED, "${combo.name} 注入失败")
        return JSONObject().put(ActionFields.KEY, combo.name)
    }

    /**
     * 在焦点输入框的光标处插入：读文本和选区，替换选区后 SET_TEXT，再把光标放到插入内容末尾（第 11 条）。
     * 光标没能放好时 cursor 为 null。
     */
    fun typeText(d: Int, text: String): JSONObject {
        val node = nodes.focusedEditable(d) ?: throw ActionFailure(ToolError.NOT_FOUND, "屏幕 $d 上没有获得输入焦点的可编辑元素")
        box(d, node)
        if (node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) {
            throw ActionFailure(ToolError.UNSUPPORTED, "焦点输入框不支持 ACTION_SET_TEXT")
        }
        val current = NodeActions.text(node).orEmpty()
        if (node.isPassword && current.isNotEmpty()) throw ActionFailure(ToolError.UNSUPPORTED, "密码框读不到真实内容，无法确定插入位置")
        var start = minOf(node.textSelectionStart, node.textSelectionEnd)
        var end = maxOf(node.textSelectionStart, node.textSelectionEnd)
        if (start < 0 && current.isEmpty()) {
            start = 0
            end = 0
        }
        if (start < 0 || end > current.length) throw ActionFailure(ToolError.FAILED, "读不到可靠的选区，无法确定插入位置")
        if (!NodeActions.setText(node, current.substring(0, start) + text + current.substring(end))) {
            throw ActionFailure(ToolError.FAILED, "焦点输入框的 ACTION_SET_TEXT 返回失败")
        }
        val cursor = start + text.length
        node.refresh()
        val placed = NodeActions.select(node, cursor, cursor)
        return JSONObject().put(ActionFields.POSITION, start).put(ActionFields.LENGTH, text.length).put(ActionFields.REPLACED, end - start)
            .put(ActionFields.CURSOR, if (placed) cursor else JSONObject.NULL)
    }

    /** visible 是等待结束时目标是否已在这块屏幕上可见。 */
    fun launchApp(d: Int, packageName: String, intentUri: String): JSONObject {
        val pm = context.packageManager
        val intent = if (intentUri.isNotBlank()) {
            try {
                Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)
            } catch (e: Exception) {
                Log.i(FrameworkApp.TAG, "intent 无法解析", e)
                throw ActionFailure(ToolError.INVALID_ARGUMENT, "intent 无法解析：$intentUri")
            }
        } else {
            pm.getLaunchIntentForPackage(packageName) ?: throw ActionFailure(ToolError.NOT_FOUND, "$packageName 没有可启动的入口 activity")
        }
        val target = intent.`package` ?: intent.component?.packageName ?: pm.resolveActivity(intent, 0)?.activityInfo?.packageName
        // 同一 task 只能在一块屏幕上（第 4 条）：如实返回，由 agent 决定是否 move_task。
        tasks.all().firstOrNull { Tasks.packageOf(it) == target && it.displayId != d }?.let {
            throw ActionFailure(ToolError.CONFLICT, "$target 已在屏幕 ${it.displayId} 上运行（task ${it.taskId}）")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        highlight.show(d, Shape.Edge)
        try {
            context.startActivityAsUser(intent, ActivityOptions.makeBasic().setLaunchDisplayId(d).toBundle(), UserHandle.CURRENT)
        } catch (e: ActivityNotFoundException) {
            throw ActionFailure(ToolError.NOT_FOUND, "没有能处理这个 intent 的 activity")
        } catch (e: SecurityException) {
            Log.w(FrameworkApp.TAG, "启动被拒绝", e)
            throw ActionFailure(ToolError.PERMISSION_DENIED, "启动被系统拒绝")
        } catch (e: Exception) {
            Log.w(FrameworkApp.TAG, "启动失败", e)
            throw ActionFailure(ToolError.FAILED, "启动失败")
        }
        // 冷启动时界面在 activity 出现之前没有任何变化，先等目标 task 在这块屏幕上可见。
        val start = SystemClock.uptimeMillis()
        fun visible() = tasks.all().any { Tasks.packageOf(it) == target && it.displayId == d && it.isVisible }
        while (!visible() && SystemClock.uptimeMillis() - start < Stability.MAX_MS) SystemClock.sleep(LAUNCH_POLL_MS)
        return JSONObject().put(ActionFields.LAUNCHED, intent.component?.flattenToShortString() ?: target ?: intentUri)
            .put(ActionFields.VISIBLE, visible())
    }

    fun moveTask(d: Int, taskId: Int): JSONObject {
        val task = tasks.byId(taskId) ?: throw ActionFailure(ToolError.NOT_FOUND, "没有 task $taskId")
        val result = JSONObject().put(ActionFields.TASK, taskId).put(ActionFields.FROM_DISPLAY, task.displayId).put(ActionFields.TO_DISPLAY, d)
        if (task.displayId == d) return result.put(ActionFields.MOVED, false)
        highlight.show(d, Shape.Edge)
        try {
            tasks.move(taskId, d)
        } catch (e: Exception) {
            Log.w(FrameworkApp.TAG, "移动 task $taskId 失败", e)
            throw ActionFailure(ToolError.FAILED, "把 task $taskId 移到屏幕 $d 失败")
        }
        return result.put(ActionFields.MOVED, true)
    }

    /** 元素在屏幕内的部分；完全在屏幕外（或边界为空）时不注入，报 NOT_FOUND（ADR-0022 第 9b 条第 3 点）。 */
    private fun onScreen(d: Int, node: AccessibilityNodeInfo, element: Int): Rect =
        NodeActions.visibleBounds(node, screens.bounds(d)) ?: NodeActions.bounds(node).let {
            throw ActionFailure(ToolError.NOT_FOUND,
                "元素 $element 在屏幕外（边界 [${it.left},${it.top},${it.right},${it.bottom}]），不能按坐标操作，需要先滚动让它进入屏幕")
        }

    /** 元素边框画在它在屏幕内的部分；完全在屏幕外时（无障碍 action 仍然有效）画屏幕四周。 */
    private fun box(d: Int, node: AccessibilityNodeInfo) =
        highlight.show(d, NodeActions.visibleBounds(node, screens.bounds(d))?.let { Shape.Box(RectF(it)) } ?: Shape.Edge)

    private fun point(x: Int, y: Int) = JSONArray(listOf(x, y))

    companion object {
        /** select_text 的 selection 取值。 */
        const val SELECTION_TEXT = "text"
        const val SELECTION_CURSOR_BEFORE = "cursor_before"
        const val SELECTION_CURSOR_AFTER = "cursor_after"

        /** 按区域滑动来滚动时，每次滑动占区域的比例。 */
        private const val SWIPE_FRACTION = 0.7f
        private const val SCROLL_SWIPE_MS = 400
        private const val SCROLL_HOLD_MS = 150
        private const val SWIPE_MS = 300
        private const val LAUNCH_POLL_MS = 100L
    }
}
