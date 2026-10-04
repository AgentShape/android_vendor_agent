package moe.uni.agent.framework.highlight

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.a11y.ScreenAccessibility

/**
 * 动作可视化（ADR-0023）：在被操作的位置画流光边框。每块屏幕一个无障碍 overlay，空闲时隐藏。
 * 效果只是提示，失败只记日志，不影响动作本身。View 相关操作都在主线程。
 * overlay 不在 mirrorDisplay 的镜像里，镜像另外镜像它的图层，所以对外暴露图层和"overlay 被创建"的通知（ADR-0031 第 3 条）。
 */
class Highlight(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val overlays = HashMap<Int, Overlay>()
    private val overlayListeners = ArrayList<(Int) -> Unit>()

    /** 在屏幕上开始画效果，立即返回，动作本身不等待。 */
    fun show(display: Int, shape: Shape) {
        main.post {
            try {
                overlay(display)?.start(shape)
            } catch (e: Exception) {
                Log.w(FrameworkApp.TAG, "动作效果失败", e)
            }
        }
    }

    /** 等屏幕上的效果结束并隐藏，之后的截图里不会有它（ADR-0023 第 4 条）。 */
    fun awaitIdle(display: Int) {
        val done = CountDownLatch(1)
        main.post { overlays[display]?.onIdle { done.countDown() } ?: done.countDown() }
        done.await(GlowView.DURATION_MS + IDLE_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    /** 屏幕的 overlay 图层，没有就创建；无障碍服务未连接时为 null。主线程调用。 */
    fun overlaySurface(display: Int): SurfaceControl? = overlay(display)?.sc

    /**
     * 某块屏幕的 overlay 被创建（第一次，或无障碍服务重连后重建）时回调，参数是 display id。
     * 回调在主线程，此时 overlaySurface 已经返回新的图层。增删监听也在主线程。
     */
    fun addOverlayListener(listener: (Int) -> Unit) {
        overlayListeners += listener
    }

    fun removeOverlayListener(listener: (Int) -> Unit) {
        overlayListeners -= listener
    }

    private fun overlay(display: Int): Overlay? {
        val a11y = ScreenAccessibility.instance ?: return null
        val displays = context.getSystemService(DisplayManager::class.java)
        // 屏幕已经不存在，或无障碍服务重连后 overlay 已被系统摘除时重建。
        overlays.entries.removeIf { (id, o) ->
            (displays.getDisplay(id) == null || o.service !== a11y).also { if (it) o.release() }
        }
        overlays[display]?.let { return it }
        val d = displays.getDisplay(display) ?: return null
        val metrics = DisplayMetrics().also { d.getRealMetrics(it) }
        val displayContext = context.createDisplayContext(d)
        val view = GlowView(displayContext)
        val host = SurfaceControlViewHost(displayContext, d, null as IBinder?)
        host.setView(view, WindowManager.LayoutParams(
            metrics.widthPixels, metrics.heightPixels,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT))
        val sc = host.surfacePackage!!.surfaceControl
        SurfaceControl.Transaction().setVisibility(sc, false).apply()
        a11y.attachOverlay(display, sc)
        return Overlay(a11y, host, sc, view).also {
            overlays[display] = it
            // 监听方（镜像）出错不影响效果本身。
            overlayListeners.forEach { listener ->
                try {
                    listener(display)
                } catch (e: Exception) {
                    Log.w(FrameworkApp.TAG, "overlay 监听出错", e)
                }
            }
        }
    }

    private class Overlay(val service: ScreenAccessibility, val host: SurfaceControlViewHost, val sc: SurfaceControl, val view: GlowView) {
        private val waiters = ArrayList<() -> Unit>()
        private var busy = false

        fun start(shape: Shape) {
            busy = true
            SurfaceControl.Transaction().setVisibility(sc, true).apply()
            view.start(shape) {
                // 最后一帧已经清空；隐藏整层，截图和合成都不再包含它。同步提交，返回时 SurfaceFlinger 已经生效。
                SurfaceControl.Transaction().setVisibility(sc, false).apply(true)
                busy = false
                waiters.forEach { it() }
                waiters.clear()
            }
        }

        fun onIdle(callback: () -> Unit) {
            if (busy) waiters += callback else callback()
        }

        fun release() {
            SurfaceControl.Transaction().reparent(sc, null).apply()
            host.release()
        }
    }

    companion object {
        /** 等效果结束的额外宽限，超过就不再等（效果只是提示）。 */
        private const val IDLE_GRACE_MS = 1000L
    }
}
