package com.sakuravillager.manga_translator.translation.translator.common

class InvalidServerResponse(message: String) : RuntimeException(message)

class MissingAPIKeyException(message: String) : RuntimeException(message)

class LanguageUnsupportedException(
    languageCode: String,
    translator: String? = null,
    supportedLanguages: List<String>? = null,
) : RuntimeException(
    buildString {
        val translatorName = translator ?: "chosen translator"
        append("Language not supported for $translatorName: \"$languageCode\"")
        if (!supportedLanguages.isNullOrEmpty()) {
            append(". Supported languages: \"${supportedLanguages.joinToString(",")}\"")
        }
    },
)
