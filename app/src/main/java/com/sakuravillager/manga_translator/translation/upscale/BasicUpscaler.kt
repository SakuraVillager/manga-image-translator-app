package com.sakuravillager.manga_translator.translation.upscale

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.api.Upscaler
import com.sakuravillager.manga_translator.translation.data.config.UpscaleConfig

class BasicUpscaler : Upscaler {
    override val name: String = "BasicUpscaler"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override suspend fun prepare() {
        _isReady = true
    }

    override suspend fun release() {
        _isReady = false
    }

    override suspend fun upscale(bitmap: Bitmap, config: UpscaleConfig): Bitmap {
        val ratio = config.upscaleRatio ?: return bitmap
        if (ratio <= 1 || bitmap.width <= 0 || bitmap.height <= 0) return bitmap

        val width = (bitmap.width * ratio).coerceAtLeast(1)
        val height = (bitmap.height * ratio).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}