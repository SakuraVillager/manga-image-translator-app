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
                translatorType = preferences[PreferencesKeys.TRANSLATOR_TYPE] ?: AppPreferences.DEFAULT_TRANSLATOR_TYPE,
                textDirection = preferences[PreferencesKeys.TEXT_DIRECTION] ?: AppPreferences.DEFAULT_TEXT_DIRECTION,
                detectorType = preferences[PreferencesKeys.DETECTOR_TYPE] ?: AppPreferences.DEFAULT_DETECTOR_TYPE,
                ocrEngineType = preferences[PreferencesKeys.OCR_ENGINE_TYPE] ?: AppPreferences.DEFAULT_OCR_ENGINE_TYPE,
                inpainterType = preferences[PreferencesKeys.INPAINTER_TYPE] ?: AppPreferences.DEFAULT_INPAINTER_TYPE,
                apiKey = preferences[PreferencesKeys.API_KEY],
                apiBase = preferences[PreferencesKeys.API_BASE],
                modelName = preferences[PreferencesKeys.MODEL_NAME],
                targetLanguage = preferences[PreferencesKeys.TARGET_LANGUAGE] ?: AppPreferences.DEFAULT_TARGET_LANGUAGE,
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

    suspend fun updateApiKey(apiKey: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.API_KEY] = apiKey
        }
    }

    suspend fun updateApiBase(apiBase: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.API_BASE] = apiBase
        }
    }

    suspend fun updateModelName(modelName: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MODEL_NAME] = modelName
        }
    }

    suspend fun updateTargetLanguage(targetLanguage: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TARGET_LANGUAGE] = targetLanguage
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
        val TRANSLATOR_TYPE = stringPreferencesKey("translator_type")
        val DETECTOR_TYPE = stringPreferencesKey("detector_type")
        val OCR_ENGINE_TYPE = stringPreferencesKey("ocr_engine_type")
        val INPAINTER_TYPE = stringPreferencesKey("inpainter_type")
        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE = stringPreferencesKey("api_base")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")
    }
}