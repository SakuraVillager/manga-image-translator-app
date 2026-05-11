package com.sakuravillager.manga_translator.translation.colorize

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.sakuravillager.manga_translator.translation.api.Colorizer
import com.sakuravillager.manga_translator.translation.data.config.ColorizerConfig
import com.sakuravillager.manga_translator.translation.data.config.ColorizerType

class BasicColorizer : Colorizer {
    override val name: String = "BasicColorizer"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override suspend fun prepare() {
        _isReady = true
    }

    override suspend fun release() {
        _isReady = false
    }

    override suspend fun colorize(bitmap: Bitmap, config: ColorizerConfig): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        if (config.colorizer == ColorizerType.NONE) return bitmap

        val source = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val matrix = ColorMatrix().apply {
            setSaturation(1.12f)
        }
        val contrast = 1.05f
        matrix.postConcat(
            ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, 0f,
                    0f, contrast, 0f, 0f, 0f,
                    0f, 0f, contrast, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        if (source !== bitmap) {
            source.recycle()
        }
        return result
    }
}