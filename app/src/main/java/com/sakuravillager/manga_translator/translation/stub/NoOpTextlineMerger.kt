package com.sakuravillager.manga_translator.translation.stub

import android.util.Log
import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextBlock

class NoOpTextlineMerger : TextlineMerger {
    override val name: String = "NoOpTextlineMerger"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    override suspend fun prepare() {
        Log.d(name, "NoOpTextlineMerger prepared")
        _isReady = true
    }

    override suspend fun release() {
        Log.d(name, "NoOpTextlineMerger released")
        _isReady = false
    }

    override suspend fun merge(
        textlines: List<Quadrilateral>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<TextBlock> {
        return emptyList()
    }
}
