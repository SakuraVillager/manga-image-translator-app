package com.sakuravillager.manga_translator.translation.ocr

import android.content.Context
import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig

/**
 * Beam-search 32px recognizer adapter.
 * Fallbacks to the 48px recognizer for now; proper 32px model support
 * will be added later when the model is available in assets.
 */
class Model32pxBeamRecognizer(private val context: Context) : TextRecognizer {
    private val delegate = Model48pxTextRecognizer(context)

    override val name: String = "Model32pxBeamRecognizer"
    override val isReady: Boolean get() = delegate.isReady

    override suspend fun prepare() = delegate.prepare()
    override suspend fun release() = delegate.release()

    override suspend fun recognize(
        bitmap: Bitmap,
        textlines: List<Quadrilateral>,
        config: OcrConfig,
    ): List<Quadrilateral> = delegate.recognize(bitmap, textlines, config)
}
