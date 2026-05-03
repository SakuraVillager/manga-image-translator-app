package com.sakuravillager.manga_translator.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [TranslationHistoryEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationHistoryDao(): TranslationHistoryDao
}