package com.sakuravillager.manga_translator.data.preferences

data class AppPreferences(
    val themeMode: String = DEFAULT_THEME_MODE,
    val colorScheme: String = DEFAULT_COLOR_SCHEME,
    val pureBlackDarkMode: Boolean = DEFAULT_PURE_BLACK_DARK_MODE,
    val appLanguage: String = DEFAULT_APP_LANGUAGE,
    val tabletInterface: String = DEFAULT_TABLET_INTERFACE,
    val translatorType: String = DEFAULT_TRANSLATOR_TYPE,
    val textDirection: String = DEFAULT_TEXT_DIRECTION,
    val detectorType: String = DEFAULT_DETECTOR_TYPE,
    val ocrEngineType: String = DEFAULT_OCR_ENGINE_TYPE,
    val inpainterType: String = DEFAULT_INPAINTER_TYPE,
    val apiKey: String? = null,
    val apiBase: String? = null,
    val modelName: String? = null,
    val targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
) {
    companion object {
        const val DEFAULT_THEME_MODE = "system"
        const val DEFAULT_COLOR_SCHEME = "default"
        const val DEFAULT_PURE_BLACK_DARK_MODE = false
        const val DEFAULT_APP_LANGUAGE = "en"
        const val DEFAULT_TABLET_INTERFACE = "auto"
        const val DEFAULT_TRANSLATOR = "GPT-4 Vision"       // KEPT for backward compat
        const val DEFAULT_TRANSLATOR_TYPE = "gpt_compatible"
        const val DEFAULT_TEXT_DIRECTION = "auto_detect_vertical"
        const val DEFAULT_TEXT_DETECTOR = "default_contour"  // KEPT for backward compat
        const val DEFAULT_DETECTOR_TYPE = "default_contour"
        const val DEFAULT_OCR_ENGINE = "google_cloud_vision" // KEPT for backward compat
        const val DEFAULT_OCR_ENGINE_TYPE = "google_cloud_vision"
        const val DEFAULT_IMAGE_REPAIR = "inpaint_lama"      // KEPT for backward compat
        const val DEFAULT_INPAINTER_TYPE = "inpaint_lama"
        const val DEFAULT_TARGET_LANGUAGE = "CHS"
    }
}