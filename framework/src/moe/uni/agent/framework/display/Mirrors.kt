package moe.uni.agent.framework.display

import android.gui.DropInputMode
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ServiceSpecificException
import android.util.Log
import android.view.SurfaceControl
import android.view.WindowManagerGlobal
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.framework.ScreenMirror
import moe.uni.agent.framework.highlight.Highlight

/**
 * 虚拟屏幕的只读镜像（ADR-0031 第 3 条），按调用方给的 token 管理。每个镜像是一个容器 leash，下面挂两层：
 * mirrorDisplay 得到的窗口层镜像（不含无障碍 overlay）和流光 overlay 的镜像，流光在上；窗口层丢弃全部输入。
 * 两层都是屏幕坐标的全屏图层，缩放由调用方设在 leash 上。
 *
 * 表和流光 overlay 都只在主线程访问（Highlight 的 View 操作在主线程），binder 调用在主线程执行并等待结果。
 * 不持有 ScreenControl 的锁：动作可能占锁数秒，镜像的创建和释放不排在它们后面。
 */
class Mirrors(private val virtual: VirtualScreen, private val highlight: Highlight) {
    private val main = Handler(Looper.getMainLooper())
    private val mirrors = HashMap<IBinder, Mirror>()

    init {
        // 由 ScreenControl 在主线程构造（FrameworkApp.onCreate）。
        highlight.addOverlayListener(::onOverlay)
    }

    /** 同一个 token 重复创建时先释放旧的。 */
    fun create(display: Int, token: IBinder): ScreenMirror {
        if (display != Screens.VIRTUAL && !(virtual.created && display == virtual.id)) {
            throw ServiceSpecificException(IAgentScreen.ERROR_INVALID_ARGUMENT, "屏幕 $display 不是虚拟屏幕")
        }
        val id = virtual.id
        val window = SurfaceControl()
        if (!WindowManagerGlobal.getWindowManagerService()!!.mirrorDisplay(id, window)) {
            throw ServiceSpecificException(IAgentScreen.ERROR_UNAVAILABLE, "虚拟屏幕 $id 已不存在")
        }
        val out = ScreenMirror().apply {
            width = virtual.width
            height = virtual.height
        }
        out.surface = try {
            onMain {
                mirrors.remove(token)?.release()
                // 返回副本：binder 返回值写入 Parcel 时 SurfaceControl 会被 release()（PARCELABLE_WRITE_RETURN_VALUE），
                // 本地的 leash 还要留给 setGlow 和 release 使用。
                SurfaceControl(Mirror(id, token, window).also { mirrors[token] = it }.leash, LEASH_NAME)
            }
        } catch (e: Exception) {
            // 调用方已死（linkToDeath 失败）等：窗口层镜像归本方法所有，不能留在 SurfaceFlinger 里。
            SurfaceControl.Transaction().remove(window).apply()
            throw e
        }
        return out
    }

    fun release(token: IBinder) {
        onMain { mirrors.remove(token)?.release() }
    }

    /** 虚拟屏幕释放时（VirtualScreen.release）所有镜像失效，调用方的画面变黑。 */
    fun clear() {
        main.post {
            if (mirrors.isNotEmpty()) Log.i(FrameworkApp.TAG, "虚拟屏幕已释放，清除 ${mirrors.size} 个镜像")
            mirrors.values.forEach { it.release() }
            mirrors.clear()
        }
    }

    /** overlay 被创建或重建：该屏幕上所有镜像换成新图层的镜像，leash 不变。 */
    private fun onOverlay(display: Int) {
        val targets = mirrors.values.filter { it.display == display }
        if (targets.isEmpty()) return
        val overlay = highlight.overlaySurface(display)
        targets.forEach { it.setGlow(overlay) }
    }

    private fun <T> onMain(block: () -> T): T {
        val task = FutureTask(Callable { block() })
        main.post(task)
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause!!
        }
    }

    /** 一个镜像。只在主线程使用；window 是 mirrorDisplay 的结果，归本对象所有。 */
    private inner class Mirror(val display: Int, private val token: IBinder, private val window: SurfaceControl) : IBinder.DeathRecipient {
        init {
            // 先登记死亡通知（调用方已死时在这里失败），再建 leash，失败时没有东西要收拾。
            token.linkToDeath(this, 0)
        }

        val leash: SurfaceControl = SurfaceControl.Builder()
            .setName(LEASH_NAME)
            .setCallsite(LEASH_NAME)
            .setContainerLayer()
            // Builder 默认隐藏；调用方只做 reparent 和缩放。
            .setHidden(false)
            .build()
        private var glow: SurfaceControl? = null

        init {
            SurfaceControl.Transaction()
                .reparent(window, leash)
                .setLayer(window, WINDOW_LAYER)
                .setDropInputMode(window, DropInputMode.ALL)
                .show(window)
                .apply()
            // 流光只是提示（ADR-0023 第 5 条），镜像不到时窗口层照常可用。
            try {
                setGlow(highlight.overlaySurface(display))
            } catch (e: Exception) {
                Log.w(FrameworkApp.TAG, "流光层镜像失败", e)
            }
        }

        /** 换成 overlay 的镜像；overlay 为 null（无障碍服务未连接）时只去掉旧的，等 overlay 创建时再补上。 */
        fun setGlow(overlay: SurfaceControl?) {
            val t = SurfaceControl.Transaction()
            glow?.let { t.remove(it) }
            glow = overlay?.let { SurfaceControl.mirrorSurface(it) }
            glow?.let { t.reparent(it, leash).setLayer(it, GLOW_LAYER).show(it) }
            t.apply()
        }

        fun release() {
            token.unlinkToDeath(this, 0)
            val t = SurfaceControl.Transaction()
            glow?.let { t.remove(it) }
            t.remove(window).remove(leash).apply()
        }

        override fun binderDied() {
            main.post {
                if (mirrors.remove(token, this)) {
                    Log.i(FrameworkApp.TAG, "镜像的调用方已退出，释放镜像")
                    release()
                }
            }
        }
    }

    companion object {
        /** 容器 leash 的名字，dumpsys SurfaceFlinger 里用它辨认（ADR-0031 第 3 条）。 */
        private const val LEASH_NAME = "AgentFramework#mirror"

        /** leash 下的层序：流光层在窗口层之上（ADR-0031 第 3 条）。 */
        private const val WINDOW_LAYER = 0
        private const val GLOW_LAYER = 1
    }
}
