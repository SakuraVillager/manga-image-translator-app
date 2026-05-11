package com.sakuravillager.manga_translator.translation.di

import android.app.Application
import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig
import com.sakuravillager.manga_translator.translation.data.config.DetectorType
import com.sakuravillager.manga_translator.translation.data.config.InpainterConfig
import com.sakuravillager.manga_translator.translation.data.config.InpainterType
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig
import com.sakuravillager.manga_translator.translation.data.config.OcrEngineType
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.inpaint.SimpleFillInpainter
import com.sakuravillager.manga_translator.translation.mask.CompleteMaskRefiner
import com.sakuravillager.manga_translator.translation.merge.DefaultTextlineMerger
import com.sakuravillager.manga_translator.translation.pipeline.TranslationPipeline
import com.sakuravillager.manga_translator.translation.render.HorizontalTextRenderer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextDetector
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRecognizer
import com.sakuravillager.manga_translator.translation.translator.GptTranslator
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranslationModuleTest : KoinTest {

    @Before
    fun setUp() {
        // Override module to use NoOp-friendly config for detector and OCR,
        // avoiding ONNX Runtime dependency in pure unit tests.
        val overrideModule = module(override = true) {
            single<TranslationConfig> {
                TranslationConfig(
                    detector = DetectorConfig(detector = DetectorType.NONE),
                    ocr = OcrConfig(ocrEngine = OcrEngineType.MODEL_32PX),
                    inpainter = InpainterConfig(inpainter = InpainterType.SIMPLE_FILL),
                )
            }
        }
        startKoin {
            androidContext(Application())
            modules(translationModule, overrideModule)
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `verify all module definitions resolve`() {
        assertNotNull(get<TextDetector>())
        assertNotNull(get<TextRecognizer>())
        assertNotNull(get<TextlineMerger>())
        assertNotNull(get<Translator>())
        assertNotNull(get<MaskRefiner>())
        assertNotNull(get<Inpainter>())
        assertNotNull(get<TextRenderer>())
        assertNotNull(get<TranslationPipeline>())
    }

    @Test
    fun `resolve TextDetector returns NoOpTextDetector`() {
        val detector = get<TextDetector>()
        assertTrue(detector is NoOpTextDetector)
    }

    @Test
    fun `resolve TextRecognizer returns NoOpTextRecognizer`() {
        val recognizer = get<TextRecognizer>()
        assertTrue(recognizer is NoOpTextRecognizer)
    }

    @Test
    fun `resolve TextlineMerger returns DefaultTextlineMerger`() {
        val merger = get<TextlineMerger>()
        assertTrue(merger is DefaultTextlineMerger)
    }

    @Test
    fun `resolve Translator returns GptTranslator by default`() {
        val translator = get<Translator>()
        assertTrue(translator is GptTranslator, "Expected GptTranslator, got ${translator::class.simpleName}")
    }

    @Test
    fun `resolve MaskRefiner returns CompleteMaskRefiner`() {
        val refiner = get<MaskRefiner>()
        assertTrue(refiner is CompleteMaskRefiner)
    }

    @Test
    fun `resolve Inpainter returns SimpleFillInpainter`() {
        val inpainter = get<Inpainter>()
        assertTrue(inpainter is SimpleFillInpainter)
    }

    @Test
    fun `resolve TextRenderer returns HorizontalTextRenderer`() {
        val renderer = get<TextRenderer>()
        assertTrue(renderer is HorizontalTextRenderer)
    }

    @Test
    fun `resolve TranslationPipeline`() {
        val pipeline = get<TranslationPipeline>()
        assertNotNull(pipeline)
    }

    @Test
    fun `resolve TranslationConfig has defaults`() {
        val config = get<TranslationConfig>()
        assertNotNull(config)
        assertEquals(3, config.kernelSize)
        assertEquals(20, config.maskDilationOffset)
    }
}
