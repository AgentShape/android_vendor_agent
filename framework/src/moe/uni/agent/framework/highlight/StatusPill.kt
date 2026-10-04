package moe.uni.agent.framework.highlight

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.Log
import android.util.TypedValue
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import moe.uni.agent.framework.FrameworkApp
import moe.uni.agent.framework.a11y.ScreenAccessibility

/**
 * 状态胶囊（ADR-0033 第 5 条）：agent 在主屏上操作时，屏幕顶部状态栏区域居中显示的一个胶囊，里面是转圈的指示器和当前步骤。
 * 和流光一样挂在无障碍 overlay 层：不是窗口，不挡触摸，不在节点树里；截图时由 Screenshots 排除它的图层。
 * View 相关操作都在主线程；[layer] 可以从任何线程读。
 */
class StatusPill(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val pills = HashMap<Int, Pill>()

    /** 正在显示的胶囊图层，截图时排除。 */
    @Volatile private var shown: Map<Int, SurfaceControl> = emptyMap()

    fun show(display: Int, text: String) {
        main.post {
            try {
                pill(display)?.show(text)
                updateShown()
            } catch (e: Exception) {
                Log.w(FrameworkApp.TAG, "状态胶囊显示失败", e)
            }
        }
    }

    fun hide(display: Int) {
        main.post {
            pills[display]?.hide()
            updateShown()
        }
    }

    /** display 上正在显示的胶囊图层，没有时为 null。 */
    fun layer(display: Int): SurfaceControl? = shown[display]

    private fun updateShown() {
        shown = pills.filterValues { it.visible }.mapValues { it.value.sc }
    }

    private fun pill(display: Int): Pill? {
        val a11y = ScreenAccessibility.instance ?: return null
        val displays = context.getSystemService(DisplayManager::class.java)
        // 屏幕已经不存在，或无障碍服务重连后 overlay 已被系统摘除时重建。
        pills.entries.removeIf { (id, p) -> (displays.getDisplay(id) == null || p.service !== a11y).also { if (it) p.release() } }
        pills[display]?.let { return it }
        val d = displays.getDisplay(display) ?: return null
        val metrics = DisplayMetrics().also { d.getRealMetrics(it) }
        val displayContext = context.createDisplayContext(d)
        val view = PillView(displayContext)
        val height = view.pillHeight
        val host = SurfaceControlViewHost(displayContext, d, null as IBinder?)
        host.setView(view, WindowManager.LayoutParams(
            metrics.widthPixels, height,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT))
        val sc = host.surfacePackage!!.surfaceControl
        // 状态栏够高时放在状态栏中间，否则紧贴状态栏下方。
        val statusBar = statusBarHeight(displayContext)
        val top = if (statusBar >= height) (statusBar - height) / 2 else statusBar
        SurfaceControl.Transaction().setPosition(sc, 0f, top.toFloat()).setVisibility(sc, false).apply()
        a11y.attachOverlay(display, sc)
        return Pill(a11y, host, sc, view).also { pills[display] = it }
    }

    /** 状态栏高度：先取窗口指标里的状态栏 inset；非界面 context 取不到时用框架的 status_bar_height。 */
    private fun statusBarHeight(displayContext: Context): Int = try {
        displayContext.getSystemService(WindowManager::class.java).maximumWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.statusBars()).top
    } catch (e: RuntimeException) {
        val res = displayContext.resources
        res.getIdentifier(STATUS_BAR_HEIGHT, DIMEN, ANDROID).takeIf { it != 0 }?.let(res::getDimensionPixelSize) ?: 0
    }

    private class Pill(val service: ScreenAccessibility, val host: SurfaceControlViewHost, val sc: SurfaceControl, val view: PillView) {
        var visible = false
            private set

        fun show(text: String) {
            view.setText(text)
            if (!visible) {
                visible = true
                view.start()
                SurfaceControl.Transaction().setVisibility(sc, true).apply()
            }
        }

        fun hide() {
            if (!visible) return
            visible = false
            view.stop()
            SurfaceControl.Transaction().setVisibility(sc, false).apply()
        }

        fun release() {
            view.stop()
            SurfaceControl.Transaction().reparent(sc, null).apply()
            host.release()
        }
    }

    /**
     * 胶囊本身：surfaceContainerHigh 的全圆角底，左边 primary 色转圈的弧，右边 onSurface 的一行字。
     * 颜色取系统的动态配色资源，随深色模式切换。View 和整行一样宽，只画中间的胶囊。
     */
    private class PillView(context: Context) : View(context) {
        private val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        private val container = context.getColor(
            if (night) android.R.color.system_surface_container_high_dark else android.R.color.system_surface_container_high_light,
        )
        private val onSurface = context.getColor(if (night) android.R.color.system_on_surface_dark else android.R.color.system_on_surface_light)
        private val primary = context.getColor(if (night) android.R.color.system_primary_dark else android.R.color.system_primary_light)

        val pillHeight = dp(HEIGHT_DP)
        private val padding = dp(PADDING_DP).toFloat()
        private val spinnerSize = dp(SPINNER_DP).toFloat()
        private val gap = dp(GAP_DP).toFloat()
        private val maxTextWidth = dp(MAX_TEXT_DP).toFloat()

        private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = container }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = onSurface
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, TEXT_SP, resources.displayMetrics)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = primary
            style = Paint.Style.STROKE
            strokeWidth = dp(STROKE_DP).toFloat()
            strokeCap = Paint.Cap.ROUND
        }
        private val rect = RectF()
        private var text = ""
        private var angle = 0f
        private val spin = ValueAnimator.ofFloat(0f, FULL_TURN).apply {
            duration = SPIN_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
        }

        fun setText(value: String) {
            text = TextUtils.ellipsize(value, android.text.TextPaint(textPaint), maxTextWidth, TextUtils.TruncateAt.END).toString()
            invalidate()
        }

        fun start() = spin.start()

        fun stop() = spin.cancel()

        override fun onDraw(canvas: Canvas) {
            val textWidth = textPaint.measureText(text)
            val width = padding * 2 + spinnerSize + gap + textWidth
            val left = (this.width - width) / 2
            val h = height.toFloat()
            rect.set(left, 0f, left + width, h)
            canvas.drawRoundRect(rect, h / 2, h / 2, background)
            val inset = arc.strokeWidth / 2
            rect.set(left + padding + inset, (h - spinnerSize) / 2 + inset, left + padding + spinnerSize - inset, (h + spinnerSize) / 2 - inset)
            canvas.drawArc(rect, angle, SWEEP, false, arc)
            val baseline = h / 2 - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(text, left + padding + spinnerSize + gap, baseline, textPaint)
        }

        private fun dp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()
    }

    private companion object {
        const val HEIGHT_DP = 26f
        const val PADDING_DP = 10f
        const val SPINNER_DP = 14f
        const val STROKE_DP = 2f
        const val GAP_DP = 8f
        const val MAX_TEXT_DP = 220f
        const val TEXT_SP = 12f
        const val SWEEP = 270f
        const val FULL_TURN = 360f
        const val SPIN_MS = 1000L
        const val STATUS_BAR_HEIGHT = "status_bar_height"
        const val DIMEN = "dimen"
        const val ANDROID = "android"
    }
}
