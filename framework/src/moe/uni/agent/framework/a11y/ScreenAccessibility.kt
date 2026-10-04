package moe.uni.agent.framework.a11y

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.SurfaceControl
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * 读节点树、执行节点 action（ADR-0022 第 8 条）。事件用来记录每块屏幕最后一次界面变化的时间，
 * 并交给 listener 产生等待用的事件（ADR-0034 第 4 条）。
 */
class ScreenAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        lastEvent[event.displayId] = SystemClock.uptimeMillis()
        listener?.invoke(event)
    }

    override fun onInterrupt() {}

    /**
     * 把 overlay 挂到屏幕上，连续挂两次（ADR-0023"首次挂载失败"）：系统新增屏幕时可能缓存了空的父层，
     * 第一次挂载失败并清掉缓存，第二次重新获取。挂载结果拿不到，本树关闭了结果回调。
     */
    fun attachOverlay(display: Int, sc: SurfaceControl) {
        repeat(2) { attachAccessibilityOverlayToDisplay(display, sc) }
    }

    companion object {
        @Volatile var instance: ScreenAccessibility? = null
            private set

        /** display id → 最后一次事件的 uptime。 */
        val lastEvent = ConcurrentHashMap<Int, Long>()

        /** 在主线程上收到每个事件；事件对象在返回后会被回收，要用的内容当场取出。 */
        @Volatile var listener: ((AccessibilityEvent) -> Unit)? = null
    }
}
