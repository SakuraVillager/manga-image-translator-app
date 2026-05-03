package com.sakuravillager.manga_translator.translation.di

import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.DetectorType
import com.sakuravillager.manga_translator.translation.data.config.OcrEngineType
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.detection.CtdTextDetector
import com.sakuravillager.manga_translator.translation.merge.DefaultTextlineMerger
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.ocr.Model48pxTextRecognizer
import com.sakuravillager.manga_translator.translation.ocr.OcrDictionary
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import com.sakuravillager.manga_translator.translation.pipeline.TranslationPipeline
import com.sakuravillager.manga_translator.translation.stub.NoOpInpainter
import com.sakuravillager.manga_translator.translation.stub.NoOpMaskRefiner
import com.sakuravillager.manga_translator.translation.stub.NoOpTextDetector
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRecognizer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRenderer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextlineMerger
import com.sakuravillager.manga_translator.translation.stub.NoOpTranslator
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val translationModule = module {
    // Infrastructure singletons
    single { OnnxSessionManager }
    single { ModelDownloadManager(androidContext()) }
    single { OcrDictionary }

    // Factory pattern for TextDetector — CTD or NoOp fallback
    single<TextDetector> {
        val config: TranslationConfig = get()
        when (config.detector.detector) {
            DetectorType.CTD -> CtdTextDetector(get(), get(), androidContext())
            else -> NoOpTextDetector()
        }
    }

    // Factory pattern for TextRecognizer — Model48px or NoOp fallback
    single<TextRecognizer> {
        val config: TranslationConfig = get()
        when (config.ocr.ocrEngine) {
            OcrEngineType.MODEL_48PX -> Model48pxTextRecognizer(get(), get(), androidContext())
            else -> NoOpTextRecognizer()
        }
    }

    // TextlineMerger — always DefaultTextlineMerger (pure algorithm, no model dependency)
    single<TextlineMerger> { DefaultTextlineMerger() }

    // Unimplemented modules (remain NoOp stubs)
    single<Translator> { NoOpTranslator() }
    single<MaskRefiner> { NoOpMaskRefiner() }
    single<Inpainter> { NoOpInpainter() }
    single<TextRenderer> { NoOpTextRenderer() }

    // Default config
    single { TranslationConfig() }

    // Pipeline orchestrator
    single {
        TranslationPipeline(
            detector = get<TextDetector>(),
            recognizer = get<TextRecognizer>(),
            merger = get<TextlineMerger>(),
            translator = get<Translator>(),
            maskRefiner = get<MaskRefiner>(),
            inpainter = get<Inpainter>(),
            renderer = get<TextRenderer>(),
            config = get<TranslationConfig>(),
        )
    }
}
