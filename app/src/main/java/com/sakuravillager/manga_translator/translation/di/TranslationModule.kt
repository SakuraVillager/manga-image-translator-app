package com.sakuravillager.manga_translator.translation.di

import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.api.Colorizer
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.api.Upscaler
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.colorize.BasicColorizer
import com.sakuravillager.manga_translator.translation.colorize.Mc2Colorizer
import com.sakuravillager.manga_translator.translation.data.config.DetectorType
import com.sakuravillager.manga_translator.translation.data.config.ColorizerType
import com.sakuravillager.manga_translator.translation.data.config.InpainterType
import com.sakuravillager.manga_translator.translation.data.config.OcrEngineType
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.data.config.UpscalerType
import com.sakuravillager.manga_translator.translation.detection.CtdTextDetector
import com.sakuravillager.manga_translator.translation.merge.DefaultTextlineMerger
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.ocr.Model48pxTextRecognizer
import com.sakuravillager.manga_translator.translation.ocr.Model48pxCTCOCR
import com.sakuravillager.manga_translator.translation.ocr.OcrDictionary
import com.sakuravillager.manga_translator.translation.ocr.Model48pxBeamRecognizer
import com.sakuravillager.manga_translator.translation.ocr.Model32pxBeamRecognizer
import com.sakuravillager.manga_translator.translation.ocr.ModelMangaOCR
// Note: avoid eager reference to OnnxSessionManager here to prevent native JNI loading
import com.sakuravillager.manga_translator.translation.pipeline.TranslationPipeline
import com.sakuravillager.manga_translator.translation.inpaint.AotInpainter
import com.sakuravillager.manga_translator.translation.inpaint.NoneInpainter
import com.sakuravillager.manga_translator.translation.inpaint.LamaLargeInpainter
import com.sakuravillager.manga_translator.translation.inpaint.LamaMPEInpainter
import com.sakuravillager.manga_translator.translation.inpaint.OriginalInpainter
import com.sakuravillager.manga_translator.translation.mask.CompleteMaskRefiner
import com.sakuravillager.manga_translator.translation.mask.OpenCVMaskRefiner
import com.sakuravillager.manga_translator.translation.render.HorizontalTextRenderer
import com.sakuravillager.manga_translator.translation.upscale.BasicUpscaler
import com.sakuravillager.manga_translator.translation.upscale.EsrganUpscaler
import com.sakuravillager.manga_translator.translation.stub.NoOpMaskRefiner
import com.sakuravillager.manga_translator.translation.stub.NoOpTextDetector
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRecognizer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRenderer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextlineMerger
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import com.sakuravillager.manga_translator.translation.translator.M2M100BigTranslator
import com.sakuravillager.manga_translator.translation.translator.M2M100Translator
import com.sakuravillager.manga_translator.translation.translator.NoOpTranslator
import com.sakuravillager.manga_translator.translation.translator.OriginalTranslator
import com.sakuravillager.manga_translator.translation.translator.CompositeTranslator
import com.sakuravillager.manga_translator.translation.translator.DeeplTranslator
import com.sakuravillager.manga_translator.translation.translator.GptTranslator
import com.sakuravillager.manga_translator.translation.translator.MBart50Translator
import com.sakuravillager.manga_translator.translation.translator.Qwen2BigTranslator
import com.sakuravillager.manga_translator.translation.translator.Qwen2Translator
import com.sakuravillager.manga_translator.translation.translator.NllbTranslator
import com.sakuravillager.manga_translator.translation.translator.NllbBigTranslator
import com.sakuravillager.manga_translator.translation.translator.SugoiTranslator
import com.sakuravillager.manga_translator.translation.translator.JparacrawlTranslator
import com.sakuravillager.manga_translator.translation.translator.JparacrawlBigTranslator
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
    // Register OnnxSessionManager lazily (its OrtEnvironment is lazy-initialized)
    single { com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager }
    single { ModelDownloadManager(androidContext()) }

    // Factory pattern for TextDetector — CTD or NoOp fallback
    single<TextDetector> {
        val config: TranslationConfig = get()
        when (config.detector.detector) {
            DetectorType.CTD -> {
                CtdTextDetector.initialize(get(), get(), androidContext())
                CtdTextDetector.getInstance()
            }
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

    // Pre-processing modules — config-driven selection
    single<Colorizer> {
        val config: TranslationConfig = get()
        when (config.colorizer.colorizer) {
            ColorizerType.MC2 -> Mc2Colorizer(
                modelDownloadManager = get(),
                sessionManager = get(),
                context = androidContext(),
            )
            else -> BasicColorizer()
        }
    }
    single<Upscaler> {
        val config: TranslationConfig = get()
        when (config.upscale.upscaler) {
            UpscalerType.BASIC -> BasicUpscaler()
            UpscalerType.NONE -> BasicUpscaler()
            else -> EsrganUpscaler(
                modelDownloadManager = get(),
                sessionManager = get(),
                context = androidContext(),
            )
        }
    }

    // Translator — conditional injection based on TranslatorType
    single<Translator> {
        val config: TranslationConfig = get()
        val httpClient: HttpClient = get()
        val modelDownloadManager: ModelDownloadManager = get()
        val onnxSessionManager = get<com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager>()
        buildTranslator(config, httpClient, modelDownloadManager, onnxSessionManager)
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
    factory<MaskRefiner> { CompleteMaskRefiner() }
    factory<Inpainter> {
        val config: TranslationConfig = get()
        when (config.inpainter.inpainter) {
            InpainterType.AOT ->
                AotInpainter(get(), get(), androidContext())
            InpainterType.LAMA_LARGE ->
                LamaLargeInpainter(get(), get(), androidContext())
            InpainterType.LAMA_MPE ->
                LamaMPEInpainter(get(), get(), androidContext())
            InpainterType.SIMPLE_FILL -> NoneInpainter()
            InpainterType.NONE -> OriginalInpainter()
            else -> LamaLargeInpainter(get(), get(), androidContext())
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
            colorizer = get<Colorizer>(),
            upscaler = get<Upscaler>(),
            maskRefiner = get<MaskRefiner>(),
            inpainter = get<Inpainter>(),
            renderer = get<TextRenderer>(),
            config = get<TranslationConfig>(),
        )
    }
}

private fun buildTranslator(
    config: TranslationConfig,
    httpClient: HttpClient,
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager,
): Translator {
    val chain = parseTranslatorChain(config, httpClient, modelDownloadManager, onnxSessionManager)
    if (chain.isNotEmpty()) {
        return CompositeTranslator(chain)
    }

    return createTranslator(config.translator.translator, httpClient, modelDownloadManager, onnxSessionManager)
}

private fun parseTranslatorChain(
    config: TranslationConfig,
    httpClient: HttpClient,
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager,
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
                translator = createTranslator(translatorType, httpClient, modelDownloadManager, onnxSessionManager),
                targetLanguage = targetLanguage,
            )
        }
}

