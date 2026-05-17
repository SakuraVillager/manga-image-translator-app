package com.sakuravillager.manga_translator.translation.pipeline

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.data.DetectionResult
import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.inpaint.OriginalInpainter
import com.sakuravillager.manga_translator.translation.stub.NoOpTextDetector
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRecognizer
import com.sakuravillager.manga_translator.translation.stub.NoOpTextlineMerger
import com.sakuravillager.manga_translator.translation.translator.NoOpTranslator
import com.sakuravillager.manga_translator.translation.stub.NoOpMaskRefiner
import com.sakuravillager.manga_translator.translation.stub.NoOpTextRenderer
import com.sakuravillager.manga_translator.translation.api.Colorizer
import com.sakuravillager.manga_translator.translation.api.Upscaler
import com.sakuravillager.manga_translator.translation.data.config.ColorizerConfig
import com.sakuravillager.manga_translator.translation.data.config.UpscaleConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

private val noOpColorizer = object : Colorizer {
    override val name: String = "NoOpColorizer"
    override val isReady: Boolean get() = true
    override suspend fun prepare() {}
    override suspend fun release() {}
    override suspend fun colorize(bitmap: Bitmap, config: ColorizerConfig): Bitmap = bitmap
}

private val noOpUpscaler = object : Upscaler {
    override val name: String = "NoOpUpscaler"
    override val isReady: Boolean get() = true
    override suspend fun prepare() {}
    override suspend fun release() {}
    override suspend fun upscale(bitmap: Bitmap, config: UpscaleConfig): Bitmap = bitmap
}

@RunWith(AndroidJUnit4::class)
class TranslationPipelineTest {

    @Test
    fun testNoOpPipelineReturnsNoText() = runBlocking {
        val pipeline = TranslationPipeline(
            detector = NoOpTextDetector(),
            recognizer = NoOpTextRecognizer(),
            merger = NoOpTextlineMerger(),
            translator = NoOpTranslator(),
            colorizer = noOpColorizer,
            upscaler = noOpUpscaler,
            maskRefiner = NoOpMaskRefiner(),
            inpainter = OriginalInpainter(),
            renderer = NoOpTextRenderer(),
            config = TranslationConfig(),
        )
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = pipeline.translate(bitmap)
        Assert.assertTrue("Expected NoText", result is TranslationResult.NoText)
    }

    @Test
    fun testProgressFlow() = runBlocking {
        val pipeline = TranslationPipeline(
            detector = NoOpTextDetector(),
            recognizer = NoOpTextRecognizer(),
            merger = NoOpTextlineMerger(),
            translator = NoOpTranslator(),
            colorizer = noOpColorizer,
            upscaler = noOpUpscaler,
            maskRefiner = NoOpMaskRefiner(),
            inpainter = OriginalInpainter(),
            renderer = NoOpTextRenderer(),
            config = TranslationConfig(),
        )
        val progressEvents = mutableListOf<TranslationProgress>()
        val job = launch {
            pipeline.progress.collect { progressEvents.add(it) }
        }
        pipeline.translate(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888))
        job.cancel()
        Assert.assertTrue("Progress should update", progressEvents.isNotEmpty())
    }

    @Test
    fun testBrokenDetectorReturnsError() = runBlocking {
        val pipeline = TranslationPipeline(
            detector = BrokenTextDetector(),
            recognizer = NoOpTextRecognizer(),
            merger = NoOpTextlineMerger(),
            translator = NoOpTranslator(),
            colorizer = noOpColorizer,
            upscaler = noOpUpscaler,
            maskRefiner = NoOpMaskRefiner(),
            inpainter = OriginalInpainter(),
            renderer = NoOpTextRenderer(),
            config = TranslationConfig(),
        )
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val result = pipeline.translate(bitmap)
        Assert.assertTrue("Expected Error", result is TranslationResult.Error)
        val err = result as TranslationResult.Error
        Assert.assertEquals("Detector failed", err.message)
    }
}

class BrokenTextDetector(private val errorMsg: String = "Detector failed") : TextDetector {
    override val name: String = "BrokenTextDetector"
    override val isReady: Boolean = true
    override suspend fun prepare() {}
    override suspend fun release() {}
    override suspend fun detect(bitmap: Bitmap, config: DetectorConfig): DetectionResult {
        throw RuntimeException(errorMsg)
    }
}
