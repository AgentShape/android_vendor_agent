package moe.uni.agent.watch

import android.os.IBinder
import android.view.Display
import moe.uni.agent.framework.IAgentEvents
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.framework.ScreenMirror
import moe.uni.agent.framework.ScreenResult
import moe.uni.agent.watch.ScreenWatch.Event

/**
 * IAgentScreen 的装饰器（ADR-0031 第 5 条）：转发所有调用；以虚拟屏幕为目标的调用先上报 AgentActive。
 * 实现接口而不是 Stub，IAgentScreen 加方法时这里必然跟着改。list、setup、镜像、状态胶囊和事件监听的方法不算 agent 活跃；
 * state、query 算（agent 在看虚拟屏幕，用户也想看它看到了什么）。
 */
class WatchedScreen(private val screen: IAgentScreen, private val watch: ScreenWatch, private val session: String) : IAgentScreen {
    private fun <T> active(display: Int, call: () -> T): T {
        // 模型常用 screen_list/state 返回的真实 id（如 2）而不是 -1；AgentFramework 只接受主屏幕、-1 和虚拟屏幕的 id，
        // 所以不是主屏幕就是虚拟屏幕（非法 id 的调用本身会失败）。
        if (display != Display.DEFAULT_DISPLAY) watch.dispatch(Event.AgentActive(session))
        return call()
    }

    override fun asBinder(): IBinder = screen.asBinder()

    override fun setup() = screen.setup()

    override fun list(): ScreenResult = screen.list()

    override fun state(display: Int): ScreenResult = active(display) { screen.state(display) }

    override fun query(display: Int, selector: String, settle: Boolean): String = active(display) { screen.query(display, selector, settle) }

    override fun tap(display: Int, element: Int, x: Int, y: Int, count: Int, longPress: Boolean, observe: Int): ScreenResult =
        active(display) { screen.tap(display, element, x, y, count, longPress, observe) }

    override fun nodeAction(display: Int, element: Int, action: String, observe: Int): ScreenResult =
        active(display) { screen.nodeAction(display, element, action, observe) }

    override fun setText(display: Int, element: Int, text: String, observe: Int): ScreenResult =
        active(display) { screen.setText(display, element, text, observe) }

    override fun selectText(
        display: Int, element: Int, text: String, prefix: String, suffix: String, selection: String, observe: Int,
    ): ScreenResult = active(display) { screen.selectText(display, element, text, prefix, suffix, selection, observe) }

    override fun scroll(display: Int, element: Int, direction: String, pages: Float, observe: Int): ScreenResult =
        active(display) { screen.scroll(display, element, direction, pages, observe) }

    override fun swipe(display: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int, observe: Int): ScreenResult =
        active(display) { screen.swipe(display, fromX, fromY, toX, toY, durationMs, observe) }

    override fun pressKey(display: Int, key: String, observe: Int): ScreenResult =
        active(display) { screen.pressKey(display, key, observe) }

    override fun typeText(display: Int, text: String, observe: Int): ScreenResult =
        active(display) { screen.typeText(display, text, observe) }

    override fun launchApp(display: Int, packageName: String, intentUri: String, observe: Int): ScreenResult =
        active(display) { screen.launchApp(display, packageName, intentUri, observe) }

    /** display 是移入的目标屏幕。 */
    override fun moveTask(taskId: Int, display: Int, observe: Int): ScreenResult =
        active(display) { screen.moveTask(taskId, display, observe) }

    override fun createMirror(display: Int, token: IBinder): ScreenMirror = screen.createMirror(display, token)

    override fun releaseMirror(token: IBinder) = screen.releaseMirror(token)

    override fun showStatus(display: Int, text: String) = screen.showStatus(display, text)

    override fun hideStatus(display: Int) = screen.hideStatus(display)

    override fun addEventListener(listener: IAgentEvents) = screen.addEventListener(listener)

    override fun removeEventListener(listener: IAgentEvents) = screen.removeEventListener(listener)
}
