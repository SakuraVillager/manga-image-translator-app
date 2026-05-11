package com.sakuravillager.manga_translator.translation.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Colorizer
import com.sakuravillager.manga_translator.translation.api.Inpainter
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.api.Upscaler
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.data.TranslationContext
import com.sakuravillager.manga_translator.translation.data.config.ColorizerType
import com.sakuravillager.manga_translator.translation.data.config.TranslationConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import com.sakuravillager.manga_translator.translation.util.resizeBitmap
import com.sakuravillager.manga_translator.translation.sort.RegionSorter
import com.sakuravillager.manga_translator.translation.util.downsampleToMaxSize
import com.sakuravillager.manga_translator.translation.dict.DictionaryLoader
import com.sakuravillager.manga_translator.translation.pipeline.RepetitionHallucinationChecker
import com.sakuravillager.manga_translator.translation.translator.TranslationValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TranslationPipeline(
    private val detector: TextDetector,
    private val recognizer: TextRecognizer,
    private val merger: TextlineMerger,
    private val translator: Translator,
    private val colorizer: Colorizer,
    private val upscaler: Upscaler,
    private val maskRefiner: MaskRefiner,
    private val inpainter: Inpainter,
    private val renderer: TextRenderer,
    private val config: TranslationConfig,
) {
    companion object {
        private const val TAG = "TranslationPipeline"
    }

    private val pageHistoryMutex = Mutex()
    private val pageHistory = ArrayDeque<Map<String, String>>()

    /** Pre-compiled regex from config.filterText (matches Python's config.re_filter_text). */
    private val filterTextRegex: Regex? by lazy {
        config.filterText?.let { Regex(it) }
    }

    private val _progress = MutableStateFlow<TranslationProgress>(TranslationProgress.Idle)
    val progress: StateFlow<TranslationProgress> = _progress.asStateFlow()

    suspend fun translate(inputBitmap: Bitmap): TranslationResult {
        val ctx = TranslationContext(inputBitmap = inputBitmap, config = config)
        return try {
            // Step 0: Prepare
            _progress.value = TranslationProgress.Loading("Preparing models...")
            colorizer.prepare()
            upscaler.prepare()
            detector.prepare()
            recognizer.prepare()
            merger.prepare()
            translator.prepare()
            maskRefiner.prepare()
            inpainter.prepare()
            renderer.prepare()

            // Step 1: Optional preprocessing
            var processingBitmap = inputBitmap
            if (config.colorizer.colorizer != ColorizerType.NONE) {
                _progress.value = TranslationProgress.Processing("Colorizing image...", 0.05f)
                try {
                    processingBitmap = colorizer.colorize(processingBitmap, config.colorizer)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during colorizing: ${e.message}", e)
                    if (!config.ignoreErrors) throw e
                    // Fallback: keep processingBitmap unchanged
                }
            }
            ctx.imgColorized = processingBitmap

            if (config.upscale.upscaleRatio != null && config.upscale.upscaleRatio > 1) {
                _progress.value = TranslationProgress.Processing("Upscaling image...", 0.08f)
                try {
                    processingBitmap = upscaler.upscale(processingBitmap, config.upscale)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during upscaling: ${e.message}", e)
                    if (!config.ignoreErrors) throw e
                    // Fallback: keep processingBitmap unchanged
                }
            }
            ctx.imgUpscaled = processingBitmap

            // Step 2: Downsample large images to prevent OOM
            if (maxOf(processingBitmap.width, processingBitmap.height) > config.detector.detectionSize) {
                processingBitmap = downsampleToMaxSize(processingBitmap, config.detector.detectionSize)
            }
            ctx.originalBitmap = if (processingBitmap !== inputBitmap) inputBitmap else null
            ctx.imgRgb = processingBitmap

            // Debug: input.png - after preprocessing (colorize + upscale + downsample)
            if (config.verbose) {
                ctx.debugImages["input.png"] = processingBitmap.copy(Bitmap.Config.ARGB_8888, false)
            }

            // Step 3: Detection
            _progress.value = TranslationProgress.Processing("Detecting text...", 0.1f)
            try {
                val detectionResult = detector.detect(processingBitmap, config.detector)
                ctx.textlines = detectionResult.textlines.toMutableList()
                ctx.rawMask = detectionResult.rawMask
            } catch (e: Exception) {
                Log.e(TAG, "Error during detection: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.textlines = mutableListOf()
                ctx.rawMask = null
            }
            // Debug: mask_raw.png and bboxes_unfiltered.png after detection
            if (config.verbose) {
                ctx.rawMask?.let { ctx.debugImages["mask_raw.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
                ctx.imgRgb?.let { ctx.debugImages["bboxes_unfiltered.png"] = drawQuadrilaterals(it, ctx.textlines) }
            }
            if (ctx.textlines.isEmpty()) {
                Log.i("TranslationPipeline", "NoText after detection: detector=${detector.name}, image=${processingBitmap.width}x${processingBitmap.height}")
                return TranslationResult.NoText(processingBitmap)
            }

            // Step 4: OCR
            _progress.value = TranslationProgress.Processing("Recognizing text...", 0.25f)
            try {
                ctx.textlines = recognizer.recognize(processingBitmap, ctx.textlines, config.ocr).toMutableList()
                ctx.textlines = ctx.textlines.sortedWith(compareBy<Quadrilateral> {
                    it.readingOrderIndex ?: Int.MAX_VALUE
                }.thenBy { it.sourceIndex ?: Int.MAX_VALUE }).toMutableList()
            } catch (e: Exception) {
                Log.e(TAG, "Error during ocr: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.textlines = mutableListOf()
            }
            val recognizedCount = ctx.textlines.count { it.text.isNotBlank() }
            Log.i(
                "TranslationPipeline",
                "OCR result: total=${ctx.textlines.size}, nonBlank=$recognizedCount, engine=${config.ocr.ocrEngine}",
            )
            if (ctx.textlines.all { it.text.isBlank() }) {
                Log.i("TranslationPipeline", "NoText after OCR: engine=${config.ocr.ocrEngine}")
                return TranslationResult.NoText(processingBitmap)
            }

            // Step 5: Textline merge
            _progress.value = TranslationProgress.Processing("Merging text lines...", 0.35f)
            try {
                ctx.textRegions = merger.merge(ctx.textlines, processingBitmap.width, processingBitmap.height).toMutableList()
            } catch (e: Exception) {
                Log.e(TAG, "Error during textline merge: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.textRegions = mutableListOf()
            }
            if (ctx.textRegions.isEmpty()) {
                Log.i("TranslationPipeline", "NoText after merge: lines=${ctx.textlines.size}")
                return TranslationResult.NoText(processingBitmap)
            }

            ctx.textRegions = RegionSorter.sortRegions(
                ctx.textRegions,
                rightToLeft = config.renderer.rtl,
                image = processingBitmap,
                forceSimpleSort = config.forceSimpleSort,
            ).toMutableList()

            // Debug: bboxes.png after textline merge and sorting (placeholder for visualize_textblocks)
            if (config.verbose) {
                ctx.imgRgb?.let { ctx.debugImages["bboxes.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
            }

            // Apply pre-dictionary (text replacement before translation)
            ctx.textRegions = applyPreDictionary(ctx.textRegions, config).toMutableList()

            // Step 6: Translation
            _progress.value = TranslationProgress.Processing("Translating...", 0.5f)
            // Detect source language from merged text
            val allText = ctx.textRegions.joinToString("") { it.text }
            ctx.fromLanguage = detectSourceLanguage(allText).first
            try {
                ctx.textRegions = translateWithValidationRetry(ctx.textRegions, config, ctx.fromLanguage)
            } catch (e: Exception) {
                Log.e(TAG, "Error during translation: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.textRegions = mutableListOf()
            }

            // Step 7: Mask refinement
            _progress.value = TranslationProgress.Processing("Refining mask...", 0.6f)
            try {
                ctx.refinedMask = maskRefiner.refine(
                    ctx.textRegions, processingBitmap, ctx.rawMask,
                    config.kernelSize, config.maskDilationOffset,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during mask refinement: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.refinedMask = ctx.rawMask ?: Bitmap.createBitmap(processingBitmap.width, processingBitmap.height, Bitmap.Config.ARGB_8888).apply { eraseColor(0) }
            }

            // Debug: mask_final.png and inpaint_input.png after mask refinement
            if (config.verbose) {
                ctx.refinedMask?.let { ctx.debugImages["mask_final.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
                if (ctx.imgRgb != null && ctx.refinedMask != null) {
                    ctx.debugImages["inpaint_input.png"] = createMaskOverlay(ctx.imgRgb!!, ctx.refinedMask!!)
                }
            }

            // Step 8: Inpainting
            _progress.value = TranslationProgress.Processing("Inpainting...", 0.7f)
            try {
                ctx.imgInpainted = inpainter.inpaint(processingBitmap, ctx.refinedMask!!, config.inpainter)
                ctx.gimpMask = ctx.imgInpainted
            } catch (e: Exception) {
                Log.e(TAG, "Error during inpainting: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.imgInpainted = processingBitmap
                ctx.gimpMask = processingBitmap
            }

            // Debug: inpainted.png after inpainting
            if (config.verbose) {
                ctx.imgInpainted?.let { ctx.debugImages["inpainted.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
            }

            // Step 9: Rendering
            _progress.value = TranslationProgress.Processing("Rendering text...", 0.85f)
            try {
                val safeInpainted = ctx.imgInpainted?.copy(Bitmap.Config.ARGB_8888, false) ?: ctx.imgInpainted
                ctx.imgRendered = renderer.render(safeInpainted!!, ctx.textRegions, config.renderer)
            } catch (e: Exception) {
                Log.e(TAG, "Error during rendering: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.imgRendered = ctx.imgInpainted
            }

            // Debug: final.png after rendering
            if (config.verbose) {
                ctx.imgRendered?.let { ctx.debugImages["final.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
            }

            if (config.upscale.revertUpscaling && processingBitmap !== inputBitmap) {
                ctx.imgRendered = resizeBitmap(ctx.imgRendered!!, inputBitmap.width, inputBitmap.height)
            }

            // Step 10: Finalize
            val result = ctx.imgRendered ?: processingBitmap
            ctx.resultBitmap = result
            _progress.value = TranslationProgress.Done(result)

            rememberPageTranslation(ctx.textRegions)
            TranslationResult.Success(result, ctx.textRegions)

        } catch (e: CancellationException) {
            TranslationResult.Cancelled
        } catch (e: Exception) {
            TranslationResult.Error(e.message ?: "Unknown error", e)
        } finally {
            colorizer.release()
            upscaler.release()
            detector.release()
            recognizer.release()
            merger.release()
            translator.release()
            maskRefiner.release()
            inpainter.release()
            renderer.release()
        }
    }

    /**
     * Translates multiple images in batch.
     *
     * When batchSize <= 1: processes images sequentially with context carry-over.
     * When batchSize > 1: pre-processes all images (detect→OCR→merge), batch-translates all texts,
     * then completes the pipeline (mask→inpaint→render) per page.
     *
     * Matches Python translate_batch() (manga_translator.py L1456-1657, simplified).
     */
    suspend fun translateBatch(
        images: List<Bitmap>,
        batchSize: Int = 1,
    ): List<TranslationResult> {
        if (images.isEmpty()) return emptyList()

        try {
            // Prepare once for all images
            _progress.value = TranslationProgress.Loading("Preparing models for batch...")
            colorizer.prepare()
            upscaler.prepare()
            detector.prepare()
            recognizer.prepare()
            merger.prepare()
            translator.prepare()
            maskRefiner.prepare()
            inpainter.prepare()
            renderer.prepare()

            val results = mutableListOf<TranslationResult>()

            if (batchSize <= 1) {
                // Sequential mode: process each image independently
                for ((i, bitmap) in images.withIndex()) {
                    Log.i(TAG, "Batch processing image ${i + 1}/${images.size}")
                    val ctx = translateUntilTranslation(bitmap, config)
                    if (ctx.textRegions.isEmpty()) {
                        results.add(TranslationResult.NoText(bitmap))
                        continue
                    }
                    ctx.textRegions = translateWithValidationRetry(ctx.textRegions, config, ctx.fromLanguage)
                    completeTranslationPipeline(ctx, processingBitmap = ctx.imgRgb ?: bitmap, inputBitmap = bitmap, config)
                    rememberPageTranslation(ctx.textRegions)
                    results.add(TranslationResult.Success(ctx.resultBitmap ?: bitmap, ctx.textRegions))
                }
            } else {
                // Batch mode: pre-process all, then translate together
                Log.i(TAG, "Batch mode activated with batchSize=$batchSize for ${images.size} images")

                val preContexts = images.map { bitmap ->
                    val ctx = translateUntilTranslation(bitmap, config)
                    ctx to bitmap
                }

                // Translate in batches
                val translateContexts = preContexts.map { it.first }
                val translatedContexts = batchTranslateContexts(translateContexts, batchSize, config)

                // Complete pipeline per page
                for ((i, ctx) in translatedContexts.withIndex()) {
                    val bitmap = preContexts[i].second
                    if (ctx.textRegions.isEmpty()) {
                        results.add(TranslationResult.NoText(bitmap))
                        continue
                    }
                    val processingBitmap = ctx.imgRgb ?: bitmap
                    completeTranslationPipeline(ctx, processingBitmap, bitmap, config)
                    rememberPageTranslation(ctx.textRegions)
                    results.add(TranslationResult.Success(ctx.resultBitmap ?: bitmap, ctx.textRegions))
                }
            }

            return results
        } finally {
            colorizer.release()
            upscaler.release()
            detector.release()
            recognizer.release()
            merger.release()
            translator.release()
            maskRefiner.release()
            inpainter.release()
            renderer.release()
        }
    }

    /**
     * Collects texts from multiple contexts, translates them in batches,
     * and distributes translations back.
     *
     * Matches Python _batch_translate_contexts() (manga_translator.py L1804-2010, simplified).
     */
    private suspend fun batchTranslateContexts(
        contexts: List<TranslationContext>,
        batchSize: Int,
        config: TranslationConfig,
    ): List<TranslationContext> {
        val results = mutableListOf<TranslationContext>()

        for (i in contexts.indices step batchSize) {
            val batch = contexts.subList(i, minOf(i + batchSize, contexts.size))

            // Collect all texts from this batch
            val allTexts = mutableListOf<String>()
            val textMapping = mutableListOf<Pair<Int, Int>>()  // (contextIndex, regionIndex)

            for ((ctxIdx, ctx) in batch.withIndex()) {
                for ((regionIdx, region) in ctx.textRegions.withIndex()) {
                    allTexts.add(region.text)
                    textMapping.add(ctxIdx to regionIdx)
                }
            }

            if (allTexts.isNotEmpty()) {
                val translations = batchTranslateTexts(allTexts, config)

                // Distribute translations back
                var textIdx = 0
                for ((ctxIdx, ctx) in batch.withIndex()) {
                    for ((regionIdx, region) in ctx.textRegions.withIndex()) {
                        if (textIdx < translations.size) {
                            ctx.textRegions[regionIdx] = region.copy(translation = translations[textIdx])
                            textIdx++
                        }
                    }
                }
            }

            results.addAll(batch)
        }

        return results
    }

    /**
     * Translates multiple contexts concurrently using coroutine async.
     * Each context's text regions are translated independently in parallel.
     *
     * Matches Python _concurrent_translate_contexts() (manga_translator.py L2012-2210, simplified).
     */
    private suspend fun concurrentTranslateContexts(
        contexts: List<TranslationContext>,
        config: TranslationConfig,
    ): List<TranslationContext> = coroutineScope {
        contexts.map { ctx ->
            async {
                val texts = ctx.textRegions.map { it.text }
                if (texts.isEmpty()) return@async ctx

                val translations = batchTranslateTexts(texts, config)

                for ((i, region) in ctx.textRegions.withIndex()) {
                    if (i < translations.size) {
                        ctx.textRegions[i] = region.copy(translation = translations[i])
                    }
                }

                ctx
            }
        }.awaitAll()
    }

    private suspend fun translateUntilTranslation(
        inputBitmap: Bitmap,
        config: TranslationConfig,
    ): TranslationContext {
        val ctx = TranslationContext(inputBitmap = inputBitmap, config = config)
        // Step 1: Optional preprocessing
        var processingBitmap = inputBitmap
        if (config.colorizer.colorizer != ColorizerType.NONE) {
            processingBitmap = colorizer.colorize(processingBitmap, config.colorizer)
        }
        ctx.imgColorized = processingBitmap

        if (config.upscale.upscaleRatio != null && config.upscale.upscaleRatio > 1) {
            processingBitmap = upscaler.upscale(processingBitmap, config.upscale)
        }
        ctx.imgUpscaled = processingBitmap

        // Step 2: Downsample large images to prevent OOM
        if (maxOf(processingBitmap.width, processingBitmap.height) > config.detector.detectionSize) {
            processingBitmap = downsampleToMaxSize(processingBitmap, config.detector.detectionSize)
        }
        ctx.originalBitmap = if (processingBitmap !== inputBitmap) inputBitmap else null
        ctx.imgRgb = processingBitmap

        // Debug: input.png after preprocessing
        if (config.verbose) {
            ctx.debugImages["input.png"] = processingBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Step 3: Detection
        val detectionResult = detector.detect(processingBitmap, config.detector)
        ctx.textlines = detectionResult.textlines.toMutableList()
        ctx.rawMask = detectionResult.rawMask
        // Debug: mask_raw.png and bboxes_unfiltered.png after detection
        if (config.verbose) {
            ctx.rawMask?.let { ctx.debugImages["mask_raw.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
            ctx.imgRgb?.let { ctx.debugImages["bboxes_unfiltered.png"] = drawQuadrilaterals(it, ctx.textlines) }
        }
        if (ctx.textlines.isEmpty()) {
            Log.i(TAG, "NoText after detection: detector=${detector.name}, image=${processingBitmap.width}x${processingBitmap.height}")
            return ctx  // caller checks ctx.textlines.isEmpty()
        }

        // Step 4: OCR
        ctx.textlines = recognizer.recognize(processingBitmap, ctx.textlines, config.ocr).toMutableList()
        ctx.textlines = ctx.textlines.sortedWith(compareBy<Quadrilateral> {
            it.readingOrderIndex ?: Int.MAX_VALUE
        }.thenBy { it.sourceIndex ?: Int.MAX_VALUE }).toMutableList()
        val recognizedCount = ctx.textlines.count { it.text.isNotBlank() }
        Log.i(TAG, "OCR result: total=${ctx.textlines.size}, nonBlank=$recognizedCount, engine=${config.ocr.ocrEngine}")
        if (ctx.textlines.all { it.text.isBlank() }) {
            Log.i(TAG, "NoText after OCR: engine=${config.ocr.ocrEngine}")
            return ctx  // caller checks ctx.textRegions.isEmpty()
        }

        // Step 5: Textline merge
        ctx.textRegions = merger.merge(ctx.textlines, processingBitmap.width, processingBitmap.height).toMutableList()
        if (ctx.textRegions.isEmpty()) {
            Log.i(TAG, "NoText after merge: lines=${ctx.textlines.size}")
            return ctx
        }

        ctx.textRegions = RegionSorter.sortRegions(
            ctx.textRegions,
            rightToLeft = config.renderer.rtl,
            image = processingBitmap,
            forceSimpleSort = config.forceSimpleSort,
        ).toMutableList()

        // Debug: bboxes.png after textline merge and sorting (placeholder for visualize_textblocks)
        if (config.verbose) {
            ctx.imgRgb?.let { ctx.debugImages["bboxes.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
        }

        // Apply pre-dictionary
        ctx.textRegions = applyPreDictionary(ctx.textRegions, config).toMutableList()

        // Detect source language from merged text
        val allText = ctx.textRegions.joinToString("") { it.text }
        ctx.fromLanguage = detectSourceLanguage(allText).first

        return ctx  // contains textRegions ready for translation
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

    /**
     * Translates a list of text strings in batch.
     * Creates temporary TextBlock list, delegates to translateWithValidationRetry,
     * then extracts translations.
     *
     * Matches Python _batch_translate_texts() (manga_translator.py L2212-2341, simplified).
     */
    private suspend fun batchTranslateTexts(
        texts: List<String>,
        config: TranslationConfig,
    ): List<String> {
        if (config.translator.translator == TranslatorType.NONE) {
            return texts.map { "" }
        }

        // Build context if enabled
        val context = buildPreviousPageContext(config.translator.contextPages)
        val translatorConfig = if (context.isNotEmpty()) {
            config.translator.copy(prevContext = context)
        } else {
            config.translator
        }

        // Create temporary TextBlocks to reuse translateWithValidationRetry
        val tempRegions = texts.map { text ->
            TextBlock(
                lines = emptyList(),
                texts = listOf(text),
                text = text,
                translation = "",
            )
        }

        val configWithTranslator = config.copy(translator = translatorConfig)
        val resultRegions = translateWithValidationRetry(tempRegions.toMutableList(), configWithTranslator)

        return resultRegions.map { it.translation }
    }

    /**
     * Applies post-dictionary, hallucination detection, and retry logic
     * after translation.
     *
     * Matches Python _apply_post_translation_processing() (manga_translator.py L2343-2479).
     */
    private suspend fun applyPostTranslationProcessing(
        regions: List<TextBlock>,
        config: TranslationConfig,
    ): List<TextBlock> {
        // 1. Apply post-dictionary
        val postDictRegions = applyPostDictionary(regions, config)

        // 2. Hallucination detection per region
        val failedRegions = mutableListOf<TextBlock>()
        if (config.enablePostTranslationCheck) {
            for (region in postDictRegions) {
                if (region.translation.isNotBlank() &&
                    RepetitionHallucinationChecker.check(region.translation, threshold = config.postCheckRepetitionThreshold)
                ) {
                    Log.w(TAG, "Hallucination detected for '${region.text}': '${region.translation}'")
                    failedRegions.add(region)
                }
            }
        }

        // 3. Retry failed regions (placeholder — real retrySingleRegionTranslation in Task 10)
        // For now, just keep the original translation for failed regions
        if (failedRegions.isNotEmpty()) {
            Log.w(TAG, "Found ${failedRegions.size} regions with hallucinations (will be retried in Task 10)")
        }

        return postDictRegions
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
        return regions.map { region ->
            val isValid = TranslationValidator.validate(
                original = region.text,
                translation = region.translation,
                targetLang = targetLanguage,
                repetitionThreshold = 20,
                targetLangThreshold = 0.5f,
            )
            if (isValid) {
                region
            } else {
                Log.w("TranslationPipeline",
                    "Translation validation failed for '${region.text}', falling back to original")
                region.copy(translation = region.text)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun isPageTranslationValid(
        regions: List<TextBlock>,
        targetLanguage: String,
        threshold: Float,
    ): Boolean {
        return checkTargetLanguageRatio(regions, targetLanguage)
    }

    private fun checkTargetLanguageRatio(
        textRegions: List<TextBlock>,
        targetLang: String,
    ): Boolean {
        if (textRegions.size <= 10) return true

        val allTranslations = textRegions.mapNotNull { region ->
            region.translation.trim().takeIf { it.isNotEmpty() }
        }
        if (allTranslations.isEmpty()) return true

        val mergedText = allTranslations.joinToString("")
        val (detectedLang, _) = detectSourceLanguage(mergedText)

        Log.d(TAG, "Target language check: detected=$detectedLang, expected=$targetLang")
        return detectedLang.equals(targetLang, ignoreCase = true)
    }

    private suspend fun translateWithValidationRetry(
        regions: List<TextBlock>,
        config: TranslationConfig,
        fromLanguage: String? = null,
    ): MutableList<TextBlock> {
        if (regions.isEmpty()) return mutableListOf()
        if (config.translator.translator == TranslatorType.NONE) {
            return regions.map { region -> region.copy(translation = "") }.toMutableList()
        }

        val translatableIndices = regions.mapIndexedNotNull { index, region ->
            if (region.text.isBlank()) null else index
        }
        if (translatableIndices.isEmpty()) {
            return regions.map { it.copy(translation = "") }.toMutableList()
        }

        val translatableTexts = translatableIndices.map { regions[it].text }
        var candidateTranslations = translatableTexts
        val translatorConfig = config.translator.copy(prevContext = buildPreviousPageContext(config.translator.contextPages))

        repeat((if (config.enablePostTranslationCheck) config.postCheckMaxRetryAttempts else 0) + 1) { attempt ->
            candidateTranslations = translator.translate(
                translatableTexts,
                fromLanguage ?: "auto",
                config.translator.targetLanguage,
                translatorConfig,
            )

            val remappedTranslations = MutableList(regions.size) { "" }
            translatableIndices.forEachIndexed { orderedIndex, originalIndex ->
                remappedTranslations[originalIndex] = candidateTranslations.getOrElse(orderedIndex) {
                    regions[originalIndex].text
                }
            }

            val translatedRegions = regions.mapIndexed { index, region ->
                region.copy(translation = remappedTranslations[index])
            }
            val postDictRegions = applyPostDictionary(translatedRegions, config)
            val cleanedRegions = postDictRegions.map { region ->
                region.copy(translation = TranslationValidator.cleanTranslation(region.translation, region.text))
            }
            val normalizedRegions = normalizePunctuation(cleanedRegions)
            // Hallucination check using RepetitionHallucinationChecker (word + phrase level)
            val hallucinationCheckedRegions = normalizedRegions.map { region ->
                if (region.translation.isNotBlank() &&
                    RepetitionHallucinationChecker.check(region.translation, threshold = config.postCheckRepetitionThreshold)
                ) {
                    Log.w(TAG, "Hallucination detected for '${region.text}': '${region.translation}'")
                    region.copy(translation = region.text) // fallback to original text
                } else {
                    region
                }
            }
            val validatedRegions = if (config.enablePostTranslationCheck) {
                hallucinationCheckedRegions.map { region ->
                    val isValid = TranslationValidator.validate(
                        original = region.text,
                        translation = region.translation,
                        targetLang = config.translator.targetLanguage,
                        repetitionThreshold = config.postCheckRepetitionThreshold,
                        targetLangThreshold = config.postCheckTargetLangThreshold,
                    )
                    if (isValid) {
                        region
                    } else {
                        region.copy(translation = region.text)
                    }
                }
            } else {
                hallucinationCheckedRegions
            }

            val filteredRegions = applyFinalFiltering(validatedRegions, config)

            val pageLevelValid = !config.enablePostTranslationCheck ||
                filteredRegions.size <= 5 ||
                isPageTranslationValid(
                    filteredRegions,
                    config.translator.targetLanguage,
                    config.postCheckTargetLangThreshold,
                )

            if (!pageLevelValid) {
                Log.w(
                    "TranslationPipeline",
                    "Page-level target language check failed, retrying attempt ${attempt + 1}/${config.postCheckMaxRetryAttempts + 1}",
                )
            }

            val allValid = filteredRegions.all { region ->
                region.translation.isNotBlank() && region.translation != region.text || region.text.isBlank()
            }

            if ((allValid && pageLevelValid) || attempt == config.postCheckMaxRetryAttempts || !config.enablePostTranslationCheck) {
                return filteredRegions.toMutableList()
            }
        }

        return regions.map { region -> region.copy(translation = region.text) }.toMutableList()
    }

    /**
     * Retries translation for a single region that failed post-validation.
     * Attempts up to postCheckMaxRetryAttempts times with validation after each attempt.
     *
     * Matches Python _retry_translation_with_validation() (manga_translator.py L2729-2796).
     */
    private suspend fun retrySingleRegionTranslation(
        region: TextBlock,
        config: TranslationConfig,
    ): String? {
        val maxAttempts = config.postCheckMaxRetryAttempts
        val translatorConfig = config.translator

        for (attempt in 1..maxAttempts) {
            try {
                val result = translator.translate(
                    listOf(region.text),
                    "auto",
                    translatorConfig.targetLanguage,
                    translatorConfig,
                )
                if (result.isNotEmpty()) {
                    val newTranslation = result[0]
                    // Check if it passes hallucination detection
                    if (!RepetitionHallucinationChecker.check(newTranslation, threshold = config.postCheckRepetitionThreshold)) {
                        Log.i(TAG, "Retry successful for '${region.text}': '$newTranslation'")
                        return newTranslation
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Retry attempt $attempt failed for '${region.text}': ${e.message}")
            }
        }

        Log.w(TAG, "All retry attempts failed for '${region.text}'")
        return null
    }

    /**
     * Completes the post-translation pipeline steps (mask, inpaint, render, finalize).
     * Called after translation is done, either for single images or batch processing.
     *
     * Matches Python _complete_translation_pipeline() (manga_translator.py L2481-2574).
     */
    private suspend fun completeTranslationPipeline(
        ctx: TranslationContext,
        processingBitmap: Bitmap,
        inputBitmap: Bitmap,
        config: TranslationConfig,
    ): TranslationContext {
        // Step 7: Mask refinement
        ctx.refinedMask = maskRefiner.refine(
            ctx.textRegions, processingBitmap, ctx.rawMask,
            config.kernelSize, config.maskDilationOffset,
        )

        // Debug: mask_final.png and inpaint_input.png after mask refinement
        if (config.verbose) {
            ctx.refinedMask?.let { ctx.debugImages["mask_final.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
            ctx.imgRgb?.let { img ->
                ctx.refinedMask?.let { mask ->
                    ctx.debugImages["inpaint_input.png"] = createMaskOverlay(img, mask)
                }
            }
        }

        // Step 8: Inpainting
        ctx.imgInpainted = inpainter.inpaint(processingBitmap, ctx.refinedMask!!, config.inpainter)
        ctx.gimpMask = ctx.imgInpainted

        // Debug: inpainted.png after inpainting
        if (config.verbose) {
            ctx.imgInpainted?.let { ctx.debugImages["inpainted.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
        }

        // Step 9: Rendering
        val safeInpainted = ctx.imgInpainted?.copy(Bitmap.Config.ARGB_8888, false) ?: ctx.imgInpainted
        ctx.imgRendered = renderer.render(safeInpainted!!, ctx.textRegions, config.renderer)

        // Debug: final.png after rendering
        if (config.verbose) {
            ctx.imgRendered?.let { ctx.debugImages["final.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
        }

        // Revert upscale if needed
        if (config.upscale.revertUpscaling && processingBitmap !== inputBitmap) {
            ctx.imgRendered = resizeBitmap(ctx.imgRendered!!, inputBitmap.width, inputBitmap.height)
        }

        // Step 10: Finalize
        val result = ctx.imgRendered ?: processingBitmap
        ctx.resultBitmap = result

        return ctx
    }

    private suspend fun buildPreviousPageContext(contextPages: Int): String {
        if (contextPages <= 0) return ""

        val recentPages = pageHistoryMutex.withLock {
            if (pageHistory.isEmpty()) return@withLock emptyList()
            pageHistory.takeLast(contextPages)
        }
        if (recentPages.isEmpty()) return ""

        val lines = recentPages.flatMap { page ->
            page.entries.mapNotNull { (_, translation) ->
                translation.trim().takeIf { it.isNotEmpty() }
            }
        }

        if (lines.isEmpty()) return ""

        val numbered = lines.mapIndexed { index, text -> "<|${index + 1}|>$text" }
        return "Here are the previous translation results for reference:\n" + numbered.joinToString("\n")
    }

    private suspend fun rememberPageTranslation(regions: List<TextBlock>) {
        if (regions.isEmpty()) return

        val page = regions.associate { region ->
            val key = if (region.text.isNotBlank()) region.text else region.texts.firstOrNull().orEmpty()
            key to region.translation
        }.filterKeys { it.isNotBlank() }

        if (page.isEmpty()) return

        pageHistoryMutex.withLock {
            pageHistory.addLast(page)
            while (pageHistory.size > 12) {
                pageHistory.removeFirst()
            }
        }
    }

    private fun normalizePunctuation(regions: List<TextBlock>): List<TextBlock> {
        val checkItems = listOf(
            listOf("(", "（", "「", "【"),
            listOf("（", "(", "「", "【"),
            listOf(")", "）", "」", "】"),
            listOf("）", ")", "」", "】"),
            listOf("[", "［", "【", "「"),
            listOf("［", "[", "【", "「"),
            listOf("]", "］", "】", "」"),
            listOf("］", "]", "】", "」"),
            listOf("「", "“", "‘", "『", "【"),
            listOf("」", "”", "’", "』", "】"),
            listOf("『", "“", "‘", "「", "【"),
            listOf("』", "”", "’", "」", "】"),
            listOf("【", "(", "（", "「", "『", "["),
            listOf("】", ")", "）", "」", "』", "]"),
        )

        val replaceItems = listOf(
            listOf("「", "“"),
            listOf("「", "‘"),
            listOf("」", "”"),
            listOf("」", "’"),
            listOf("【", "["),
            listOf("】", "]"),
        )

        return regions.map { region ->
            if (region.text.isBlank() || region.translation.isBlank()) return@map region

            var translation = region.translation
            val original = region.text

            val quoteType = when {
                original.contains('『') && original.contains('』') -> "『』"
                original.contains('「') && original.contains('」') -> "「」"
                original.contains('【') && original.contains('】') -> "【】"
                else -> null
            }

            if (quoteType != null && !translation.isAscii()) {
                val srcQuoteCount = original.count { it == quoteType[0] }
                val dstDQuoteCount = translation.count { it == '"' }
                val dstFwQuoteCount = translation.count { it == '＂' }
                if (srcQuoteCount > 0 && (srcQuoteCount == dstDQuoteCount || srcQuoteCount == dstFwQuoteCount)) {
                    translation = when (quoteType) {
                        "「」" -> Regex("\"([^\"]*)\"").replace(translation, "「$1」")
                        "『』" -> Regex("\"([^\"]*)\"").replace(translation, "『$1』")
                        "【】" -> Regex("\"([^\"]*)\"").replace(translation, "【$1】")
                        else -> translation
                    }
                }
            }

            for (item in checkItems) {
                val standard = item.first()
                val variants = item.drop(1)
                val numSrcStd = original.count { it.toString() == standard }
                val numSrcVar = variants.sumOf { variant -> original.count { it.toString() == variant } }
                val numDstStd = translation.count { it.toString() == standard }
                val numDstVar = variants.sumOf { variant -> translation.count { it.toString() == variant } }

                if (numSrcStd > 0 && numSrcStd != numSrcVar && numSrcStd == numDstStd + numDstVar) {
                    for (variant in variants) {
                        translation = translation.replace(variant, standard)
                    }
                }
            }

            for (item in replaceItems) {
                translation = translation.replace(item[1], item[0])
            }

            region.copy(translation = translation)
        }
    }

    private fun applyFinalFiltering(regions: List<TextBlock>, config: TranslationConfig): List<TextBlock> {
        val skipLanguages = config.translator.skipLanguage
            ?.split(',')
            ?.map { it.trim().uppercase() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

        return regions.filter { region ->
            val translation = region.translation
            if (translation.isBlank()) {
                return@filter false
            }

            val sourceLanguage = detectSourceLanguage(region.text).first
            if (skipLanguages.isNotEmpty() && sourceLanguage in skipLanguages) {
                Log.i(TAG, "Filtered out: $translation")
                Log.i(TAG, "Reason: sourceLanguage=$sourceLanguage in skipLanguages=$skipLanguages")
                return@filter false
            }

            if (sourceLanguage != "UNKNOWN" && sourceLanguage == config.translator.targetLanguage.uppercase()) {
                Log.i(TAG, "Filtered out: $translation")
                Log.i(TAG, "Reason: sourceLanguage=$sourceLanguage matches targetLanguage")
                return@filter false
            }

            if (config.translator.translator != TranslatorType.NONE) {
                if (translation.all { it.isDigit() }) {
                    Log.i(TAG, "Filtered out: $translation")
                    Log.i(TAG, "Reason: translation is all digits")
                    return@filter false
                }
                if (filterTextRegex != null && filterTextRegex!!.containsMatchIn(translation)) {
                    Log.i(TAG, "Filtered out: $translation")
                    Log.i(TAG, "Reason: matches filterText=${config.filterText}")
                    return@filter false
                }
                if (config.translator.translator != TranslatorType.ORIGINAL &&
                    region.text.trim().lowercase() == translation.trim().lowercase()
                ) {
                    Log.i(TAG, "Filtered out: $translation")
                    Log.i(TAG, "Reason: translation unchanged from source text")
                    return@filter false
                }
            }
            true
        }.map { region ->
            val sourceLanguage = detectSourceLanguage(region.text).first
            region.copy(
                sourceLanguage = if (sourceLanguage == "UNKNOWN") null else sourceLanguage,
                targetLanguage = config.translator.targetLanguage,
                language = if (sourceLanguage == "UNKNOWN") region.language else sourceLanguage,
            )
        }
    }

    /**
     * Draws quadrilateral textline bounding boxes as red polylines on a copy of the image.
     * Used for bboxes_unfiltered debug image.
     */
    private fun drawQuadrilaterals(image: Bitmap, quads: List<Quadrilateral>): Bitmap {
        val copy = image.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(copy)
        val paint = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        for (quad in quads) {
            if (quad.points.size >= 4) {
                val path = Path()
                path.moveTo(quad.points[0].x, quad.points[0].y)
                for (i in 1 until quad.points.size) {
                    path.lineTo(quad.points[i].x, quad.points[i].y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        return copy
    }

    /**
     * Creates a semi-transparent red overlay on masked regions of the image.
     * Used for inpaint_input debug image.
     */
    private fun createMaskOverlay(image: Bitmap, mask: Bitmap): Bitmap {
        val result = image.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val scaledMask = if (mask.width != image.width || mask.height != image.height) {
            Bitmap.createScaledBitmap(mask, image.width, image.height, true)
        } else {
            mask
        }

        val width = scaledMask.width
        val height = scaledMask.height
        val maskPixels = IntArray(width * height)
        scaledMask.getPixels(maskPixels, 0, width, 0, 0, width, height)

        // Create overlay: semi-transparent red for white (masked) pixels
        val overlayPixels = IntArray(width * height) { i ->
            val pixel = maskPixels[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val gray = (r + g + b) / 3
            if (gray > 10) {
                Color.argb((gray * 0.6f).toInt().coerceIn(0, 180), 255, 0, 0)
            } else {
                0 // fully transparent
            }
        }

        val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        overlay.setPixels(overlayPixels, 0, width, 0, 0, width, height)

        canvas.drawBitmap(overlay, 0f, 0f, null)
        overlay.recycle()

        if (scaledMask !== mask) scaledMask.recycle()

        return result
    }

    private fun String.isAscii(): Boolean = all { it.code < 128 }

}

internal fun detectSourceLanguage(text: String): Pair<String, Float> {
    if (text.length < 4) return "UNKNOWN" to 0f

    var cjkScore = 0f    // Weight 1.0
    var hiraganaScore = 0f  // Weight 2.0
    var katakanaScore = 0f  // Weight 1.5
    var koreanScore = 0f    // Weight 1.5
    var arabicScore = 0f    // Weight 1.5
    var thaiScore = 0f      // Weight 1.5
    var cyrillicScore = 0f  // Weight 1.5
    var latinScore = 0f     // Weight 0.5

    for (ch in text) {
        when {
            ch in '\u3040'..'\u309f' -> hiraganaScore += 2.0f
            ch in '\u30a0'..'\u30ff' -> katakanaScore += 1.5f
            ch in '\uac00'..'\ud7af' || ch in '\u1100'..'\u11ff' -> koreanScore += 1.5f
            ch in '\u0600'..'\u06ff' || ch in '\u0750'..'\u077f' || ch in '\u08a0'..'\u08ff' -> arabicScore += 1.5f
            ch in '\u0e00'..'\u0e7f' -> thaiScore += 1.5f  // Thai
            ch in '\u0400'..'\u04ff' -> cyrillicScore += 1.5f  // Cyrillic (Russian, Ukrainian, etc.)
            ch.isLetter() && ch.code < 128 -> latinScore += 0.5f
            ch in '\u3000'..'\u303f' || ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf' || ch in '\uf900'..'\ufaff' -> cjkScore += 1.0f
        }
    }

    val scores = mapOf(
        "JPN" to hiraganaScore * 2 + katakanaScore * 1.5f,
        "KOR" to koreanScore * 1.5f,
        "ARA" to arabicScore * 1.5f,
        "CHS" to cjkScore,
        "ENG" to latinScore * 0.5f,
        "THA" to thaiScore * 1.5f,
        "RUS" to cyrillicScore * 1.5f,
    )
    val maxEntry = scores.maxByOrNull { it.value } ?: return "UNKNOWN" to 0f
    val totalScore = scores.values.sum()
    val confidence = if (totalScore > 0f) maxEntry.value / totalScore else 0f

    return when {
        thaiScore > 0 -> "THA" to confidence
        cyrillicScore > 0 -> "RUS" to confidence  // Simplified: can't distinguish RU/UK
        hiraganaScore > 0 || (katakanaScore > 0 && hiraganaScore + katakanaScore >= cjkScore) -> "JPN" to confidence
        koreanScore > 0 -> "KOR" to confidence
        arabicScore > 0 -> "ARA" to confidence
        cjkScore > 0 -> "CHS" to confidence
        latinScore > 0 -> "ENG" to confidence
        else -> "UNKNOWN" to confidence
    }
}
