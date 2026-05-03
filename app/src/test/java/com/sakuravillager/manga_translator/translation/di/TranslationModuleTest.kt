package com.sakuravillager.manga_translator.translation.di

import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.pipeline.TranslationPipeline
import com.sakuravillager.manga_translator.translation.stub.NoOpInpainter
import com.sakuravillager.manga_translator.translation.stub.NoOpMaskRefiner
import com.sakuravillager.manga_translator.translation.stub.NoOpTextDetector
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRecognizer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRenderer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextlineMerger
import com.sakuravillager.manga_translator.translation.stub.NoOpTranslator
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.check.checkModules
import org.koin.test.get
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TranslationModuleTest : KoinTest {

    @Before
    fun setUp() {
        startKoin {
            modules(translationModule)
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `verify all module definitions resolve`() {
        // checkModules manages its own Koin lifecycle internally.
        // Stop setUp() context first to avoid double-start conflict.
        stopKoin()
        checkModules {
            modules(translationModule)
        }
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
    fun `resolve TextlineMerger returns NoOpTextlineMerger`() {
        val merger = get<TextlineMerger>()
        assertTrue(merger is NoOpTextlineMerger)
    }

    @Test
    fun `resolve Translator returns NoOpTranslator`() {
        val translator = get<Translator>()
        assertTrue(translator is NoOpTranslator)
    }

    @Test
    fun `resolve MaskRefiner returns NoOpMaskRefiner`() {
        val refiner = get<MaskRefiner>()
        assertTrue(refiner is NoOpMaskRefiner)
    }

    @Test
    fun `resolve Inpainter returns NoOpInpainter`() {
        val inpainter = get<Inpainter>()
        assertTrue(inpainter is NoOpInpainter)
    }

    @Test
    fun `resolve TextRenderer returns NoOpTextRenderer`() {
        val renderer = get<TextRenderer>()
        assertTrue(renderer is NoOpTextRenderer)
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
