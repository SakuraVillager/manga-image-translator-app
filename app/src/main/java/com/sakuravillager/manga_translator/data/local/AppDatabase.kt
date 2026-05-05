package com.sakuravillager.manga_translator.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TranslationHistoryEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun translationHistoryDao(): TranslationHistoryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE translation_history ADD COLUMN resultImagePath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE translation_history ADD COLUMN textRegions TEXT DEFAULT NULL")
            }
        }
    }
}