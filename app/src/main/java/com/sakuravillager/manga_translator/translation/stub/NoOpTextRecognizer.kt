package com.sakuravillager.manga_translator.translation.stub

import android.graphics.Bitmap
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig

class NoOpTextRecognizer : TextRecognizer {
    override val name: String = "NoOpTextRecognizer"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override suspend fun prepare() {
        Log.d(name, "NoOpTextRecognizer prepared")
        _isReady = true
    }

    override suspend fun release() {
        Log.d(name, "NoOpTextRecognizer released")
        _isReady = false
    }

    override suspend fun recognize(
        bitmap: Bitmap,
        textlines: List<Quadrilateral>,
        config: OcrConfig,
    ): List<Quadrilateral> {
        return textlines
    }
}
