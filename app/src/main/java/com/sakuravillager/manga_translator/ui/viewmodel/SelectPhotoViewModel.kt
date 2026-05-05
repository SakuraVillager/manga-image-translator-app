package com.sakuravillager.manga_translator.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakuravillager.manga_translator.data.preferences.AppPreferences
import com.sakuravillager.manga_translator.data.preferences.PreferencesProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class TranslationOptions(
    val translatorType: String = "gpt_compatible",
    val textDirection: String = "auto_detect_vertical",
    val detectorType: String = "default_contour",
    val ocrEngineType: String = "model_48px",
)

class SelectPhotoViewModel : ViewModel() {
    private val _selectedImages = MutableStateFlow<List<Uri>>(emptyList())
    val selectedImages: StateFlow<List<Uri>> = _selectedImages.asStateFlow()

    private val _translationOptions = MutableStateFlow(TranslationOptions())
    val translationOptions: StateFlow<TranslationOptions> = _translationOptions.asStateFlow()

    init {
        // Load default values from preferences
        viewModelScope.launch {
            val prefs = PreferencesProvider.repository.getPreferences().first()
            _translationOptions.value = TranslationOptions(
                translatorType = prefs.translatorType,
                textDirection = prefs.textDirection,
                detectorType = prefs.detectorType,
                ocrEngineType = prefs.ocrEngineType,
            )
        }
    }

    fun addImage(uri: Uri) {
        _selectedImages.value = _selectedImages.value + uri
    }

    fun removeImage(uri: Uri) {
        _selectedImages.value = _selectedImages.value.filter { it != uri }
    }

    fun clearSelection() {
        _selectedImages.value = emptyList()
    }

    fun updateTranslatorType(type: String) {
        _translationOptions.value = _translationOptions.value.copy(translatorType = type)
        viewModelScope.launch {
            PreferencesProvider.repository.updateTranslatorType(type)
        }
    }

    fun updateTextDirection(direction: String) {
        _translationOptions.value = _translationOptions.value.copy(textDirection = direction)
        viewModelScope.launch {
            PreferencesProvider.repository.updateTextDirection(direction)
        }
    }

    fun updateDetectorType(type: String) {
        _translationOptions.value = _translationOptions.value.copy(detectorType = type)
        viewModelScope.launch {
            PreferencesProvider.repository.updateDetectorType(type)
        }
    }

    fun updateOcrEngineType(type: String) {
        _translationOptions.value = _translationOptions.value.copy(ocrEngineType = type)
        viewModelScope.launch {
            PreferencesProvider.repository.updateOcrEngineType(type)
        }
    }
}
