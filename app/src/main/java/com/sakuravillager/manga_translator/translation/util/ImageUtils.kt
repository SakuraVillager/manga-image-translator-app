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
 * then places it at the top-left corner on a black background canvas.
 * Padding is applied to the right and bottom only (matching Python CTD letterbox).
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
    canvas.drawBitmap(scaled, 0f, 0f, null)  // top-left aligned, padding on right/bottom
    scaled.recycle()

    val dw = targetSize - newWidth
    val dh = targetSize - newHeight

    return LetterboxResult(bitmap = result, ratio = ratio, dw = dw, dh = dh)
}

fun load_image(img: Bitmap): Pair<Bitmap, Bitmap?> {
    val source = if (img.config == Bitmap.Config.ARGB_8888) img else img.copy(Bitmap.Config.ARGB_8888, false)
    return if (source.hasAlpha() || source.config == Bitmap.Config.ALPHA_8) {
        val alpha_ch = source.extractAlpha()
        val background = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(background)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        background to alpha_ch
    } else {
        val background = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(background)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, 0f, 0f, null)
        background to null
    }
}

fun dump_image(img_pil: Bitmap, img: Bitmap, alpha_ch: Bitmap? = null): Bitmap {
    val width = img.width
    val height = img.height
    val result = if (img_pil.width == width && img_pil.height == height) {
        if (img_pil.config == Bitmap.Config.ARGB_8888) img_pil.copy(Bitmap.Config.ARGB_8888, true) else img_pil.copy(Bitmap.Config.ARGB_8888, true)
    } else {
        Bitmap.createScaledBitmap(img_pil, width, height, true).copy(Bitmap.Config.ARGB_8888, true)
    }

    val source = if (alpha_ch != null) {
        combine_alpha(img, alpha_ch)
    } else {
        if (img.config == Bitmap.Config.ARGB_8888) img else img.copy(Bitmap.Config.ARGB_8888, false)
    }

    val canvas = Canvas(result)
    canvas.drawBitmap(source, 0f, 0f, null)
    if (source !== img && source !== img_pil) {
        source.recycle()
    }
    return result
}

private fun combine_alpha(img: Bitmap, alpha_ch: Bitmap): Bitmap {
    val width = img.width
    val height = img.height
    val rgbSource = if (img.width == width && img.height == height) {
        if (img.config == Bitmap.Config.ARGB_8888) img else img.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        Bitmap.createScaledBitmap(img, width, height, true).copy(Bitmap.Config.ARGB_8888, false)
    }
    val alphaSource = if (alpha_ch.width == width && alpha_ch.height == height) {
        if (alpha_ch.config == Bitmap.Config.ARGB_8888) alpha_ch else alpha_ch.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        Bitmap.createScaledBitmap(alpha_ch, width, height, true).copy(Bitmap.Config.ARGB_8888, false)
    }

    val rgbPixels = IntArray(width * height)
    val alphaPixels = IntArray(width * height)
    rgbSource.getPixels(rgbPixels, 0, width, 0, 0, width, height)
    alphaSource.getPixels(alphaPixels, 0, width, 0, 0, width, height)

    val outPixels = IntArray(width * height)
    for (index in outPixels.indices) {
        val alpha = (alphaPixels[index] ushr 24) and 0xFF
        outPixels[index] = (alpha shl 24) or (rgbPixels[index] and 0x00FFFFFF)
    }

    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    result.setPixels(outPixels, 0, width, 0, 0, width, height)

    if (rgbSource !== img) {
        rgbSource.recycle()
    }
    if (alphaSource !== alpha_ch) {
        alphaSource.recycle()
    }
    return result
}

fun resize_keep_aspect(img: Bitmap, size: Int): Bitmap {
    val ratio = size.toFloat() / maxOf(img.width, img.height)
    val new_width = (img.width * ratio).roundToInt().coerceAtLeast(1)
    val new_height = (img.height * ratio).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(img, new_width, new_height, true)
}

fun image_resize(image: Bitmap, width: Int? = null, height: Int? = null): Bitmap {
    if (width == null && height == null) return image

    val (targetWidth, targetHeight) = when {
        width == null -> {
            val ratio = height!!.toFloat() / image.height.toFloat()
            val newWidth = (image.width * ratio).toInt()
            newWidth to height
        }
        else -> {
            val ratio = width.toFloat() / image.width.toFloat()
            width to (image.height * ratio).toInt()
        }
    }
    return Bitmap.createScaledBitmap(image, targetWidth, targetHeight, true)
}

/**
 * Downsamples [bitmap] so that its longest side does not exceed [maxSize],
 * preserving aspect ratio. If both dimensions are already within [maxSize],
 * the original bitmap is returned unchanged.
 *
 * This is used at pipeline entry to prevent OOM and speed up processing
 * when the input image is very large (e.g., > 2048px on any side).
 *
 * @param bitmap  The source bitmap to downsample.
 * @param maxSize The maximum allowed pixel size for the longest dimension.
 * @return The downsampled bitmap, or the original if already within limits.
 */
fun downsample_to_max_size(bitmap: Bitmap, max_size: Int): Bitmap {
    val width = bitmap.width
    val height = bitmap.height
    val maxDimension = maxOf(width, height)
    if (maxDimension <= max_size) return bitmap

    val scale = max_size.toFloat() / maxDimension
    val newWidth = (width * scale).toInt().coerceAtLeast(1)
    val newHeight = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
}
