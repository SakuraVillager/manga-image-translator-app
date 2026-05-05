package com.sakuravillager.manga_translator.data.local

import android.content.Context
import androidx.room.Room
import com.sakuravillager.manga_translator.data.logging.AppLogger

object DatabaseProvider {
    private var database: AppDatabase? = null
    lateinit var dao: TranslationHistoryDao
        private set

    fun getDatabase(context: Context): AppDatabase {
        return database ?: synchronized(this) {
            database ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "manga_translator_database"
            ).addMigrations(AppDatabase.MIGRATION_1_2).build().also {
                database = it
                dao = it.translationHistoryDao()
                AppLogger.i("Database", "Database initialized")
            }
        }
    }
}