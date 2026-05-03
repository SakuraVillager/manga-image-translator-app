package com.sakuravillager.manga_translator.translation.data.config

data class TranslatorConfig(
    val translator: TranslatorType = TranslatorType.GPT_COMPATIBLE,
    val targetLanguage: String = "CHS",
    val skipLanguage: String? = null,
    val apiKey: String? = null,
    val apiBase: String? = null,
    val model: String? = null,
)
