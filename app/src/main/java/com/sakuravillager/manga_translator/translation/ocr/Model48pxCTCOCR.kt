package com.sakuravillager.manga_translator.translation.ocr

import android.content.Context
import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig

/**
 * Adapter class named for the Python engine mapping. Delegates to the
 * existing `Model48pxTextRecognizer` implementation which contains the
 * CTC-based logic.
 */
class Model48pxCTCOCR(private val context: Context) : TextRecognizer {
    private val delegate = Model48pxTextRecognizer(context)

    override val name: String = "Model48pxCTCOCR"
    override val isReady: Boolean get() = delegate.isReady

    override suspend fun prepare() = delegate.prepare()
    override suspend fun release() = delegate.release()

    override suspend fun recognize(
        bitmap: Bitmap,
        textlines: List<Quadrilateral>,
        config: OcrConfig,
    ): List<Quadrilateral> = delegate.recognize(bitmap, textlines, config)
}
