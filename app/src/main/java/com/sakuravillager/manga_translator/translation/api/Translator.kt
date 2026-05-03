package com.sakuravillager.manga_translator.translation.api

import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig

interface Translator : PipelineModule {
    override val name: String
    val supportedSourceLanguages: Set<String>
    val supportedTargetLanguages: Set<String>

    suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig,
    ): List<String>

    fun supportsLanguagePair(from: String, to: String): Boolean
}
