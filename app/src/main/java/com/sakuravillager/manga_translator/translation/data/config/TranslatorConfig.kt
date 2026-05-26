package com.sakuravillager.manga_translator.translation.data.config

data class TranslatorConfig(
    val translator: TranslatorType = TranslatorType.GPT_COMPATIBLE,
    val targetLanguage: String = "CHS",
    val noTextLangSkip: Boolean = false,
    val skipLanguage: String? = null,
    val apiKey: String? = null,
    val apiBase: String? = null,
    val model: String? = null,
    val translatorChain: String? = null,
    val selectiveTranslation: String? = null,
    val useMtpe: Boolean = false,
    val contextPages: Int = 0,
    val prevContext: String? = null,
)
