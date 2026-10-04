package moe.uni.agent.framework.system

import android.app.ActivityManager.RunningTaskInfo
import android.app.ActivityTaskManager
import android.content.ComponentName
import android.view.Display

/** 所有屏幕上的 task（ADR-0022 第 4 条）：同一个 task 只能在一块屏幕上。 */
class Tasks {
    private val atm = ActivityTaskManager.getService()

    /** 同一屏幕上靠前的在上层。 */
    fun all(): List<RunningTaskInfo> = atm.getTasks(MAX_TASKS, false, false, Display.INVALID_DISPLAY)

    fun byId(taskId: Int) = all().firstOrNull { it.taskId == taskId }

    fun byComponent(component: ComponentName) = all().firstOrNull { it.topActivity == component || it.baseActivity == component }

    /** 屏幕上最上层可见 task 的顶层 activity。 */
    fun top(display: Int): String? =
        all().firstOrNull { it.displayId == display && it.isVisible }?.topActivity?.flattenToShortString()

    fun move(taskId: Int, display: Int) = atm.moveRootTaskToDisplay(taskId, display)

    companion object {
        private const val MAX_TASKS = 100

        fun packageOf(task: RunningTaskInfo) = (task.baseActivity ?: task.topActivity)?.packageName
    }
}
