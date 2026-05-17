package com.sakuravillager.manga_translator.translation.inpaint

import android.graphics.Bitmap
import android.graphics.Color
import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig

class NoneInpainter : Inpainter {
    override val name: String = "NoneInpainter"
    override var isReady: Boolean = false
        private set

    override suspend fun prepare() {
        isReady = true
    }

    override suspend fun release() {
        isReady = false
    }

    override suspend fun inpaint(bitmap: Bitmap, mask: Bitmap, config: InpainterConfig): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: bitmap

        val width = minOf(bitmap.width, mask.width)
        val height = minOf(bitmap.height, mask.height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val maskPixel = mask.getPixel(x, y)
                if (Color.alpha(maskPixel) > 0 || Color.red(maskPixel) > 0 || Color.green(maskPixel) > 0 || Color.blue(maskPixel) > 0) {
                    result.setPixel(x, y, Color.WHITE)
                }
            }
        }

        return result
    }
}