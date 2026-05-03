package com.sakuravillager.manga_translator.translation.pipeline

import android.graphics.Bitmap
import android.util.Log
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
import com.sakuravillager.manga_translator.translation.dict.DictionaryLoader
import com.sakuravillager.manga_translator.translation.translator.TranslationValidator
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
            merger.prepare()
            translator.prepare()
            maskRefiner.prepare()
            inpainter.prepare()
            renderer.prepare()

            // Step 1: Detection
            _progress.value = TranslationProgress.Processing("Detecting text...", 0.1f)
            val detectionResult = detector.detect(inputBitmap, config.detector)
            ctx.textlines = detectionResult.textlines.toMutableList()
            ctx.rawMask = detectionResult.rawMask
            if (ctx.textlines.isEmpty()) return TranslationResult.NoText(inputBitmap)

            // Step 2: OCR
            _progress.value = TranslationProgress.Processing("Recognizing text...", 0.25f)
            ctx.textlines = recognizer.recognize(inputBitmap, ctx.textlines, config.ocr).toMutableList()
            if (ctx.textlines.all { it.text.isBlank() }) return TranslationResult.NoText(inputBitmap)

            // Step 3: Textline merge
            _progress.value = TranslationProgress.Processing("Merging text lines...", 0.35f)
            ctx.textRegions = merger.merge(ctx.textlines, inputBitmap.width, inputBitmap.height).toMutableList()
            if (ctx.textRegions.isEmpty()) return TranslationResult.NoText(inputBitmap)

            // Apply pre-dictionary (text replacement before translation)
            ctx.textRegions = applyPreDictionary(ctx.textRegions, config).toMutableList()

            // Step 4: Translation
            _progress.value = TranslationProgress.Processing("Translating...", 0.5f)
            val texts = ctx.textRegions.map { it.text }
            val translations = translator.translate(
                texts, "auto", config.translator.targetLanguage, config.translator
            )
            ctx.textRegions = ctx.textRegions.zip(translations) { region, translation ->
                region.copy(translation = translation)
            }.toMutableList()

            // Apply post-dictionary and validation
            ctx.textRegions = applyPostDictionary(ctx.textRegions, config).toMutableList()
            ctx.textRegions = filterInvalidTranslations(ctx.textRegions, config.translator.targetLanguage).toMutableList()

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
            val safeInpainted = ctx.imgInpainted?.copy(Bitmap.Config.ARGB_8888, false) ?: ctx.imgInpainted
            ctx.imgRendered = renderer.render(safeInpainted!!, ctx.textRegions, config.renderer)

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
            merger.release()
            translator.release()
            maskRefiner.release()
            inpainter.release()
            renderer.release()
        }
    }

    private fun applyPreDictionary(regions: List<TextBlock>, config: TranslationConfig): List<TextBlock> {
        val path = config.preDictPath ?: return regions
        return try {
            val dict = DictionaryLoader.load(path)
            if (dict.isEmpty()) return regions
            regions.map { region -> region.copy(text = DictionaryLoader.apply(region.text, dict)) }
        } catch (e: Exception) {
            Log.w("TranslationPipeline", "Failed to apply pre-dictionary: ${e.message}")
            regions
        }
    }

    private fun applyPostDictionary(regions: List<TextBlock>, config: TranslationConfig): List<TextBlock> {
        val path = config.postDictPath ?: return regions
        return try {
            val dict = DictionaryLoader.load(path)
            if (dict.isEmpty()) return regions
            regions.map { region -> region.copy(translation = DictionaryLoader.apply(region.translation, dict)) }
        } catch (e: Exception) {
            Log.w("TranslationPipeline", "Failed to apply post-dictionary: ${e.message}")
            regions
        }
    }

    private fun filterInvalidTranslations(regions: List<TextBlock>, targetLanguage: String): List<TextBlock> {
        return regions.filter { region ->
            TranslationValidator.validate(region.text, region.translation, targetLanguage)
        }
    }
}
