package com.sakuravillager.manga_translator.data.model

data class TranslationHistory(
    val id: Long = 0,
    val title: String,
    val coverImageUri: String?,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translatedAt: Long,
    val status: TranslationStatus
)

enum class TranslationStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}