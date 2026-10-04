package moe.uni.agent.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** material-icons-core 里没有、又没有 extended 模块可用的几个图标（Material Icons 的路径，Apache 2.0）。 */
object Glyphs {
    val ArrowUp = icon("ArrowUp", "M4,12l1.41,1.41L11,7.83V20h2V7.83l5.58,5.59L20,12l-8,-8 -8,8z")
    val Copy = icon(
        "Copy",
        "M16,1H4c-1.1,0 -2,0.9 -2,2v14h2V3h12V1zM19,5H8c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2V7" +
            "c0,-1.1 -0.9,-2 -2,-2zM19,21H8V7h11v14z",
    )
    val Terminal = icon(
        "Terminal",
        "M20,4H4C2.89,4 2,4.9 2,6v12c0,1.1 0.89,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.11,4 20,4zM20,18H4V8h16V18z" +
            "M18,17h-6v-2h6V17zM7.5,17l-1.41,-1.41L8.67,13l-2.59,-2.59L7.5,9l4,4L7.5,17z",
    )
    val Description = icon(
        "Description",
        "M14,2H6c-1.1,0 -1.99,0.9 -1.99,2L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM16,18H8v-2h8v2z" +
            "M16,14H8v-2h8v2zM13,9V3.5L18.5,9H13z",
    )
    val Smartphone = icon(
        "Smartphone",
        "M17,1.01L7,1c-1.1,0 -2,0.9 -2,2v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3c0,-1.1 -0.9,-1.99 -2,-1.99z" +
            "M17,19H7V5h10v14z",
    )
    val Code = icon(
        "Code",
        "M9.4,16.6L4.8,12l4.6,-4.6L8,6l-6,6 6,6 1.4,-1.4zM14.6,16.6l4.6,-4.6 -4.6,-4.6L16,6l6,6 -6,6 -1.4,-1.4z",
    )

    val Stop = icon("Stop", "M6,6h12v12H6z")
    val OpenInNew = icon(
        "OpenInNew",
        "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7z" +
            "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
    )
    val SmartToy = icon(
        "SmartToy",
        "M20,9V7c0,-1.1 -0.9,-2 -2,-2h-3c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5H6C4.9,5 4,5.9 4,7v2c-1.66,0 -3,1.34 -3,3" +
            "c0,1.66 1.34,3 3,3v4c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2v-4c1.66,0 3,-1.34 3,-3C23,10.34 21.66,9 20,9z" +
            "M7.5,11.5C7.5,10.67 8.17,10 9,10s1.5,0.67 1.5,1.5S9.83,13 9,13S7.5,12.33 7.5,11.5zM16,17H8v-2h8V17z" +
            "M15,13c-0.83,0 -1.5,-0.67 -1.5,-1.5S14.17,10 15,10s1.5,0.67 1.5,1.5S15.83,13 15,13z",
    )

    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()
}
