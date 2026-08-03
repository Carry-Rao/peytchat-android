package cn.yzjtiantian.android.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * TDesign stroke icons extracted from the desktop project
 * (`src/components/tdesignIcons.ts`), rebuilt as Compose ImageVectors so the
 * mobile UI keeps the same icon language as the original.
 */
object TdesignIcons {

    private fun icon(
        name: String,
        block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        block()
    }.build()

    private fun androidx.compose.ui.graphics.vector.ImageVector.Builder.strokePath(
        d: String,
        fillRule: PathFillType = PathFillType.NonZero,
    ) {
        val nodes = androidx.compose.ui.graphics.vector.addPathNodes(d)
        addPath(
            pathData = nodes,
            pathFillType = fillRule,
            fill = SolidColor(Color.Black),
            fillAlpha = 0f,
            stroke = SolidColor(Color.Black),
            strokeAlpha = 1f,
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            strokeLineMiter = 4f,
        )
    }

    val MessageCircle: ImageVector = icon("message-circle") {
        strokePath(
            "M12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2C6.47715 2 2 6.47715 2 12C2 14.6624 3.04042 17.0817 4.73686 18.8737L3 22H12Z",
            PathFillType.EvenOdd,
        )
    }

    val Users: ImageVector = icon("users") {
        strokePath("M16 8C16 10.2091 14.2091 12 12 12C9.79086 12 8 10.2091 8 8C8 5.79086 9.79086 4 12 4C14.2091 4 16 5.79086 16 8Z")
        strokePath("M5 19C5 16.7909 6.79086 15 9 15H15C17.2091 15 19 16.7909 19 19V21H5V19Z")
        strokePath("M7 4C4.79086 4 3 5.79086 3 8C3 10.2091 4.79086 12 7 12C3.68629 12 1 14.6863 1 18V21M23 21V18C23 14.6863 20.3137 12 17 12C19.2091 12 21 10.2091 21 8C21 5.79086 19.2091 4 17 4")
    }

    val LayoutGrid: ImageVector = icon("layout-grid") {
        strokePath("M10 3H3V10H10V3Z")
        strokePath("M14 3H21V10H14V3Z")
        strokePath("M21 14H14V21H21V14Z")
        strokePath("M10 14H3V21H10V14Z")
    }

    val Inbox: ImageVector = icon("inbox") {
        strokePath("M2 4H22M2 4V20L22 20V4M2 4V7.44444L12 12.5L22 7.44444V4")
    }

    val Settings: ImageVector = icon("settings") {
        strokePath("M12.0001 2L20.6604 7V17L12.0001 22L3.33984 17V7L12.0001 2Z")
        strokePath("M16 12C16 14.2091 14.2091 16 12 16C9.79086 16 8 14.2091 8 12C8 9.79086 9.79086 8 12 8C14.2091 8 16 9.79086 16 12Z")
    }

    val Robot: ImageVector = icon("robot") {
        strokePath("M4 15H2M20 15H22M12 4C12.5523 4 13 3.55228 13 3C13 2.44772 12.5523 2 12 2C11.4477 2 11 2.44772 11 3C11 3.55228 11.4477 4 12 4ZM12 4V8M4 8H20V21H4V8Z")
        strokePath("M10 17H14M9 12.5C9 12.7761 8.77614 13 8.5 13C8.22386 13 8 12.7761 8 12.5C8 12.2239 8.22386 12 8.5 12C8.77614 12 9 12.2239 9 12.5ZM16 12.5C16 12.7761 15.7761 13 15.5 13C15.2239 13 15 12.7761 15 12.5C15 12.2239 15.2239 12 15.5 12C15.7761 12 16 12.2239 16 12.5Z")
    }

    val Terminal: ImageVector = icon("terminal") {
        strokePath("M13 19H21M3.5 17L8.5 12L3.5 7")
    }

    val LogOut: ImageVector = icon("log-out") {
        strokePath("M15.5 16.5L20 12L15.5 7.5M18.75 12H9")
        strokePath("M9 20.5H4V3.5H9")
    }

    val Bell: ImageVector = icon("bell") {
        strokePath("M5 8V13L3 16V19H21V16L19 13V8C19 4.13401 15.866 1 12 1C8.13401 1 5 4.13401 5 8Z")
        strokePath("M8.5 19H15.5C15.5 20.933 13.933 22.5 12 22.5C10.067 22.5 8.5 20.933 8.5 19Z")
    }

    val Send: ImageVector = icon("send") {
        strokePath("M5 12L2 20.5L21.5 12L2 3.5L5 12ZM5 12H10")
    }

    val Plus: ImageVector = icon("plus") {
        strokePath("M12 7.5V12M12 12L12 16.5M12 12L16.5 12M12 12L7.5 12")
    }

    val Hash: ImageVector = icon("hash") {
        strokePath("M21 15.5L3 15.5M21 8.5L3 8.5M16.5 3L14.5 21M9.5 3L7.5 21")
    }

    val ChevronDown: ImageVector = icon("chevron-down") {
        strokePath("M17.5 9.5L12 15L6.5 9.5")
    }

    val ChevronLeft: ImageVector = icon("chevron-left") {
        strokePath("M14.5 17.5L9 12L14.5 6.5")
    }
}
