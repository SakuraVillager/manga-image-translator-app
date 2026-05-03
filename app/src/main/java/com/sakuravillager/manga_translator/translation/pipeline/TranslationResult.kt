package com.sakuravillager.manga_translator.translation.pipeline

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.data.TextBlock

sealed class TranslationResult {
    data class Success(
        val bitmap: Bitmap,
        val textRegions: List<TextBlock>,
    ) : TranslationResult()

    data class NoText(val originalBitmap: Bitmap) : TranslationResult()
    object Cancelled : TranslationResult()
    data class Error(val message: String, val exception: Throwable) : TranslationResult()
}
