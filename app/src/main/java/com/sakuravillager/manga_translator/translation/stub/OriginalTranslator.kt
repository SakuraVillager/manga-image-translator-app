package com.sakuravillager.manga_translator.translation.stub

import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig

class OriginalTranslator : Translator {
    override val name: String = "Original (Keep)"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady
    override val supportedSourceLanguages: Set<String> = emptySet()
    override val supportedTargetLanguages: Set<String> = emptySet()

    override suspend fun prepare() {
        Log.d(name, "OriginalTranslator prepared")
        _isReady = true
    }

    override suspend fun release() {
        Log.d(name, "OriginalTranslator released")
        _isReady = false
    }

    override suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig,
    ): List<String> {
        return texts  // Return original text unchanged
    }

    override fun supportsLanguagePair(from: String, to: String): Boolean = true
}
