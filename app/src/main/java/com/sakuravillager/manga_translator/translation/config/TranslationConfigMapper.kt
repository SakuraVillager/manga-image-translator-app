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

object TranslationConfigMapper {

    fun map(prefs: AppPreferences): TranslationConfig {
        return TranslationConfig(
            detector = DetectorConfig(detector = safeEnumValue(prefs.detectorType, DetectorType.CTD)),
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
            inpainter = InpainterConfig(inpainter = safeEnumValue(prefs.inpainterType, InpainterType.LAMA_LARGE)),
            renderer = RendererConfig(),
        )
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
