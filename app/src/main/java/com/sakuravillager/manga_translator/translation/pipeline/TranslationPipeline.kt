package com.sakuravillager.manga_translator.translation.pipeline

import android.graphics.Bitmap
import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.data.TranslationContext
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class TranslationPipeline(
    private val detector: TextDetector,
    private val recognizer: TextRecognizer,
    private val merger: TextlineMerger,
    private val translator: Translator,
    private val maskRefiner: MaskRefiner,
    private val inpainter: Inpainter,
    private val renderer: TextRenderer,
    private val config: TranslationConfig,
) {
    private val _progress = MutableStateFlow<TranslationProgress>(TranslationProgress.Idle)
    val progress: StateFlow<TranslationProgress> = _progress.asStateFlow()

    suspend fun translate(inputBitmap: Bitmap): TranslationResult {
        val ctx = TranslationContext(inputBitmap = inputBitmap, config = config)
        return try {
            // Step 0: Prepare
            _progress.value = TranslationProgress.Loading("Preparing models...")
            detector.prepare()
            recognizer.prepare()

            // Step 1: Detection
            _progress.value = TranslationProgress.Processing("Detecting text...", 0.1f)
            val detectionResult = detector.detect(inputBitmap, config.detector)
            ctx.textlines = detectionResult.textlines
            ctx.rawMask = detectionResult.rawMask
            if (ctx.textlines.isEmpty()) return TranslationResult.NoText(inputBitmap)

            // Step 2: OCR
            _progress.value = TranslationProgress.Processing("Recognizing text...", 0.25f)
            ctx.textlines = recognizer.recognize(inputBitmap, ctx.textlines, config.ocr)
            if (ctx.textlines.all { it.text.isBlank() }) return TranslationResult.NoText(inputBitmap)

            // Step 3: Textline merge
            _progress.value = TranslationProgress.Processing("Merging text lines...", 0.35f)
            ctx.textRegions = merger.merge(ctx.textlines, inputBitmap.width, inputBitmap.height)
            if (ctx.textRegions.isEmpty()) return TranslationResult.NoText(inputBitmap)

            // Step 4: Translation
            _progress.value = TranslationProgress.Processing("Translating...", 0.5f)
            val texts = ctx.textRegions.map { it.text }
            val translations = translator.translate(
                texts, "auto", config.translator.targetLanguage, config.translator
            )
            ctx.textRegions = ctx.textRegions.zip(translations) { region, translation ->
                region.copy(translation = translation)
            }

            // Step 5: Mask refinement
            _progress.value = TranslationProgress.Processing("Refining mask...", 0.6f)
            ctx.refinedMask = maskRefiner.refine(
                ctx.textRegions, inputBitmap, ctx.rawMask,
                config.kernelSize, config.maskDilationOffset
            )

            // Step 6: Inpainting
            _progress.value = TranslationProgress.Processing("Inpainting...", 0.7f)
            ctx.imgInpainted = inpainter.inpaint(inputBitmap, ctx.refinedMask!!, config.inpainter)

            // Step 7: Rendering
            _progress.value = TranslationProgress.Processing("Rendering text...", 0.85f)
            ctx.imgRendered = renderer.render(ctx.imgInpainted!!, ctx.textRegions, config.renderer)

            // Step 8: Finalize
            val result = ctx.imgRendered ?: inputBitmap
            _progress.value = TranslationProgress.Done(result)
            TranslationResult.Success(result, ctx.textRegions)

        } catch (e: CancellationException) {
            TranslationResult.Cancelled
        } catch (e: Exception) {
            TranslationResult.Error(e.message ?: "Unknown error", e)
        } finally {
            detector.release()
            recognizer.release()
        }
    }

    private fun applyPreDictionary(regions: List<TextBlock>): List<TextBlock> = regions

    private fun applyPostDictionary(regions: List<TextBlock>): List<TextBlock> = regions

    private fun filterInvalidTranslations(regions: List<TextBlock>): List<TextBlock> = regions
}
