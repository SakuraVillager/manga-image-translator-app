package com.sakuravillager.manga_translator.translation.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Result of the [letterbox] operation.
 *
 * @property bitmap The target-size bitmap with the original image centered and padded with black.
 * @property ratio  The scale factor applied to the original image (new_size / original_size).
 * @property dw     The total horizontal padding (right side).
 * @property dh     The total vertical padding (bottom side).
 */
data class LetterboxResult(
    val bitmap: Bitmap,
    val ratio: Float,
    val dw: Int,
    val dh: Int,
)

/**
 * Scales [bitmap] to fit within a [targetSize]×[targetSize] square while preserving aspect ratio,
 * then centers it on a black background canvas.
 *
 * The [stride] parameter is reserved for alignment padding requirements (e.g. model input stride).
 *
 * @param bitmap     The source bitmap to letterbox.
 * @param targetSize The size of the output square in pixels.
 * @param stride     Alignment stride (not currently applied; reserved for model compatibility).
 * @return A [LetterboxResult] containing the padded bitmap, scale ratio, and padding dimensions.
 */
fun letterbox(bitmap: Bitmap, targetSize: Int, stride: Int = 64): LetterboxResult {
    val oldWidth = bitmap.width
    val oldHeight = bitmap.height

    val ratio = min(targetSize.toFloat() / oldWidth, targetSize.toFloat() / oldHeight)
    val newWidth = (oldWidth * ratio).roundToInt()
    val newHeight = (oldHeight * ratio).roundToInt()

    val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    val result = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)

    val canvas = Canvas(result)
    canvas.drawColor(Color.BLACK)
    val left = (targetSize - newWidth) / 2f
    val top = (targetSize - newHeight) / 2f
    canvas.drawBitmap(scaled, left, top, null)
    scaled.recycle()

    val dw = targetSize - newWidth
    val dh = targetSize - newHeight

    return LetterboxResult(bitmap = result, ratio = ratio, dw = dw, dh = dh)
}

/**
 * Creates a new bitmap resized to the specified dimensions.
 *
 * @param bitmap The source bitmap.
 * @param width  Target width in pixels.
 * @param height Target height in pixels.
 * @return A new [Bitmap] of the requested size.
 */
fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
    return Bitmap.createScaledBitmap(bitmap, width, height, true)
}
