package com.sakuravillager.manga_translator.data.preferences

data class AppPreferences(
    val themeMode: String = DEFAULT_THEME_MODE,
    val colorScheme: String = DEFAULT_COLOR_SCHEME,
    val pureBlackDarkMode: Boolean = DEFAULT_PURE_BLACK_DARK_MODE,
    val appLanguage: String = DEFAULT_APP_LANGUAGE,
    val tabletInterface: String = DEFAULT_TABLET_INTERFACE,
    val translator: String = DEFAULT_TRANSLATOR,
    val textDirection: String = DEFAULT_TEXT_DIRECTION,
    val textDetector: String = DEFAULT_TEXT_DETECTOR,
    val ocrEngine: String = DEFAULT_OCR_ENGINE,
    val imageRepair: String = DEFAULT_IMAGE_REPAIR
) {
    companion object {
        const val DEFAULT_THEME_MODE = "system"
        const val DEFAULT_COLOR_SCHEME = "default"
        const val DEFAULT_PURE_BLACK_DARK_MODE = false
        const val DEFAULT_APP_LANGUAGE = "en"
        const val DEFAULT_TABLET_INTERFACE = "auto"
        const val DEFAULT_TRANSLATOR = "GPT-4 Vision"
        const val DEFAULT_TEXT_DIRECTION = "auto_detect_vertical"
        const val DEFAULT_TEXT_DETECTOR = "default_contour"
        const val DEFAULT_OCR_ENGINE = "google_cloud_vision"
        const val DEFAULT_IMAGE_REPAIR = "inpaint_lama"
    }
}