private fun createTranslator(
    translatorType: TranslatorType,
    httpClient: HttpClient,
    modelDownloadManager: ModelDownloadManager,
    onnxSessionManager: com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager,
): Translator {
    return when (translatorType) {
        TranslatorType.GPT_COMPATIBLE -> GptTranslator(httpClient)
        TranslatorType.DEEPL -> DeeplTranslator(httpClient)
        // Qwen2 ONNX LLM translators — constructed with resolved dependencies
        TranslatorType.QWEN2 -> Qwen2Translator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        TranslatorType.QWEN2_BIG -> Qwen2BigTranslator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        TranslatorType.M2M100 -> M2M100Translator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        TranslatorType.M2M100_BIG -> M2M100BigTranslator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        // MBart50 multilingual encoder-decoder ONNX translator
        TranslatorType.MBART50 -> MBart50Translator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        // NLLB encoder-decoder ONNX translators
        TranslatorType.NLLB -> NllbTranslator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        TranslatorType.NLLB_BIG -> NllbBigTranslator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        // Sugoi V4 ONNX translator (manga-specialized JPN→ENG)
        TranslatorType.SUGOI -> SugoiTranslator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        // JParaCrawl ONNX translators (JPN↔ENG general-purpose)
        TranslatorType.JPARACRAWL -> JparacrawlTranslator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        TranslatorType.JPARACRAWL_BIG -> JparacrawlBigTranslator(
            modelDownloadManager = modelDownloadManager,
            onnxSessionManager = onnxSessionManager,
        )
        // Other offline translators delegate to TranslatorDispatch (no httpClient needed)
        else -> com.sakuravillager.manga_translator.translation.translator.createTranslator(translatorType)
    }
}

private fun buildTextRecognizer(config: TranslationConfig, context: Context): TextRecognizer {
    return try {
        when (config.ocr.ocrEngine) {
            OcrEngineType.MODEL_48PX_CTC -> Model48pxCTCOCR(context)
            OcrEngineType.MODEL_48PX -> Model48pxBeamRecognizer(context)
            OcrEngineType.MODEL_32PX -> Model32pxBeamRecognizer(context)
            OcrEngineType.MOCR -> ModelMangaOCR(context)
        }
    } catch (_: Throwable) {
        // If native ONNX runtime or other native deps are unavailable (unit test JVM),
        // fall back to a NoOp recognizer so tests and non-native environments continue.
        NoOpTextRecognizer()
    }
}
