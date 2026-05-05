package com.sakuravillager.manga_translator.translation.di

import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.DetectorType
import com.sakuravillager.manga_translator.translation.data.config.InpainterType
import com.sakuravillager.manga_translator.translation.data.config.OcrEngineType
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.detection.CtdTextDetector
import com.sakuravillager.manga_translator.translation.merge.DefaultTextlineMerger
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.ocr.Model48pxTextRecognizer
import com.sakuravillager.manga_translator.translation.ocr.OcrDictionary
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import com.sakuravillager.manga_translator.translation.pipeline.TranslationPipeline
import com.sakuravillager.manga_translator.translation.inpaint.AotInpainter
import com.sakuravillager.manga_translator.translation.inpaint.SimpleFillInpainter
import com.sakuravillager.manga_translator.translation.mask.OpenCVMaskRefiner
import com.sakuravillager.manga_translator.translation.render.HorizontalTextRenderer
import com.sakuravillager.manga_translator.translation.stub.NoOpInpainter
import com.sakuravillager.manga_translator.translation.stub.NoOpMaskRefiner
import com.sakuravillager.manga_translator.translation.stub.NoOpTextDetector
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRecognizer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRenderer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextlineMerger
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import com.sakuravillager.manga_translator.translation.stub.NoOpTranslator
import com.sakuravillager.manga_translator.translation.stub.OriginalTranslator
import com.sakuravillager.manga_translator.translation.translator.CompositeTranslator
import com.sakuravillager.manga_translator.translation.translator.DeeplTranslator
import com.sakuravillager.manga_translator.translation.translator.GptTranslator
import com.sakuravillager.manga_translator.translation.translator.TranslatorStep
import com.sakuravillager.manga_translator.data.preferences.PreferencesProvider
import com.sakuravillager.manga_translator.translation.config.TranslationConfigMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val translationModule = module {
    // Infrastructure singletons
    single { OnnxSessionManager }
    single { ModelDownloadManager(androidContext()) }

    // Factory pattern for TextDetector — CTD or NoOp fallback
    single<TextDetector> {
        val config: TranslationConfig = get()
        when (config.detector.detector) {
            DetectorType.CTD -> CtdTextDetector(get(), get(), androidContext())
            else -> NoOpTextDetector()
        }
    }

    // TextRecognizer — CTC model loaded from assets, no ONNX session needed
    single<TextRecognizer> {
        val config: TranslationConfig = get()
        buildTextRecognizer(config, androidContext())
    }

    // TextlineMerger — always DefaultTextlineMerger (pure algorithm, no model dependency)
    single<TextlineMerger> { DefaultTextlineMerger() }

    // Translator — conditional injection based on TranslatorType
    single<Translator> {
        val config: TranslationConfig = get()
        buildTranslator(config, get())
    }

    // Ktor HttpClient (for GPT translator and future API integrations)
    single {
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
        }
    }

    // Real implementations replacing NoOp stubs
    factory<MaskRefiner> { OpenCVMaskRefiner() }
    factory<Inpainter> {
        val config: TranslationConfig = get()
        when (config.inpainter.inpainter) {
            InpainterType.AOT, InpainterType.LAMA_LARGE, InpainterType.LAMA_MPE ->
                AotInpainter(get(), get(), androidContext())
            InpainterType.SIMPLE_FILL -> SimpleFillInpainter()
            else -> SimpleFillInpainter()
        }
    }
    factory<TextRenderer> { HorizontalTextRenderer(androidContext(), get()) }

    // TranslationConfig — factory reading from DataStore via PreferencesProvider
    factory {
        val prefs = kotlinx.coroutines.runBlocking {
            PreferencesProvider.repository.getPreferences().first()
        }
        TranslationConfigMapper.map(prefs)
    }

    // Pipeline orchestrator — factory so it re-resolves config on each injection
    factory {
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

private fun buildTranslator(config: TranslationConfig, httpClient: HttpClient): Translator {
    val chain = parseTranslatorChain(config, httpClient)
    if (chain.isNotEmpty()) {
        return CompositeTranslator(chain)
    }

    return createTranslator(config.translator.translator, httpClient)
}

private fun parseTranslatorChain(
    config: TranslationConfig,
    httpClient: HttpClient,
): List<TranslatorStep> {
    val chainSpec = config.translator.translatorChain?.trim().orEmpty()
    if (chainSpec.isEmpty()) return emptyList()

    return chainSpec.split(';')
        .mapNotNull { item ->
            val token = item.trim()
            if (token.isEmpty()) return@mapNotNull null

            val parts = token.split(':', limit = 2)
            val translatorName = parts.getOrNull(0)?.trim().orEmpty()
            val targetLanguage = parts.getOrNull(1)?.trim().takeUnless { it.isNullOrEmpty() }
                ?: config.translator.targetLanguage
            val translatorType = runCatching { TranslatorType.valueOf(translatorName.uppercase()) }
                .getOrNull() ?: return@mapNotNull null

            TranslatorStep(
                translator = createTranslator(translatorType, httpClient),
                targetLanguage = targetLanguage,
            )
        }
}

private fun createTranslator(
    translatorType: TranslatorType,
    httpClient: HttpClient,
): Translator {
    return when (translatorType) {
        TranslatorType.GPT_COMPATIBLE -> GptTranslator(httpClient)
        TranslatorType.DEEPL -> DeeplTranslator(httpClient)
        TranslatorType.NONE -> NoOpTranslator()
        TranslatorType.ORIGINAL -> OriginalTranslator()
        // BAIDU, YOUDAO — keep NoOp stubs for now
        else -> NoOpTranslator()
    }
}

private fun buildTextRecognizer(config: TranslationConfig, context: Context): TextRecognizer {
    return when (config.ocr.ocrEngine) {
        OcrEngineType.MODEL_48PX,
        OcrEngineType.MODEL_48PX_CTC,
        OcrEngineType.MODEL_32PX,
        OcrEngineType.MOCR,
            -> Model48pxTextRecognizer(context)
    }
}
