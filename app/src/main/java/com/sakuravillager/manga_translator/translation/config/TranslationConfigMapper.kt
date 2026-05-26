package com.sakuravillager.manga_translator.translation.config

import com.sakuravillager.manga_translator.data.preferences.AppPreferences
import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig
import com.sakuravillager.manga_translator.translation.data.config.DetectorType
import com.sakuravillager.manga_translator.translation.data.config.ColorizerConfig
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig
import com.sakuravillager.manga_translator.translation.data.config.InpainterType
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig
import com.sakuravillager.manga_translator.translation.data.config.OcrEngineType
import com.sakuravillager.manga_translator.translation.data.config.UpscaleConfig
import com.sakuravillager.manga_translator.translation.data.config.RendererConfig
import com.sakuravillager.manga_translator.translation.data.config.RendererType
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import com.sakuravillager.manga_translator.translation.data.TextDirection

object TranslationConfigMapper {

    fun map(prefs: AppPreferences): TranslationConfig {
        return TranslationConfig(
            detector = DetectorConfig(detector = mapDetectorType(prefs.detectorType)),
            ocr = OcrConfig(ocrEngine = safeEnumValue(prefs.ocrEngineType, OcrEngineType.MODEL_48PX)),
            colorizer = ColorizerConfig(),
            upscale = UpscaleConfig(),
            translator = TranslatorConfig(
                translator = safeEnumValue(prefs.translatorType, TranslatorType.GPT_COMPATIBLE),
                targetLanguage = prefs.targetLanguage,
                apiKey = prefs.apiKey,
                apiBase = prefs.apiBase,
                model = prefs.modelName,
            ),
            inpainter = InpainterConfig(inpainter = mapInpainterType(prefs.inpainterType)),
            renderer = RendererConfig(direction = mapTextDirection(prefs.textDirection)),
        )
    }

    /** Maps legacy detector type strings to the correct enum.
     *  "default_contour" and "default" both map to CTD (Python's default detector). */
    private fun mapDetectorType(value: String): DetectorType {
        return when (value.lowercase()) {
            "default_contour", "default" -> DetectorType.CTD
            else -> safeEnumValue(value, DetectorType.CTD)
        }
    }

    /** Maps legacy inpainter type strings to the correct enum.
     *  "inpaint_lama" maps to LAMA_LARGE (Python's default inpainter). */
    private fun mapInpainterType(value: String): InpainterType {
        return when (value.lowercase()) {
            "inpaint_lama" -> InpainterType.LAMA_LARGE
            else -> safeEnumValue(value, InpainterType.LAMA_LARGE)
        }
    }

    private fun mapTextDirection(value: String): TextDirection {
        return when (value.lowercase()) {
            "horizontal" -> TextDirection.HORIZONTAL
            "vertical" -> TextDirection.VERTICAL
            "rtl", "horizontal_rtl" -> TextDirection.HORIZONTAL_RTL
            else -> TextDirection.AUTO
        }
    }

    private inline fun <reified T : Enum<T>> safeEnumValue(name: String, default: T): T {
        return try {
            enumValueOf<T>(name.uppercase())
        } catch (e: IllegalArgumentException) {
            System.err.println("TranslationConfigMapper: Unknown '$name' for ${T::class.simpleName}, using default $default")
            default
        }
    }
}
