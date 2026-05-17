package com.sakuravillager.manga_translator.translation.inpaint

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig

class OriginalInpainter : Inpainter {
    override val name: String = "OriginalInpainter"
    override var isReady: Boolean = false
        private set

    override suspend fun prepare() {
        isReady = true
    }

    override suspend fun release() {
        isReady = false
    }

    override suspend fun inpaint(bitmap: Bitmap, mask: Bitmap, config: InpainterConfig): Bitmap {
        return bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: bitmap
    }
}