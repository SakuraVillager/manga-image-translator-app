package com.sakuravillager.manga_translator.translation.ocr

import android.content.Context
import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig

/**
 * Manga OCR adapter placeholder.
 * Delegates to the existing 48px recognizer until a dedicated MOCR
 * model and implementation are integrated.
 */
class ModelMangaOCR(private val context: Context) : TextRecognizer {
    private val delegate = Model48pxTextRecognizer(context)

    override val name: String = "ModelMangaOCR"
    override val isReady: Boolean get() = delegate.isReady

    override suspend fun prepare() = delegate.prepare()
    override suspend fun release() = delegate.release()

    override suspend fun recognize(
        bitmap: Bitmap,
        textlines: List<Quadrilateral>,
        config: OcrConfig,
    ): List<Quadrilateral> = delegate.recognize(bitmap, textlines, config)
}
