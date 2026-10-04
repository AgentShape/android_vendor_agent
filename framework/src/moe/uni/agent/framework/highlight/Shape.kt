package moe.uni.agent.framework.highlight

import android.graphics.RectF

/** 动作效果的形状（ADR-0023 第 3 条），坐标为屏幕像素。 */
sealed interface Shape {
    /** 按元素操作：元素边框。 */
    data class Box(val rect: RectF) : Shape

    /** 按坐标点击：圆环。 */
    data class Ring(val x: Float, val y: Float) : Shape

    /** 滑动：起点圆环加轨迹。 */
    data class Trail(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float) : Shape

    /** 没有具体位置的动作：屏幕四周。 */
    object Edge : Shape
}
