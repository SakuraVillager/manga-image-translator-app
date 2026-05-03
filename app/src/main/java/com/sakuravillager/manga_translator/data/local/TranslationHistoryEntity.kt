package com.sakuravillager.manga_translator.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_history")
data class TranslationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val imagePath: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translatedAt: Long,
    val status: String,
    val coverImageUri: String?
)