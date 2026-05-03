package com.sakuravillager.manga_translator.translation.config

import android.util.Log
import com.sakuravillager.manga_translator.data.preferences.AppPreferences
import com.sakuravillager.manga_translator.translation.data.TextDirection
import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig
import com.sakuravillager.manga_translator.translation.data.config.DetectorType
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig
import com.sakuravillager.manga_translator.translation.data.config.InpainterType
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig
import com.sakuravillager.manga_translator.translation.data.config.OcrEngineType
import com.sakuravillager.manga_translator.translation.data.config.RendererConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType

object TranslationConfigMapper {
    private const val TAG = "TranslationConfigMapper"

    fun AppPreferences.toTranslationConfig(): TranslationConfig {
        return TranslationConfig(
            detector = DetectorConfig(detector = parseDetectorType(detectorType)),
            ocr = OcrConfig(ocrEngine = parseOcrEngineType(ocrEngineType)),
            translator = TranslatorConfig(
                translator = parseTranslatorType(translatorType),
                targetLanguage = targetLanguage,
                apiKey = apiKey,
                apiBase = apiBase,
                model = modelName,
            ),
            inpainter = InpainterConfig(inpainter = parseInpainterType(inpainterType)),
            renderer = RendererConfig(direction = parseTextDirection(textDirection)),
        )
    }

    private fun parseDetectorType(value: String): DetectorType {
        return when (value) {
            "default_contour" -> DetectorType.CTD
            "default" -> DetectorType.DEFAULT
            "dbconvnext" -> DetectorType.DBCONVNEXT
            "craft" -> DetectorType.CRAFT
            "paddle" -> DetectorType.PADDLE
            "none" -> DetectorType.NONE
            else -> {
                Log.w(TAG, "Unknown detector type: $value, using default CTD")
                DetectorType.CTD
            }
        }
    }

    private fun parseOcrEngineType(value: String): OcrEngineType {
        return when (value) {
            "google_cloud_vision" -> OcrEngineType.MODEL_48PX
            "32px" -> OcrEngineType.MODEL_32PX
            "48px" -> OcrEngineType.MODEL_48PX
            "48px_ctc" -> OcrEngineType.MODEL_48PX_CTC
            "mocr" -> OcrEngineType.MOCR
            else -> {
                Log.w(TAG, "Unknown OCR engine: $value, using default MODEL_48PX")
                OcrEngineType.MODEL_48PX
            }
        }
    }

    private fun parseTranslatorType(value: String): TranslatorType {
        return when (value) {
            "GPT-4 Vision" -> TranslatorType.GPT_COMPATIBLE
            "gpt_compatible" -> TranslatorType.GPT_COMPATIBLE
            "deepl" -> TranslatorType.DEEPL
            "baidu" -> TranslatorType.BAIDU
            "youdao" -> TranslatorType.YOUDAO
            "none" -> TranslatorType.NONE
            "original" -> TranslatorType.ORIGINAL
            else -> {
                Log.w(TAG, "Unknown translator: $value, using default GPT_COMPATIBLE")
                TranslatorType.GPT_COMPATIBLE
            }
        }
    }

    private fun parseInpainterType(value: String): InpainterType {
        return when (value) {
            "inpaint_lama" -> InpainterType.LAMA_LARGE
            "lama_large" -> InpainterType.LAMA_LARGE
            "lama_mpe" -> InpainterType.LAMA_MPE
            "aot" -> InpainterType.AOT
            "simple_fill" -> InpainterType.SIMPLE_FILL
            "none" -> InpainterType.NONE
            else -> {
                Log.w(TAG, "Unknown inpainter: $value, using default LAMA_LARGE")
                InpainterType.LAMA_LARGE
            }
        }
    }

    private fun parseTextDirection(value: String): TextDirection {
        return when (value) {
            "auto_detect_vertical" -> TextDirection.AUTO
            "auto" -> TextDirection.AUTO
            "horizontal" -> TextDirection.HORIZONTAL
            "vertical" -> TextDirection.VERTICAL
            "horizontal_rtl" -> TextDirection.HORIZONTAL_RTL
            else -> {
                Log.w(TAG, "Unknown text direction: $value, using default AUTO")
                TextDirection.AUTO
            }
        }
    }
}
