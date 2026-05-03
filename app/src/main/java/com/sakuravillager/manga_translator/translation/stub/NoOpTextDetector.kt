package com.sakuravillager.manga_translator.translation.stub

import android.graphics.Bitmap
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.data.DetectionResult
import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig

class NoOpTextDetector : TextDetector {
    override val name: String = "NoOpTextDetector"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override suspend fun prepare() {
        Log.d(name, "NoOpTextDetector prepared")
        _isReady = true
    }

    override suspend fun release() {
        Log.d(name, "NoOpTextDetector released")
        _isReady = false
    }

    override suspend fun detect(bitmap: Bitmap, config: DetectorConfig): DetectionResult {
        return DetectionResult(emptyList(), null, null)
    }
}
