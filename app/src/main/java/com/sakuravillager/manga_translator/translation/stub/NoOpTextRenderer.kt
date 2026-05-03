package com.sakuravillager.manga_translator.translation.stub

import android.graphics.Bitmap
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.data.config.RendererConfig

class NoOpTextRenderer : TextRenderer {
    override val name: String = "NoOpTextRenderer"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override suspend fun prepare() {
        Log.d(name, "NoOpTextRenderer prepared")
        _isReady = true
    }

    override suspend fun release() {
        Log.d(name, "NoOpTextRenderer released")
        _isReady = false
    }

    override suspend fun render(
        bitmap: Bitmap,
        textRegions: List<TextBlock>,
        config: RendererConfig,
    ): Bitmap {
        return bitmap
    }
}
