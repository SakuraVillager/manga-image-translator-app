package com.sakuravillager.manga_translator.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakuravillager.manga_translator.data.local.TranslationHistoryDao
import com.sakuravillager.manga_translator.data.local.TranslationHistoryEntity
import com.sakuravillager.manga_translator.data.model.TranslationHistory
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.data.model.TranslationStatus
import com.sakuravillager.manga_translator.data.model.ViewState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class HistoryViewModel(
    private val dao: TranslationHistoryDao = com.sakuravillager.manga_translator.data.local.DatabaseProvider.dao
) : ViewModel() {

    private val _historyList = MutableStateFlow<List<TranslationHistory>>(emptyList())
    val historyList: StateFlow<List<TranslationHistory>> = _historyList.asStateFlow()

    private val _currentHistory = MutableStateFlow<TranslationHistory?>(null)
    val currentHistory: StateFlow<TranslationHistory?> = _currentHistory.asStateFlow()

    private val _viewState = MutableStateFlow(ViewState.TRANSLATED)
    val viewState: StateFlow<ViewState> = _viewState.asStateFlow()

    init {
        loadHistoryList()
    }

    private fun loadHistoryList() {
        viewModelScope.launch {
            dao.getAll().collect { entities ->
                AppLogger.i("History", "History list loaded: ${entities.size} items")
                _historyList.value = entities.map { it.toDomainModel() }
            }
        }
    }

    fun loadHistory(id: Long) {
        viewModelScope.launch {
            try {
                AppLogger.i("History", "Loading history detail: id=$id")
                dao.getById(id).collect { entity ->
                    _currentHistory.value = entity?.toDomainModel()
                }
            } catch (e: Exception) {
                AppLogger.e("History", "Failed to load history: ${e.message}", e)
            }
        }
    }

    fun deleteHistory(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Find the entity by collecting from DB (single shot)
                val entity = dao.getById(id).let { flow ->
                    var result: TranslationHistoryEntity? = null
                    flow.collect { result = it; return@collect }
                    result
                } ?: return@launch

                // 2. Delete associated image files
                listOfNotNull(
                    entity.imagePath,
                    entity.resultImagePath,
                    entity.coverImageUri,
                ).forEach { path ->
                    try { File(path).delete() } catch (_: Exception) {}
                }

                // 3. Delete from Room database
                dao.delete(entity)
                AppLogger.i("History", "Deleted history: id=$id")
            } catch (e: Exception) {
                AppLogger.e("History", "Failed to delete history: ${e.message}", e)
            }
        }
    }

    fun setViewState(state: ViewState) {
        _viewState.value = state
    }

    private fun TranslationHistoryEntity.toDomainModel(): TranslationHistory {
        return TranslationHistory(
            id = id,
            title = imagePath.substringAfterLast("/").substringBeforeLast("."),
            coverImageUri = coverImageUri,
            imagePath = imagePath,
            resultImagePath = resultImagePath,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            translatedAt = translatedAt,
            status = try {
                TranslationStatus.valueOf(status)
            } catch (e: Exception) {
                TranslationStatus.PENDING
            }
        )
    }
}
