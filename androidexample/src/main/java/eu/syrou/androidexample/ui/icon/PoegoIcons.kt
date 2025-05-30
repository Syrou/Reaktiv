import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object PoegoIcons {
    val Menu: ImageVector
        get() {
            return ImageVector.Builder(
                name = "menu",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    strokeLineWidth = 0f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                    strokeLineMiter = 4f,
                    pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero
                ) {
                    moveTo(3f, 18f)
                    horizontalLineToRelative(18f)
                    verticalLineToRelative(-2f)
                    horizontalLineTo(3f)
                    verticalLineTo(18f)
                    close()
                    moveTo(3f, 13f)
                    horizontalLineToRelative(18f)
                    verticalLineToRelative(-2f)
                    horizontalLineTo(3f)
                    verticalLineTo(13f)
                    close()
                    moveTo(3f, 6f)
                    verticalLineToRelative(2f)
                    horizontalLineToRelative(18f)
                    verticalLineTo(6f)
                    horizontalLineTo(3f)
                    close()
                }
            }.build()
        }

    val Notifications: ImageVector
        get() {
            return ImageVector.Builder(
                name = "notifications",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    strokeLineWidth = 0f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                    strokeLineMiter = 4f,
                    pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero
                ) {
                    moveTo(12f, 22f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    horizontalLineToRelative(-4f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    close()
                    moveTo(18f, 16f)
                    verticalLineToRelative(-5f)
                    curveToRelative(0f, -3.07f, -1.63f, -5.64f, -4.5f, -6.32f)
                    verticalLineTo(4f)
                    curveToRelative(0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                    reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
                    verticalLineToRelative(0.68f)
                    curveTo(7.64f, 5.36f, 6f, 7.92f, 6f, 11f)
                    verticalLineToRelative(5f)
                    lineToRelative(-2f, 2f)
                    verticalLineToRelative(1f)
                    horizontalLineToRelative(16f)
                    verticalLineToRelative(-1f)
                    lineToRelative(-2f, -2f)
                    close()
                    moveTo(16f, 17f)
                    horizontalLineTo(8f)
                    verticalLineToRelative(-6f)
                    curveToRelative(0f, -2.48f, 1.51f, -4.5f, 4f, -4.5f)
                    reflectiveCurveToRelative(4f, 2.02f, 4f, 4.5f)
                    verticalLineToRelative(6f)
                    close()
                }
            }.build()
        }

    val News: ImageVector
        get() {
            return ImageVector.Builder(
                name = "news",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    strokeLineWidth = 0f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                    strokeLineMiter = 4f,
                    pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero
                ) {
                    moveTo(20f, 2f)
                    horizontalLineTo(4f)
                    curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                    lineTo(2f, 22f)
                    lineToRelative(4f, -4f)
                    horizontalLineToRelative(14f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(4f)
                    curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(13f, 11f)
                    horizontalLineToRelative(-2f)
                    verticalLineTo(5f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(6f)
                    close()
                    moveTo(13f, 15f)
                    horizontalLineToRelative(-2f)
                    verticalLineToRelative(-2f)
                    horizontalLineToRelative(2f)
                    verticalLineToRelative(2f)
                    close()
                }
            }.build()
        }

    val Video: ImageVector
        get() {
            return ImageVector.Builder(
                name = "video",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    strokeLineWidth = 0f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                    strokeLineMiter = 4f,
                    pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero
                ) {
                    moveTo(8f, 5f)
                    verticalLineToRelative(14f)
                    lineToRelative(11f, -7f)
                    close()
                }
            }.build()
        }

    val LiveTv: ImageVector
        get() {
            return ImageVector.Builder(
                name = "live_tv",
                defaultWidth = 24.dp,
                defaultHeight = 24.dp,
                viewportWidth = 24f,
                viewportHeight = 24f
            ).apply {
                path(
                    fill = SolidColor(Color.Black),
                    strokeLineWidth = 0f,
                    strokeLineCap = StrokeCap.Butt,
                    strokeLineJoin = StrokeJoin.Miter,
                    strokeLineMiter = 4f,
                    pathFillType = androidx.compose.ui.graphics.PathFillType.NonZero
                ) {
                    moveTo(21f, 6f)
                    horizontalLineToRelative(-7.59f)
                    lineToRelative(3.29f, -3.29f)
                    lineTo(16f, 2f)
                    lineToRelative(-4f, 4f)
                    lineToRelative(-4f, -4f)
                    lineToRelative(-0.71f, 0.71f)
                    lineTo(10.59f, 6f)
                    horizontalLineTo(3f)
                    curveToRelative(-1.1f, 0f, -2f, 0.89f, -2f, 2f)
                    verticalLineToRelative(12f)
                    curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                    horizontalLineToRelative(18f)
                    curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                    verticalLineTo(8f)
                    curveToRelative(0f, -1.11f, -0.9f, -2f, -2f, -2f)
                    close()
                    moveTo(21f, 20f)
                    horizontalLineTo(3f)
                    verticalLineTo(8f)
                    horizontalLineToRelative(18f)
                    verticalLineToRelative(12f)
                    close()
                    moveTo(9f, 10f)
                    verticalLineToRelative(8f)
                    lineToRelative(7f, -4f)
                    close()
                }
            }.build()
        }
}