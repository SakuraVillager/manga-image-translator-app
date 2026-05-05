package com.sakuravillager.manga_translator.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationHistoryDao {
    @Query("SELECT * FROM translation_history ORDER BY translatedAt DESC")
    fun getAll(): Flow<List<TranslationHistoryEntity>>

    @Query("SELECT * FROM translation_history WHERE id = :id")
    fun getById(id: Long): Flow<TranslationHistoryEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranslationHistoryEntity)

    @Delete
    suspend fun delete(entity: TranslationHistoryEntity)

    @Query("DELETE FROM translation_history WHERE imagePath LIKE '/mock/%'")
    suspend fun deleteAllMockData()
}