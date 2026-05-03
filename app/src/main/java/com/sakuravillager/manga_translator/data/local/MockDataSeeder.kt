package com.sakuravillager.manga_translator.data.local

import android.content.Context
import kotlinx.coroutines.flow.first

object MockDataSeeder {
    private const val PREFS_NAME = "mock_data_seeder"
    private const val KEY_SEEDED = "seeded"

    suspend fun seedIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SEEDED, false)) {
            return
        }

        val database = DatabaseProvider.getDatabase(context)
        val dao = database.translationHistoryDao()

        // Check if database is empty
        val existingData = dao.getAll().first()
        if (existingData.isNotEmpty()) {
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            return
        }

        // Insert mock data
        val mockData = listOf(
            TranslationHistoryEntity(
                imagePath = "/mock/image1.jpg",
                sourceLanguage = "Japanese",
                targetLanguage = "English",
                translatedAt = System.currentTimeMillis() - 86400000, // 1 day ago
                status = "COMPLETED",
                coverImageUri = null
            ),
            TranslationHistoryEntity(
                imagePath = "/mock/image2.jpg",
                sourceLanguage = "Korean",
                targetLanguage = "English",
                translatedAt = System.currentTimeMillis() - 172800000, // 2 days ago
                status = "COMPLETED",
                coverImageUri = null
            ),
            TranslationHistoryEntity(
                imagePath = "/mock/image3.jpg",
                sourceLanguage = "Chinese",
                targetLanguage = "English",
                translatedAt = System.currentTimeMillis() - 259200000, // 3 days ago
                status = "COMPLETED",
                coverImageUri = null
            )
        )

        mockData.forEach { entity ->
            dao.insert(entity)
        }

        prefs.edit().putBoolean(KEY_SEEDED, true).apply()
    }
}