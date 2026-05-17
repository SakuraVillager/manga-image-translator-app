package com.sakuravillager.manga_translator.translation.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.data.DetectionResult
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.inpaint.NoneInpainter
import com.sakuravillager.manga_translator.translation.mask.OpenCVMaskRefiner
import com.sakuravillager.manga_translator.translation.merge.DefaultTextlineMerger
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.render.HorizontalTextRenderer
import com.sakuravillager.manga_translator.translation.translator.OriginalTranslator
import com.sakuravillager.manga_translator.translation.api.Colorizer
import com.sakuravillager.manga_translator.translation.api.Upscaler
import com.sakuravillager.manga_translator.translation.data.config.ColorizerConfig
import com.sakuravillager.manga_translator.translation.data.config.UpscaleConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumented test for [TranslationPipeline] using a mix of:
 * - [TextDetector] / [TextRecognizer] inline fakes (avoids ONNX model files)
 * - Real module implementations: [DefaultTextlineMerger], [OpenCVMaskRefiner],
 *   [NoneInpainter], [HorizontalTextRenderer]
 * - [OriginalTranslator] (no network dependency)
 */
@RunWith(AndroidJUnit4::class)
class TranslationPipelineE2ETest {

    @Test
    fun e2ePipeline_withRealModules_returnsSuccess() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()

        // ── Fake detector: returns a single quadrilateral ──
        // Yields to allow the progress collector to observe intermediate states.
        val fakeDetector = object : TextDetector {
            override val name: String = "FakeDetector"
            override var isReady: Boolean = false
                private set

            override suspend fun prepare() {
                isReady = true
                yield() // Let collector observe Loading state
            }

            override suspend fun release() {
                isReady = false
            }

            override suspend fun detect(
                bitmap: Bitmap,
                config: DetectorConfig,
            ): DetectionResult {
                val quad = Quadrilateral(
                    points = listOf(
                        PointF(10f, 10f),
                        PointF(180f, 10f),
                        PointF(180f, 40f),
                        PointF(10f, 40f),
                    ),
                )
                yield() // Let collector observe Processing("Detecting...")
                return DetectionResult(textlines = listOf(quad), rawMask = null, mask = null)
            }
        }

        // ── Fake recognizer: assigns recognised text to each quad ──
        val fakeRecognizer = object : TextRecognizer {
            override val name: String = "FakeRecognizer"
            override var isReady: Boolean = false
                private set

            override suspend fun prepare() {
                isReady = true
            }

            override suspend fun release() {
                isReady = false
            }

            override suspend fun recognize(
                bitmap: Bitmap,
                textlines: List<Quadrilateral>,
                config: OcrConfig,
            ): List<Quadrilateral> {
                yield() // Let collector observe Processing("Recognizing...")
                return textlines.map { it.copy(text = "Hello") }
            }
        }

        // ── Build pipeline ──
        val modelDl = ModelDownloadManager(context)
        val pipeline = TranslationPipeline(
            detector = fakeDetector,
            recognizer = fakeRecognizer,
            merger = DefaultTextlineMerger(),
            translator = OriginalTranslator(),
            colorizer = object : Colorizer {
                override val name = "NoOpColorizer"
                override val isReady get() = true
                override suspend fun prepare() {}
                override suspend fun release() {}
                override suspend fun colorize(bitmap: Bitmap, config: ColorizerConfig): Bitmap = bitmap
            },
            upscaler = object : Upscaler {
                override val name = "NoOpUpscaler"
                override val isReady get() = true
                override suspend fun prepare() {}
                override suspend fun release() {}
                override suspend fun upscale(bitmap: Bitmap, config: UpscaleConfig): Bitmap = bitmap
            },
            maskRefiner = OpenCVMaskRefiner(),
            inpainter = NoneInpainter(),
            renderer = HorizontalTextRenderer(context, modelDl),
            config = TranslationConfig(),
        )

        // ── Collect progress states ──
        val progressStates = mutableListOf<TranslationProgress>()
        val collectJob = launch {
            pipeline.progress.collect { progressStates.add(it) }
        }

        // ── Create 200x200 white test bitmap and run pipeline ──
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val result = pipeline.translate(bitmap)

        // Yield so the collector can process the final Done state
        yield()
        collectJob.cancel()

        // ══════════════════════════════════════════════
        // Assertions
        // ══════════════════════════════════════════════

        // 1. Pipeline completes with Success
        Assert.assertTrue(
            "Expected Success, got ${result::class.simpleName}",
            result is TranslationResult.Success,
        )
        val success = result as TranslationResult.Success
        Assert.assertNotNull("Result bitmap must not be null", success.bitmap)

        // 2. Progress flow: Idle → Loading → Processing* → Done
        Assert.assertTrue(
            "Should collect at least 3 progress states, got ${progressStates.size}",
            progressStates.size >= 3,
        )

        Assert.assertTrue(
            "First state should be Idle",
            progressStates[0] is TranslationProgress.Idle,
        )

        Assert.assertTrue(
            "Should contain at least one Loading state",
            progressStates.any { it is TranslationProgress.Loading },
        )

        Assert.assertTrue(
            "Should contain at least one Processing state",
            progressStates.any { it is TranslationProgress.Processing },
        )

        Assert.assertTrue(
            "Last state should be Done, got ${progressStates.last()::class.simpleName}",
            progressStates.last() is TranslationProgress.Done,
        )
    }
}
