package moe.uni.agent.framework.control

import android.app.compat.CompatChanges
import android.companion.virtual.VirtualDeviceManager
import android.content.ComponentName
import android.content.IntentSender
import android.os.UserHandle
import android.util.Log
import android.view.Display
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.display.VirtualScreen
import moe.uni.agent.framework.system.Tasks
import org.json.JSONObject

/**
 * 虚拟屏幕上的安全窗口与启动阻止（ADR-0022 第 6 条）：如实报告事件，重新查询 task 所在屏幕，
 * 仍在虚拟屏幕上才移到主屏幕，交给用户处理。回调在虚拟屏幕的 handler 线程上。事件的字段见 EventFields。
 */
class SecureWindows(
    private val tasks: Tasks,
    private val notices: Notices,
    private val virtual: () -> VirtualScreen,
) : VirtualDeviceManager.ActivityListener {
    override fun onTopActivityChanged(displayId: Int, topActivity: ComponentName) {}

    override fun onDisplayEmpty(displayId: Int) {}

    /**
     * 没有启用 compat change 的旧 target app 由系统自己把 task 移到主屏幕（随后回调 onActivityLaunchBlocked），
     * 系统的移动是异步的，这里再移动会让 system_server 在 Task.reparent 时崩溃，所以不移动。
     */
    override fun onSecureWindowShown(displayId: Int, componentName: ComponentName, user: UserHandle) {
        val task = tasks.byComponent(componentName)
        val screen = virtual()
        val result = when {
            !CompatChanges.isChangeEnabled(ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE, componentName.packageName, user) ->
                EventFields.MOVED_BY_SYSTEM
            task == null -> EventFields.NO_TASK
            task.displayId == displayId && screen.created && displayId == screen.id -> try {
                tasks.move(task.taskId, Display.DEFAULT_DISPLAY)
                EventFields.MOVED_TO_MAIN
            } catch (e: Exception) {
                Log.w(FrameworkApp.TAG, "把 task ${task.taskId} 移到主屏幕失败", e)
                EventFields.MOVE_FAILED
            }
            else -> EventFields.NOT_MOVED
        }
        notices.add(event(EventFields.SECURE_WINDOW, displayId, componentName).put(EventFields.RESULT, result))
    }

    /** 启动阻止可能来自 FLAG_SECURE 的兼容路径，也可能来自其他策略，不一律当作安全窗口，也不移动。 */
    override fun onActivityLaunchBlocked(displayId: Int, componentName: ComponentName, user: UserHandle, intentSender: IntentSender?) {
        notices.add(event(EventFields.LAUNCH_BLOCKED, displayId, componentName))
    }

    /** task 和 task_display 是事件发生后重新查询的结果，找不到 task 时为 null。 */
    private fun event(type: String, displayId: Int, component: ComponentName): JSONObject {
        val task = tasks.byComponent(component)
        return JSONObject().put(EventFields.TYPE, type).put(EventFields.DISPLAY, displayId)
            .put(EventFields.COMPONENT, component.flattenToShortString())
            .put(EventFields.TASK, task?.taskId ?: JSONObject.NULL)
            .put(EventFields.TASK_DISPLAY, task?.displayId ?: JSONObject.NULL)
    }

    companion object {
        /** GenericWindowPolicyController 的 compat change：启用时安全 activity 可以留在虚拟屏幕上，由 owner 处理。 */
        private const val ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE = 201712607L
    }
}
