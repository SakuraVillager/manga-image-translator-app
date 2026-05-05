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
                modelCtdUrl = preferences[PreferencesKeys.MODEL_CTD_URL],
                modelOcrUrl = preferences[PreferencesKeys.MODEL_OCR_URL],
                modelAlphabetUrl = preferences[PreferencesKeys.MODEL_ALPHABET_URL],
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

    suspend fun updateTextDirection(textDirection: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TEXT_DIRECTION] = textDirection
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

    suspend fun updateTranslatorType(translatorType: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSLATOR_TYPE] = translatorType
        }
    }

    suspend fun updateDetectorType(detectorType: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DETECTOR_TYPE] = detectorType
        }
    }

    suspend fun updateOcrEngineType(ocrEngineType: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.OCR_ENGINE_TYPE] = ocrEngineType
        }
    }

    suspend fun updateInpainterType(inpainterType: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.INPAINTER_TYPE] = inpainterType
        }
    }

    // ── Model URL overrides ─────────────────────────────────────────────────
    suspend fun updateModelCtdUrl(url: String?) {
        dataStore.edit { prefs -> if (url != null) prefs[PreferencesKeys.MODEL_CTD_URL] = url else prefs.remove(PreferencesKeys.MODEL_CTD_URL) }
    }
    suspend fun updateModelOcrUrl(url: String?) {
        dataStore.edit { prefs -> if (url != null) prefs[PreferencesKeys.MODEL_OCR_URL] = url else prefs.remove(PreferencesKeys.MODEL_OCR_URL) }
    }
    suspend fun updateModelAlphabetUrl(url: String?) {
        dataStore.edit { prefs -> if (url != null) prefs[PreferencesKeys.MODEL_ALPHABET_URL] = url else prefs.remove(PreferencesKeys.MODEL_ALPHABET_URL) }
    }

    companion object PreferencesKeys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val COLOR_SCHEME = stringPreferencesKey("color_scheme")
        val PURE_BLACK_DARK_MODE = booleanPreferencesKey("pure_black_dark_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val TABLET_INTERFACE = stringPreferencesKey("tablet_interface")
        val TEXT_DIRECTION = stringPreferencesKey("text_direction")
        val TRANSLATOR_TYPE = stringPreferencesKey("translator_type")
        val DETECTOR_TYPE = stringPreferencesKey("detector_type")
        val OCR_ENGINE_TYPE = stringPreferencesKey("ocr_engine_type")
        val INPAINTER_TYPE = stringPreferencesKey("inpainter_type")
        val API_KEY = stringPreferencesKey("api_key")
        val API_BASE = stringPreferencesKey("api_base")
        val MODEL_NAME = stringPreferencesKey("model_name")
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")
        val MODEL_CTD_URL = stringPreferencesKey("model_ctd_url")
        val MODEL_OCR_URL = stringPreferencesKey("model_ocr_url")
        val MODEL_ALPHABET_URL = stringPreferencesKey("model_alphabet_url")
    }
}