package com.sakuravillager.manga_translator.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.data.model.ViewState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val viewState: ViewState = ViewState.TRANSLATED,
    val imageUris: List<String> = emptyList(),
    val selectedLanguage: String = "Japanese"
)

class WorkspaceViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(WorkspaceUiState())
    val uiState: StateFlow<WorkspaceUiState> = _uiState.asStateFlow()

    init {
        // Mock data - in real app would be passed via navigation args
        _uiState.value = WorkspaceUiState(
            viewState = ViewState.TRANSLATED,
            imageUris = listOf(
                "file:///android_asset/mock_manga_1.png",
                "file:///android_asset/mock_manga_2.png"
            ),
            selectedLanguage = "Japanese"
        )
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
