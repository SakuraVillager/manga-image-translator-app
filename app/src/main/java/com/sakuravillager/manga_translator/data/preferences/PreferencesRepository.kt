package com.sakuravillager.manga_translator.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PreferencesRepository(
    private val dataStore: DataStore<Preferences>
) {

    fun getPreferences(): Flow<AppPreferences> {
        return dataStore.data.map { preferences ->
            AppPreferences(
                themeMode = preferences[PreferencesKeys.THEME_MODE] ?: AppPreferences.DEFAULT_THEME_MODE,
                colorScheme = preferences[PreferencesKeys.COLOR_SCHEME] ?: AppPreferences.DEFAULT_COLOR_SCHEME,
                pureBlackDarkMode = preferences[PreferencesKeys.PURE_BLACK_DARK_MODE] ?: AppPreferences.DEFAULT_PURE_BLACK_DARK_MODE,
                appLanguage = preferences[PreferencesKeys.APP_LANGUAGE] ?: AppPreferences.DEFAULT_APP_LANGUAGE,
                tabletInterface = preferences[PreferencesKeys.TABLET_INTERFACE] ?: AppPreferences.DEFAULT_TABLET_INTERFACE,
                translator = preferences[PreferencesKeys.TRANSLATOR] ?: AppPreferences.DEFAULT_TRANSLATOR,
                textDirection = preferences[PreferencesKeys.TEXT_DIRECTION] ?: AppPreferences.DEFAULT_TEXT_DIRECTION,
                textDetector = preferences[PreferencesKeys.TEXT_DETECTOR] ?: AppPreferences.DEFAULT_TEXT_DETECTOR,
                ocrEngine = preferences[PreferencesKeys.OCR_ENGINE] ?: AppPreferences.DEFAULT_OCR_ENGINE,
                imageRepair = preferences[PreferencesKeys.IMAGE_REPAIR] ?: AppPreferences.DEFAULT_IMAGE_REPAIR
            )
        }
    }

    suspend fun updateThemeMode(themeMode: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = themeMode
        }
    }

    suspend fun updateColorScheme(colorScheme: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.COLOR_SCHEME] = colorScheme
        }
    }

    suspend fun updatePureBlackDarkMode(pureBlackDarkMode: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PURE_BLACK_DARK_MODE] = pureBlackDarkMode
        }
    }

    suspend fun updateAppLanguage(appLanguage: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = appLanguage
        }
    }

    suspend fun updateTabletInterface(tabletInterface: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TABLET_INTERFACE] = tabletInterface
        }
    }

    suspend fun updateTranslator(translator: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSLATOR] = translator
        }
    }

    suspend fun updateTextDirection(textDirection: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TEXT_DIRECTION] = textDirection
        }
    }

    suspend fun updateTextDetector(textDetector: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TEXT_DETECTOR] = textDetector
        }
    }

    suspend fun updateOcrEngine(ocrEngine: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.OCR_ENGINE] = ocrEngine
        }
    }

    suspend fun updateImageRepair(imageRepair: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IMAGE_REPAIR] = imageRepair
        }
    }

    companion object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COLOR_SCHEME = stringPreferencesKey("color_scheme")
        val PURE_BLACK_DARK_MODE = booleanPreferencesKey("pure_black_dark_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val TABLET_INTERFACE = stringPreferencesKey("tablet_interface")
        val TRANSLATOR = stringPreferencesKey("translator")
        val TEXT_DIRECTION = stringPreferencesKey("text_direction")
        val TEXT_DETECTOR = stringPreferencesKey("text_detector")
        val OCR_ENGINE = stringPreferencesKey("ocr_engine")
        val IMAGE_REPAIR = stringPreferencesKey("image_repair")
    }
}