package com.sakuravillager.manga_translator.translation.stub

import android.graphics.Bitmap
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.data.TextBlock

class NoOpMaskRefiner : MaskRefiner {
    override val name: String = "NoOpMaskRefiner"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override suspend fun prepare() {
        Log.d(name, "NoOpMaskRefiner prepared")
        _isReady = true
    }

    override suspend fun release() {
        Log.d(name, "NoOpMaskRefiner released")
        _isReady = false
    }

    override suspend fun refine(
        textRegions: List<TextBlock>,
        bitmap: Bitmap,
        rawMask: Bitmap?,
        kernelSize: Int,
        dilationOffset: Int,
    ): Bitmap {
        if (rawMask != null) {
            return rawMask
        }
        return Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    }
}
