package com.sakuravillager.manga_translator.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "translation_history")
data class TranslationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val imagePath: String,
    val resultImagePath: String? = null,
    val sourceLanguage: String,
    val targetLanguage: String,
    val translatedAt: Long,
    val status: String,
    val coverImageUri: String? = null,
    val textRegions: String? = null,
)