package com.sakuravillager.manga_translator.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.data.model.ViewState
import com.sakuravillager.manga_translator.translation.pipeline.TranslationPipeline
import com.sakuravillager.manga_translator.translation.pipeline.TranslationProgress
import com.sakuravillager.manga_translator.translation.pipeline.TranslationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val viewState: ViewState = ViewState.TRANSLATED,
    val imageUris: List<String> = emptyList(),
    val selectedLanguage: String = "Japanese",
    val progress: TranslationProgress = TranslationProgress.Idle
)

class WorkspaceViewModel(
    private val pipeline: TranslationPipeline,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        AppLogger.i("WorkspaceViewModel", "ViewModel initialized")
    }

    fun startTranslation(bitmap: Bitmap) {
        viewModelScope.launch {
            pipeline.progress.collect { progress ->
                _uiState.value = _uiState.value.copy(progress = progress)
            }
        }
        viewModelScope.launch {
            when (val result = pipeline.translate(bitmap)) {
                is TranslationResult.Success -> {
                    AppLogger.i("WorkspaceViewModel", "Translation succeeded")
                }
                is TranslationResult.NoText -> {
                    AppLogger.i("WorkspaceViewModel", "No text found")
                }
                is TranslationResult.Cancelled -> {
                    AppLogger.i("WorkspaceViewModel", "Translation cancelled")
                }
                is TranslationResult.Error -> {
                    AppLogger.e("WorkspaceViewModel", "Translation failed: ${result.message}")
                }
            }
        }
    }

    fun setViewState(state: ViewState) {
        viewModelScope.launch {
            AppLogger.i("Workspace", "View state changed to ${state.name}")
            _uiState.value = _uiState.value.copy(viewState = state)
        }
    }

    fun setSelectedLanguage(language: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(selectedLanguage = language)
        }
    }

    fun saveTranslation() {
        viewModelScope.launch {
            AppLogger.i("Workspace", "Translation saved")
            // Mock save - in real app would save to database
        }
    }
}
