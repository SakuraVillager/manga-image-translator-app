package com.sakuravillager.manga_translator.translation.stub

import android.graphics.Bitmap
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig

class NoOpInpainter : Inpainter {
    override val name: String = "NoOpInpainter"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override suspend fun prepare() {
        Log.d(name, "NoOpInpainter prepared")
        _isReady = true
    }

    override suspend fun release() {
        Log.d(name, "NoOpInpainter released")
        _isReady = false
    }

    override suspend fun inpaint(bitmap: Bitmap, mask: Bitmap, config: InpainterConfig): Bitmap {
        return bitmap
    }
}
