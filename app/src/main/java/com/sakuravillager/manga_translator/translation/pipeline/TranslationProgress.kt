package com.sakuravillager.manga_translator.translation.pipeline

import android.graphics.Bitmap

sealed class TranslationProgress {
    object Idle : TranslationProgress()
    data class Loading(val message: String) : TranslationProgress()
    data class Processing(val message: String, val progress: Float) : TranslationProgress()
    data class Downloading(val progress: Float, val message: String) : TranslationProgress()
    data class Done(val result: Bitmap) : TranslationProgress()
    object Error : TranslationProgress()
}
