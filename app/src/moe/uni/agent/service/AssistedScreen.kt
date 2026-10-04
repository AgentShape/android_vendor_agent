package moe.uni.agent.service

import android.content.res.Resources
import android.os.IBinder
import android.util.Log
import android.view.Display
import androidx.annotation.StringRes
import moe.uni.agent.R
import moe.uni.agent.framework.IAgentEvents
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.framework.ScreenMirror
import moe.uni.agent.framework.ScreenResult

/**
 * 助手面板发起的一次运行（ADR-0033 第 5 条）。以主屏为目标的调用之前先收起面板（返回时面板已不在屏幕上，截图和点按都碰不到它），
 * 并在主屏顶部的状态胶囊里显示这一步。面板在运行中被用户重新唤起时，下一次主屏调用前再收起。
 * 运行结束时隐藏胶囊；面板被收起过就重新弹出，显示这个会话。
 */
internal class AssistRun(private val panel: AgentService.AssistPanel?, private val conversation: String, private val res: Resources) {
    @Volatile private var reopen = false
    @Volatile private var status = false

    fun beforeMain(screen: IAgentScreen, step: Step) {
        if (panel?.collapse() == true) reopen = true
        try {
            screen.showStatus(Display.DEFAULT_DISPLAY, res.getString(step.text))
            status = true
        } catch (e: Exception) {
            // 胶囊只是提示，失败不影响动作本身。
            Log.w(TAG, "showStatus failed", e)
        }
    }

    fun end(screen: () -> IAgentScreen) {
        if (status) {
            try {
                screen().hideStatus(Display.DEFAULT_DISPLAY)
            } catch (e: Exception) {
                Log.w(TAG, "hideStatus failed", e)
            }
        }
        if (reopen) panel?.reopen(conversation)
    }

    /** 胶囊上显示的步骤。 */
    enum class Step(@param:StringRes val text: Int) {
        LOOK(R.string.status_look),
        TAP(R.string.status_tap),
        ACT(R.string.status_act),
        TYPE(R.string.status_type),
        SELECT(R.string.status_select),
        SCROLL(R.string.status_scroll),
        SWIPE(R.string.status_swipe),
        KEY(R.string.status_key),
        LAUNCH(R.string.status_launch),
        MOVE(R.string.status_move),
    }

    private companion object {
        const val TAG = "AssistRun"
    }
}

/**
 * IAgentScreen 的装饰器：转发所有调用，以主屏为目标的先交给 [AssistRun]。实现接口而不是 Stub，IAgentScreen 加方法时这里必然跟着改。
 * list、setup、镜像、状态胶囊本身和事件监听的方法不算。
 */
internal class AssistedScreen(private val screen: IAgentScreen, private val run: AssistRun) : IAgentScreen {
    private fun <T> onMain(display: Int, step: AssistRun.Step, call: () -> T): T {
        if (display == Display.DEFAULT_DISPLAY) run.beforeMain(screen, step)
        return call()
    }

    override fun asBinder(): IBinder = screen.asBinder()

    override fun setup() = screen.setup()

    override fun list(): ScreenResult = screen.list()

    override fun state(display: Int): ScreenResult = onMain(display, AssistRun.Step.LOOK) { screen.state(display) }

    override fun query(display: Int, selector: String, settle: Boolean): String =
        onMain(display, AssistRun.Step.LOOK) { screen.query(display, selector, settle) }

    override fun tap(display: Int, element: Int, x: Int, y: Int, count: Int, longPress: Boolean, observe: Int): ScreenResult =
        onMain(display, AssistRun.Step.TAP) { screen.tap(display, element, x, y, count, longPress, observe) }

    override fun nodeAction(display: Int, element: Int, action: String, observe: Int): ScreenResult =
        onMain(display, AssistRun.Step.ACT) { screen.nodeAction(display, element, action, observe) }

    override fun setText(display: Int, element: Int, text: String, observe: Int): ScreenResult =
        onMain(display, AssistRun.Step.TYPE) { screen.setText(display, element, text, observe) }

    override fun selectText(
        display: Int, element: Int, text: String, prefix: String, suffix: String, selection: String, observe: Int,
    ): ScreenResult = onMain(display, AssistRun.Step.SELECT) { screen.selectText(display, element, text, prefix, suffix, selection, observe) }

    override fun scroll(display: Int, element: Int, direction: String, pages: Float, observe: Int): ScreenResult =
        onMain(display, AssistRun.Step.SCROLL) { screen.scroll(display, element, direction, pages, observe) }

    override fun swipe(display: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int, observe: Int): ScreenResult =
        onMain(display, AssistRun.Step.SWIPE) { screen.swipe(display, fromX, fromY, toX, toY, durationMs, observe) }

    override fun pressKey(display: Int, key: String, observe: Int): ScreenResult =
        onMain(display, AssistRun.Step.KEY) { screen.pressKey(display, key, observe) }

    override fun typeText(display: Int, text: String, observe: Int): ScreenResult =
        onMain(display, AssistRun.Step.TYPE) { screen.typeText(display, text, observe) }

    override fun launchApp(display: Int, packageName: String, intentUri: String, observe: Int): ScreenResult =
        onMain(display, AssistRun.Step.LAUNCH) { screen.launchApp(display, packageName, intentUri, observe) }

    /** display 是移入的目标屏幕。 */
    override fun moveTask(taskId: Int, display: Int, observe: Int): ScreenResult =
        onMain(display, AssistRun.Step.MOVE) { screen.moveTask(taskId, display, observe) }

    override fun createMirror(display: Int, token: IBinder): ScreenMirror = screen.createMirror(display, token)

    override fun releaseMirror(token: IBinder) = screen.releaseMirror(token)

    override fun showStatus(display: Int, text: String) = screen.showStatus(display, text)

    override fun hideStatus(display: Int) = screen.hideStatus(display)

    override fun addEventListener(listener: IAgentEvents) = screen.addEventListener(listener)

    override fun removeEventListener(listener: IAgentEvents) = screen.removeEventListener(listener)
}
