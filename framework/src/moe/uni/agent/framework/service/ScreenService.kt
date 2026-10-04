package moe.uni.agent.framework.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.IAgentEvents
import moe.uni.agent.framework.IAgentScreen

/**
 * IAgentScreen 的 bound Service（ADR-0022 第 2 条）。manifest 中用 signature 权限保护，
 * 入口经 dispatch 再校验调用方。
 */
class ScreenService : Service() {
    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IAgentScreen.Stub() {
        private val screen get() = FrameworkApp.screen

        override fun setup() = dispatch("setup") { screen.setup() }
        override fun list() = dispatch("list") { screen.list() }
        override fun state(display: Int) = dispatch("state") { screen.state(display) }
        override fun query(display: Int, selector: String, settle: Boolean) = dispatch("query") { screen.query(display, selector, settle) }
        override fun tap(display: Int, element: Int, x: Int, y: Int, count: Int, longPress: Boolean, observe: Int) =
            dispatch("tap") { screen.tap(display, element, x, y, count, longPress, observe) }
        override fun nodeAction(display: Int, element: Int, action: String, observe: Int) =
            dispatch("nodeAction") { screen.nodeAction(display, element, action, observe) }
        override fun setText(display: Int, element: Int, text: String, observe: Int) =
            dispatch("setText") { screen.setText(display, element, text, observe) }
        override fun selectText(display: Int, element: Int, text: String, prefix: String, suffix: String, selection: String, observe: Int) =
            dispatch("selectText") { screen.selectText(display, element, text, prefix, suffix, selection, observe) }
        override fun scroll(display: Int, element: Int, direction: String, pages: Float, observe: Int) =
            dispatch("scroll") { screen.scroll(display, element, direction, pages, observe) }
        override fun swipe(display: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int, observe: Int) =
            dispatch("swipe") { screen.swipe(display, fromX, fromY, toX, toY, durationMs, observe) }
        override fun pressKey(display: Int, key: String, observe: Int) = dispatch("pressKey") { screen.pressKey(display, key, observe) }
        override fun typeText(display: Int, text: String, observe: Int) = dispatch("typeText") { screen.typeText(display, text, observe) }
        override fun launchApp(display: Int, packageName: String, intentUri: String, observe: Int) =
            dispatch("launchApp") { screen.launchApp(display, packageName, intentUri, observe) }
        override fun moveTask(taskId: Int, display: Int, observe: Int) = dispatch("moveTask") { screen.moveTask(taskId, display, observe) }
        override fun createMirror(display: Int, token: IBinder) = dispatch("createMirror") { screen.createMirror(display, token) }
        override fun releaseMirror(token: IBinder) = dispatch("releaseMirror") { screen.releaseMirror(token) }
        override fun showStatus(display: Int, text: String) = dispatch("showStatus") { screen.showStatus(display, text) }
        override fun hideStatus(display: Int) = dispatch("hideStatus") { screen.hideStatus(display) }
        override fun addEventListener(listener: IAgentEvents) = dispatch("addEventListener") { screen.addEventListener(listener) }
        override fun removeEventListener(listener: IAgentEvents) =
            dispatch("removeEventListener") { screen.removeEventListener(listener) }
    }
}
