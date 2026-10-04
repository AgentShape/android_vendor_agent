package moe.uni.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Material 3 Expressive：跟随壁纸动态取色，跟随系统深色模式（ADR-0026 第 4 条，系统应用保留 Material 配色）。
 * 颜色、字体、形状都取自主题的角色，不写死数值（ADR-0032 第 1 条）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive(), content = content)
}

/** 8dp 网格上的间距（ADR-0032 第 1 条）。 */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** 自适应布局（ADR-0032 第 2 条）。 */
object Layout {
    /** 窗口宽度达到 Expanded（840dp）时抽屉常驻。 */
    val ExpandedWidth = 840.dp
    /** 会话内容的最大宽度。 */
    val ChatMaxWidth = 840.dp
    /** 设置、任务页内容的最大宽度。 */
    val PageMaxWidth = 640.dp
}

/**
 * 分段列表（Expressive 的 segmented list）里第 [index] 段的形状：整组外侧用 large 圆角，段与段之间用 extraSmall 圆角。
 * 只有一段时四角都是 large。
 */
@Composable
fun segmentShape(index: Int, count: Int): Shape {
    val outer = MaterialTheme.shapes.large
    val inner = MaterialTheme.shapes.extraSmall
    return when {
        count <= 1 -> outer
        index == 0 -> outer.copyBottom(inner)
        index == count - 1 -> outer.copyTop(inner)
        else -> inner
    }
}

private fun CornerBasedShape.copyBottom(other: CornerBasedShape) = copy(bottomStart = other.bottomStart, bottomEnd = other.bottomEnd)
private fun CornerBasedShape.copyTop(other: CornerBasedShape) = copy(topStart = other.topStart, topEnd = other.topEnd)

/** 分段列表里段与段的间隔。 */
val SegmentGap = 2.dp

/** 空状态：Expressive 形状里的图标、标题、一句说明。会话页和任务页共用。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EmptyState(icon: ImageVector, title: String, detail: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = MaterialShapes.Cookie9Sided.toShape(), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
            modifier = Modifier.size(EMPTY_ICON_CONTAINER),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(EMPTY_ICON)) }
        }
        Spacer(Modifier.size(Spacing.xl))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = colors.onSurface, textAlign = TextAlign.Center)
        if (detail != null) {
            Spacer(Modifier.size(Spacing.s))
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

private val EMPTY_ICON_CONTAINER = 96.dp
private val EMPTY_ICON = 40.dp
