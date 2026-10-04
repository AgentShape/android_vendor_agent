package moe.uni.agent.framework.highlight

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View

/** 参照 Apple Intelligence 的流光（ADR-0023 第 2 条）：炫彩 SweepGradient 沿边框旋转，外加一层模糊外发光。 */
internal class GlowView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private var shape: Shape? = null
    private var startTime = 0L
    private var onEnd: (() -> Unit)? = null
    private val matrix = Matrix()
    private val path = Path()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 上一个效果还没结束就被新的替换；onEnd 在最后一帧（空白）画完后调用。 */
    fun start(shape: Shape, onEnd: () -> Unit) {
        this.shape = shape
        this.onEnd = onEnd
        startTime = SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val shape = shape ?: return
        val t = SystemClock.uptimeMillis() - startTime
        if (t >= DURATION_MS) {
            this.shape = null
            // 再画一帧空白，确保隐藏前屏幕上没有残留。
            invalidate()
            post { onEnd?.invoke(); onEnd = null }
            return
        }
        val alpha = when {
            t < FADE_IN_MS -> t.toFloat() / FADE_IN_MS
            t > DURATION_MS - FADE_OUT_MS -> (DURATION_MS - t).toFloat() / FADE_OUT_MS
            else -> 1f
        }
        path.reset()
        val cx: Float
        val cy: Float
        when (shape) {
            is Shape.Box -> {
                val r = RectF(shape.rect).apply { inset(-BOX_OUTSET_DP * density, -BOX_OUTSET_DP * density) }
                val radius = minOf(BOX_RADIUS_DP * density, r.width() / 2, r.height() / 2)
                path.addRoundRect(r, radius, radius, Path.Direction.CW)
                cx = r.centerX(); cy = r.centerY()
            }
            is Shape.Ring -> {
                path.addCircle(shape.x, shape.y, RING_RADIUS_DP * density, Path.Direction.CW)
                cx = shape.x; cy = shape.y
            }
            is Shape.Trail -> {
                path.addCircle(shape.fromX, shape.fromY, TRAIL_RADIUS_DP * density, Path.Direction.CW)
                path.moveTo(shape.fromX, shape.fromY)
                path.lineTo(shape.toX, shape.toY)
                cx = (shape.fromX + shape.toX) / 2; cy = (shape.fromY + shape.toY) / 2
            }
            Shape.Edge -> {
                val inset = EDGE_INSET_DP * density
                path.addRoundRect(RectF(inset, inset, width - inset, height - inset),
                    EDGE_RADIUS_DP * density, EDGE_RADIUS_DP * density, Path.Direction.CW)
                cx = width / 2f; cy = height / 2f
            }
        }
        matrix.setRotate(t * DEGREES_PER_MS, cx, cy)
        glow.shader = SweepGradient(cx, cy, COLORS, null).apply { setLocalMatrix(matrix) }
        val scale = if (shape == Shape.Edge) EDGE_SCALE else 1f
        for ((width, blur, layerAlpha) in LAYERS) {
            glow.strokeWidth = width * density * scale
            glow.maskFilter = if (blur > 0) BlurMaskFilter(blur * density * scale, BlurMaskFilter.Blur.NORMAL) else null
            glow.alpha = (255 * alpha * layerAlpha).toInt()
            canvas.drawPath(path, glow)
        }
        postInvalidateOnAnimation()
    }

    companion object {
        /** 效果总时长（按用户要求由 1 秒缩短，ADR-0023 第 2 条）。 */
        const val DURATION_MS = 500L
        private const val FADE_IN_MS = 80L
        private const val FADE_OUT_MS = 150L

        /** 渐变每秒转 1.5 圈。 */
        private const val DEGREES_PER_MS = 0.54f

        private const val BOX_OUTSET_DP = 4f
        private const val BOX_RADIUS_DP = 20f
        private const val RING_RADIUS_DP = 28f
        private const val TRAIL_RADIUS_DP = 20f
        private const val EDGE_INSET_DP = 6f
        private const val EDGE_RADIUS_DP = 36f
        private const val EDGE_SCALE = 2f

        /** 紫、粉、蓝、珊瑚红、橙，首尾相接。 */
        private val COLORS = intArrayOf(
            Color.parseColor("#BC82F3"), Color.parseColor("#F5B9EA"), Color.parseColor("#8D9FFF"),
            Color.parseColor("#FF6778"), Color.parseColor("#FFBA71"), Color.parseColor("#C686FF"),
            Color.parseColor("#BC82F3"),
        )

        /** 由外到内：宽的模糊外发光、中等的柔光、清晰的描边（宽度和模糊半径单位为 dp）。 */
        private val LAYERS = listOf(Triple(12f, 14f, 0.7f), Triple(6f, 5f, 0.9f), Triple(2.5f, 0f, 1f))
    }
}
