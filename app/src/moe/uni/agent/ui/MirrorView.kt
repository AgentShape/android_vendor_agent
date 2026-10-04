package moe.uni.agent.ui

import android.content.Context
import android.os.Binder
import android.util.Log
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import moe.uni.agent.framework.IAgentScreen
import moe.uni.agent.framework.ScreenMirror

/**
 * 虚拟屏幕的只读镜像（ADR-0031 第 2、3 条）。surface 创建时向 AgentFramework 要镜像（容器 leash），挂到自己的 SurfaceControl 下，
 * 按 FIT_CENTER 缩放居中；surface 销毁时释放。镜像不接收触摸（AgentFramework 设了 DropInputMode）。
 * 镜像的状态经 [onState] 报给界面（主线程）：界面按镜像的比例确定本 View 的尺寸，所以不留黑边（ADR-0032 第 10 条）。
 * 做法参考 frameworks/base/libs/computercontrol 的 MirrorView（MirrorSurface）。
 */
class MirrorView(
    context: Context,
    private val screen: () -> IAgentScreen,
    cornerRadius: Float,
    private val onState: (MirrorState) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {
    /** AgentFramework 按它识别这个镜像，本进程死亡时自动释放。 */
    private val token = Binder()

    /**
     * binder 调用放在单独的线程上，按提交顺序执行：AgentFramework 的屏幕调用是串行的，agent 的动作可能要等几秒，不能卡住主线程。
     * 最多一个线程，空闲后自己退出，不用关闭。
     */
    private val worker = ThreadPoolExecutor(0, 1, WORKER_KEEP_ALIVE_S, TimeUnit.SECONDS, LinkedBlockingQueue())

    /** 挂在 surface 下的镜像；只在主线程访问。 */
    private var mirror: ScreenMirror? = null

    /** surface 每创建、销毁一次加一：镜像到达时 surface 已经换过，就不再挂上。 */
    private var generation = 0

    init {
        holder.addCallback(this)
        // 圆角和外面的容器一致，由界面按主题的形状给出。
        setCornerRadius(cornerRadius)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val current = ++generation
        worker.execute {
            val m = try {
                screen().createMirror(IAgentScreen.DISPLAY_VIRTUAL, token)
            } catch (e: Exception) {
                // 虚拟屏幕还没创建、AgentFramework 不可用等：界面显示说明，重新展开时再试。
                Log.w(TAG, "createMirror failed", e)
                null
            }
            post { if (m != null) attach(current, m) else if (current == generation) onState(MirrorState.Failed) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        val m = mirror ?: return
        SurfaceControl.Transaction().use { fit(it, m, width, height).apply() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        generation++
        mirror?.surface?.release()
        mirror = null
        onState(MirrorState.Connecting)
        worker.execute {
            try {
                screen().releaseMirror(token)
            } catch (e: Exception) {
                Log.w(TAG, "releaseMirror failed", e)
            }
        }
    }

    /** 主线程上：把镜像挂到 surface 下。surface 已经换过时丢掉本地引用，AgentFramework 那边由随后的 releaseMirror 或重建释放。 */
    private fun attach(created: Int, m: ScreenMirror) {
        val parent = surfaceControl
        if (created != generation || parent == null) {
            m.surface.release()
            return
        }
        mirror = m
        val frame = holder.surfaceFrame
        SurfaceControl.Transaction().use {
            fit(it.reparent(m.surface, parent).show(m.surface), m, frame.width(), frame.height()).apply()
        }
        onState(MirrorState.Shown(m.width, m.height))
    }

    /** FIT_CENTER：整个虚拟屏幕放进 surface，保持比例，居中。 */
    private fun fit(t: SurfaceControl.Transaction, m: ScreenMirror, width: Int, height: Int): SurfaceControl.Transaction {
        if (m.width <= 0 || m.height <= 0 || width <= 0 || height <= 0) return t
        val scale = minOf(width.toFloat() / m.width, height.toFloat() / m.height)
        return t.setScale(m.surface, scale, scale)
            .setPosition(m.surface, (width - m.width * scale) / 2, (height - m.height * scale) / 2)
    }

    private companion object {
        const val TAG = "MirrorView"
        const val WORKER_KEEP_ALIVE_S = 5L
    }
}

/** 镜像的状态：连接中（surface 刚创建或已销毁）、已显示（带虚拟屏幕的逻辑尺寸）、失败。 */
sealed interface MirrorState {
    data object Connecting : MirrorState
    data class Shown(val width: Int, val height: Int) : MirrorState
    data object Failed : MirrorState
}
