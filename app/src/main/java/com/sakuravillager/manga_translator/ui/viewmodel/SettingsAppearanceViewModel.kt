package com.sakuravillager.manga_translator.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.data.preferences.AppPreferences
import com.sakuravillager.manga_translator.data.preferences.PreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsAppearanceViewModel(
    private val preferencesRepository: PreferencesRepository
) : ViewModel() {

    val preferences: StateFlow<AppPreferences> = preferencesRepository.getPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppPreferences()
        )

    fun updateThemeMode(themeMode: String) {
        viewModelScope.launch {
            AppLogger.i("Settings", "Theme changed to $themeMode")
            preferencesRepository.updateThemeMode(themeMode)
        }
    }

    fun updateColorScheme(colorScheme: String) {
        viewModelScope.launch {
            preferencesRepository.updateColorScheme(colorScheme)
        }
    }

    fun updatePureBlackDarkMode(pureBlackDarkMode: Boolean) {
        viewModelScope.launch {
            preferencesRepository.updatePureBlackDarkMode(pureBlackDarkMode)
        }
    }

    fun updateAppLanguage(appLanguage: String) {
        viewModelScope.launch {
            preferencesRepository.updateAppLanguage(appLanguage)
        }
    }

    fun updateTabletInterface(tabletInterface: String) {
        viewModelScope.launch {
            preferencesRepository.updateTabletInterface(tabletInterface)
        }
    }
}
