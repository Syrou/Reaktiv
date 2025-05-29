package eu.syrou.androidexample.ui.util

import android.graphics.Bitmap
import android.graphics.Color
import coil.size.Size
import coil.transform.Transformation

class BorderRemoverTransformation(private val tolerance: Int = 10) : Transformation {

    override val cacheKey: String = "border_remover_transformation"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height

        var topY = 0
        var bottomY = height - 1
        var leftX = 0
        var rightX = width - 1
        for (y in 0..<height) {
            if (!isRowBlack(input, y, tolerance)) {
                topY = y
                break
            }
        }
        for (y in height - 1 downTo 0) {
            if (!isRowBlack(input, y, tolerance)) {
                bottomY = y
                break
            }
        }
        for (x in 0..<width) {
            if (!isColumnBlack(input, x, tolerance)) {
                leftX = x
                break
            }
        }
        for (x in width - 1 downTo 0) {
            if (!isColumnBlack(input, x, tolerance)) {
                rightX = x
                break
            }
        }
        return Bitmap.createBitmap(input, leftX, topY, rightX - leftX + 1, bottomY - topY + 1)
    }

    private fun isRowBlack(bitmap: Bitmap, y: Int, tolerance: Int): Boolean {
        for (x in 0..<bitmap.width) {
            if (!isPixelBlack(bitmap.getPixel(x, y), tolerance)) {
                return false
            }
        }
        return true
    }

    private fun isColumnBlack(bitmap: Bitmap, x: Int, tolerance: Int): Boolean {
        for (y in 0..<bitmap.height) {
            if (!isPixelBlack(bitmap.getPixel(x, y), tolerance)) {
                return false
            }
        }
        return true
    }

    private fun isPixelBlack(color: Int, tolerance: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return r <= tolerance && g <= tolerance && b <= tolerance
    }
}