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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val viewState: ViewState = ViewState.TRANSLATED,
    val imageUris: List<String> = emptyList(),
    val selectedLanguage: String = "Japanese",
    val resultBitmap: Bitmap? = null,
    val progress: TranslationProgress = TranslationProgress.Idle,
    val isTranslating: Boolean = false,
    val errorMessage: String? = null,
    val noTextDetected: Boolean = false,
)

class WorkspaceViewModel(
    private val pipeline: TranslationPipeline,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()
    private var progressJob: Job? = null
    private var translationJob: Job? = null

    init {
        AppLogger.i("WorkspaceViewModel", "ViewModel initialized")
    }

    fun startTranslation(bitmap: Bitmap) {
        AppLogger.i(
            "WorkspaceViewModel",
            "startTranslation called with bitmap=${bitmap.width}x${bitmap.height}"
        )

        progressJob?.cancel()
        translationJob?.cancel()

        progressJob = viewModelScope.launch {
            pipeline.progress.collect { progress ->
                AppLogger.d("WorkspaceViewModel", "Progress update: $progress")
                _uiState.value = _uiState.value.copy(progress = progress)
            }
        }

        translationJob = viewModelScope.launch(Dispatchers.Default) {
            when (val result = pipeline.translate(bitmap)) {
                is TranslationResult.Success -> {
                    AppLogger.i(
                        "WorkspaceViewModel",
                        "Translation succeeded: regions=${result.textRegions.size}"
                    )
                    _uiState.value = _uiState.value.copy(
                        resultBitmap = result.bitmap,
                        errorMessage = null,
                        noTextDetected = false,
                        progress = TranslationProgress.Done(result.bitmap),
                        isTranslating = false,
                    )
                }
                is TranslationResult.NoText -> {
                    AppLogger.i("WorkspaceViewModel", "No text found")
                    _uiState.value = _uiState.value.copy(
                        resultBitmap = null,
                        errorMessage = null,
                        noTextDetected = true,
                        progress = TranslationProgress.Done(result.originalBitmap),
                        isTranslating = false,
                    )
                }
                is TranslationResult.Cancelled -> {
                    AppLogger.i("WorkspaceViewModel", "Translation cancelled")
                    _uiState.value = _uiState.value.copy(
                        errorMessage = "翻译已取消",
                        noTextDetected = false,
                        isTranslating = false,
                        progress = TranslationProgress.Idle,
                    )
                }
                is TranslationResult.Error -> {
                    AppLogger.e(
                        "WorkspaceViewModel",
                        "Translation failed: ${result.message}",
                        result.exception
                    )
                    _uiState.value = _uiState.value.copy(
                        errorMessage = result.message ?: "翻译失败",
                        noTextDetected = false,
                        isTranslating = false,
                        progress = TranslationProgress.Idle,
                    )
                }
            }

            progressJob?.cancel()
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

    fun cancelTranslation() {
        AppLogger.i("WorkspaceViewModel", "Translation cancelled by user")
        translationJob?.cancel()
        progressJob?.cancel()
        _uiState.value = _uiState.value.copy(progress = TranslationProgress.Idle)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, noTextDetected = false)
    }

    fun saveTranslation() {
        viewModelScope.launch {
            AppLogger.i("Workspace", "Translation saved")
            // Mock save - in real app would save to database
        }
    }
}
