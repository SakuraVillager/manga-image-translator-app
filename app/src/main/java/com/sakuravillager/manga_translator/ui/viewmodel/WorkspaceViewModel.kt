package com.sakuravillager.manga_translator.ui.viewmodel

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakuravillager.manga_translator.data.local.DatabaseProvider
import com.sakuravillager.manga_translator.data.local.TranslationHistoryEntity
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.data.model.ViewState
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.model.DownloadStatus
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.pipeline.TranslationPipeline
import com.sakuravillager.manga_translator.translation.pipeline.TranslationProgress
import com.sakuravillager.manga_translator.translation.pipeline.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.java.KoinJavaComponent
import java.io.File
import java.io.FileOutputStream

data class WorkspaceUiState(
    val viewState: ViewState = ViewState.TRANSLATED,
    val imageUris: List<String> = emptyList(),
    val selectedLanguage: String = "Japanese",
    val resultBitmap: Bitmap? = null,
    val inputBitmap: Bitmap? = null,
    val progress: TranslationProgress = TranslationProgress.Idle,
    val isTranslating: Boolean = false,
    val errorMessage: String? = null,
    val noTextDetected: Boolean = false,
    val translationResult: TranslationResult.Success? = null,
)

class WorkspaceViewModel(
    private val pipeline: TranslationPipeline,
    private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    private val modelDownloadManager: ModelDownloadManager by lazy {
        KoinJavaComponent.get(ModelDownloadManager::class.java)
    }

    private var progressJob: Job? = null
    private var translationJob: Job? = null
    private var downloadJob: Job? = null

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
        downloadJob?.cancel()

        progressJob = viewModelScope.launch {
            pipeline.progress.collect { progress ->
                AppLogger.d("WorkspaceViewModel", "Progress update: $progress")
                _uiState.value = _uiState.value.copy(progress = progress)
            }
        }

        downloadJob = viewModelScope.launch {
            modelDownloadManager.downloadStatus.collect { status ->
                when (status) {
                    is DownloadStatus.Downloading -> {
                        _uiState.value = _uiState.value.copy(
                            progress = TranslationProgress.Downloading(
                                progress = status.progress,
                                message = "Downloading model... (${(status.progress * 100).toInt()}%)"
                            )
                        )
                    }
                    is DownloadStatus.Verifying -> {
                        _uiState.value = _uiState.value.copy(
                            progress = TranslationProgress.Downloading(
                                progress = 0.95f,
                                message = "Verifying model integrity..."
                            )
                        )
                    }
                    is DownloadStatus.Error -> {
                        _uiState.value = _uiState.value.copy(
                            progress = TranslationProgress.Error
                        )
                    }
                    else -> { /* Idle or Ready — pipeline progress handles it */ }
                }
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
                        inputBitmap = bitmap,
                        errorMessage = null,
                        noTextDetected = false,
                        progress = TranslationProgress.Done(result.bitmap),
                        isTranslating = false,
                        translationResult = result,
                    )
                    saveTranslation()
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
        downloadJob?.cancel()
        modelDownloadManager.cancelDownload()
        _uiState.value = _uiState.value.copy(progress = TranslationProgress.Idle)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, noTextDetected = false)
    }

    fun saveTranslation() {
        viewModelScope.launch {
            val state = _uiState.value
            val result = state.translationResult ?: run {
                AppLogger.w("Workspace", "saveTranslation called but no result available")
                return@launch
            }
            val inputBitmap = state.inputBitmap ?: run {
                AppLogger.w("Workspace", "saveTranslation called but no input bitmap available")
                return@launch
            }

            try {
                val timestamp = System.currentTimeMillis()
                val inputPath = saveBitmapToFile(inputBitmap, "input_$timestamp")
                val outputPath = saveBitmapToFile(result.bitmap, "result_$timestamp")
                val thumbPath = saveThumbnail(result.bitmap, "thumb_$timestamp")

                val entity = TranslationHistoryEntity(
                    imagePath = inputPath,
                    resultImagePath = outputPath,
                    sourceLanguage = state.selectedLanguage,
                    targetLanguage = "CHS",
                    translatedAt = timestamp,
                    status = "COMPLETED",
                    coverImageUri = outputPath,
                    textRegions = serializeTextRegions(result.textRegions),
                )

                DatabaseProvider.dao.insert(entity)
                AppLogger.i("Workspace", "Translation saved to history: id=${entity.id}")
            } catch (e: Exception) {
                AppLogger.e("Workspace", "Failed to save translation", e)
            }
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, filename: String): String {
        val dir = File(appContext.filesDir, "translations")
        dir.mkdirs()
        val file = File(dir, "$filename.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    private fun saveThumbnail(bitmap: Bitmap, filename: String): String {
        val scale = minOf(200f / bitmap.width, 200f / bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val thumb = Bitmap.createScaledBitmap(bitmap, w, h, true)
        return saveBitmapToFile(thumb, filename)
    }

    private fun serializeTextRegions(regions: List<TextBlock>): String {
        return regions.joinToString("||") { "${it.text}|-|${it.translation}" }
    }
}
