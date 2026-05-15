package com.sakuravillager.manga_translator.translation.pipeline

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.sakuravillager.manga_translator.data.logging.AppLogger
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
import com.sakuravillager.manga_translator.translation.util.dump_image
import com.sakuravillager.manga_translator.translation.util.image_resize
import com.sakuravillager.manga_translator.translation.util.load_image
import com.sakuravillager.manga_translator.translation.util.VisualizeUtils
import com.sakuravillager.manga_translator.translation.sort.RegionSorter
import com.sakuravillager.manga_translator.translation.util.downsample_to_max_size
import com.sakuravillager.manga_translator.translation.dict.DictionaryLoader
import com.sakuravillager.manga_translator.translation.mask.BubbleDetector
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

    // ─── Model usage tracking (matches Python _model_usage_timestamps, L140) ───
    /**
     * Tracks last-usage timestamps for (tool, model) pairs.
     * Used by cleanup logic to decide which models can be released.
     */
    private val modelUsageTimestamps = mutableMapOf<Pair<String, String>, Long>()

    /** Records a timestamp for a model's usage (matches Python L668-672 pattern). */
    private fun trackModelUsage(tool: String, model: String) {
        modelUsageTimestamps[tool to model] = System.currentTimeMillis()
    }

    /**
     * Unloads models that haven't been used within [ttlMs].
     * Called between pipeline invocations or during batch processing.
     * Matches Python _detector_cleanup_job (L714-724).
     */
    private suspend fun cleanupStaleModels(ttlMs: Long) {
        if (ttlMs <= 0) return
        val now = System.currentTimeMillis()
        val toRemove = modelUsageTimestamps.filter { (_, lastUsed) ->
            now - lastUsed > ttlMs
        }
        for ((key, _) in toRemove) {
            val (tool, model) = key
            AppLogger.i(TAG, "Unloading stale $tool model: $model")
            when (tool) {
                "colorization" -> colorizer.release()
                "detection" -> detector.release()
                "inpainting" -> inpainter.release()
                "ocr" -> recognizer.release()
                "upscaling" -> upscaler.release()
                "translation" -> translator.release()
            }
            modelUsageTimestamps.remove(key)
        }
    }

    private val _progress = MutableStateFlow<TranslationProgress>(TranslationProgress.Idle)
    val progress: StateFlow<TranslationProgress> = _progress.asStateFlow()

    suspend fun translate(inputBitmap: Bitmap): TranslationResult {
        val ctx = TranslationContext(input_bitmap = inputBitmap, config = config)
        AppLogger.i(TAG, "[PIPELINE] Starting translate: ${inputBitmap.width}x${inputBitmap.height}, det=${config.detector.detector}, ocr=${config.ocr.ocrEngine}, trans=${config.translator.translator}")
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
            ctx.img_colorized = processingBitmap

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
            ctx.img_upscaled = processingBitmap

            // Step 2: Downsample large images to prevent OOM
            if (maxOf(processingBitmap.width, processingBitmap.height) > config.detector.detectionSize) {
                processingBitmap = downsample_to_max_size(processingBitmap, config.detector.detectionSize)
            }
            ctx.original_bitmap = if (processingBitmap !== inputBitmap) inputBitmap else null
            val (img_rgb, img_alpha) = load_image(processingBitmap)
            ctx.img_rgb = img_rgb
            ctx.img_alpha = img_alpha

            // Debug: input.png - after preprocessing (colorize + upscale + downsample)
            if (config.verbose) {
                    ctx.debug_images["input.png"] = img_rgb.copy(Bitmap.Config.ARGB_8888, false)
            }

            // Step 3: Detection
            _progress.value = TranslationProgress.Processing("Detecting text...", 0.1f)
            try {
                val detectionResult = detector.detect(processingBitmap, config.detector)
                ctx.textlines = detectionResult.textlines.toMutableList()
                ctx.raw_mask = detectionResult.rawMask
                trackModelUsage("detection", config.detector.detector.name)
                AppLogger.i(TAG, "[DETECT] Found ${ctx.textlines.size} textlines (det=${config.detector.detector}, ${processingBitmap.width}x${processingBitmap.height})")
            } catch (e: Exception) {
                AppLogger.e(TAG, "[DETECT] Error: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.textlines = mutableListOf()
                ctx.raw_mask = null
            }
            // Debug: mask_raw.png and bboxes_unfiltered.png after detection
            if (config.verbose) {
                ctx.raw_mask?.let { ctx.debug_images["mask_raw.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
                ctx.img_rgb?.let { ctx.debug_images["bboxes_unfiltered.png"] = drawQuadrilaterals(it, ctx.textlines) }
            }
            if (ctx.textlines.isEmpty()) {
                AppLogger.i(TAG, "[DETECT-NOTEXT] 0 textlines found (det=${detector.name}, image=${processingBitmap.width}x${processingBitmap.height})")
                return TranslationResult.NoText(processingBitmap)
            }

            // Step 4: OCR
            _progress.value = TranslationProgress.Processing("Recognizing text...", 0.25f)
            AppLogger.i(TAG, "[OCR-PRE] Calling recognize() with ${ctx.textlines.size} textlines, image=${processingBitmap.width}x${processingBitmap.height}, engine=${config.ocr.ocrEngine}")
            try {
                ctx.textlines = recognizer.recognize(processingBitmap, ctx.textlines, config.ocr).toMutableList()
                AppLogger.i(TAG, "[OCR-POST] recognize() returned ${ctx.textlines.size} textlines, nonBlank=${ctx.textlines.count { it.text.isNotBlank() }}")
                ctx.textlines = ctx.textlines.sortedWith(compareBy<Quadrilateral> {
                    it.readingOrderIndex ?: Int.MAX_VALUE
                }.thenBy { it.sourceIndex ?: Int.MAX_VALUE }).toMutableList()
                trackModelUsage("ocr", config.ocr.ocrEngine.name)
                AppLogger.i(TAG, "[OCR] Recognized ${ctx.textlines.size} textlines (engine=${config.ocr.ocrEngine})")
                // Log first 5 recognized texts for diagnostics
                ctx.textlines.filter { it.text.isNotBlank() }.take(5).forEachIndexed { i, q ->
                    AppLogger.i(TAG, "[OCR]   [$i] '${q.text}' (prob=${q.probability}, fontSize=${q.fontSize})")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "[OCR] Error: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.textlines = mutableListOf()
            }
            val recognizedCount = ctx.textlines.count { it.text.isNotBlank() }
            Log.i(
                "TranslationPipeline",
                "OCR result: total=${ctx.textlines.size}, nonBlank=$recognizedCount, engine=${config.ocr.ocrEngine}",
            )
            if (ctx.textlines.all { it.text.isBlank() }) {
                AppLogger.i(TAG, "[OCR-NOTEXT] All ${ctx.textlines.size} textlines have blank text — sample texts: ${ctx.textlines.take(3).map { "'${it.text}'" }}")
                return TranslationResult.NoText(processingBitmap)
            }

            // Step 5: Textline merge
            _progress.value = TranslationProgress.Processing("Merging text lines...", 0.35f)
            try {
                ctx.text_regions = merger.merge(ctx.textlines, processingBitmap.width, processingBitmap.height).toMutableList()
            } catch (e: Exception) {
                Log.e(TAG, "Error during textline merge: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.text_regions = mutableListOf()
            }
            if (ctx.text_regions.isEmpty()) {
                AppLogger.i(TAG, "[MERGE] NoText: ${ctx.textlines.size} lines → 0 regions (texts=${ctx.textlines.map { it.text }})")
                return TranslationResult.NoText(processingBitmap)
            }
            AppLogger.i(TAG, "[MERGE] ${ctx.textlines.size} lines → ${ctx.text_regions.size} regions")
            ctx.text_regions.take(5).forEachIndexed { i, r ->
                AppLogger.i(TAG, "[MERGE]   [$i] '${r.text}' (sz=${r.fontSize}, dir=${r.direction})")
            }

            ctx.text_regions = RegionSorter.sortRegions(
                ctx.text_regions,
                rightToLeft = config.renderer.rtl,
                image = processingBitmap,
                forceSimpleSort = config.forceSimpleSort,
            ).toMutableList()

            // Debug: bboxes.png after textline merge and sorting (visualize_textblocks)
            if (config.verbose) {
                ctx.debug_images["bboxes.png"] = VisualizeUtils.visualizeTextBlocks(
                    ctx.img_rgb!!, ctx.text_regions,
                    showPanels = !config.forceSimpleSort,
                    rightToLeft = config.renderer.rtl,
                )
            }

            // Apply pre-dictionary (text replacement before translation)
            ctx.text_regions = applyPreDictionary(ctx.text_regions, config).toMutableList()
            AppLogger.i(TAG, "[PRE-DICT] Applied, ${ctx.text_regions.size} regions remain")

            // Fix unmatched/mismatched brackets (matches Python _run_textline_merge L806-888)
            try {
                val before = ctx.text_regions.size
                ctx.text_regions = fixBrackets(ctx.text_regions).toMutableList()
                AppLogger.i(TAG, "[BRACKETS] After bracket fix: $before → ${ctx.text_regions.size} regions")
                ctx.text_regions.take(5).forEachIndexed { i, r ->
                    AppLogger.i(TAG, "[BRACKETS]   [$i] '${r.text}'")
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "[BRACKETS] Error: ${e.message}", e)
                // Continue with unmodified regions
            }

            // Step 6: Translation
            _progress.value = TranslationProgress.Processing("Translating...", 0.5f)
            // Detect source language from merged text
            val allText = ctx.text_regions.joinToString("") { it.text }
            ctx.from_language = detectSourceLanguage(allText).first
            try {
                ctx.text_regions = translateWithValidationRetry(ctx.text_regions, config, ctx.from_language)
                trackModelUsage("translation", config.translator.translator.name)
            } catch (e: Exception) {
                Log.e(TAG, "Error during translation: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.text_regions = mutableListOf()
            }

            // Step 7: Mask refinement
            _progress.value = TranslationProgress.Processing("Refining mask...", 0.6f)
            try {
                // Filter text regions through BubbleDetector — remove non-bubble regions from mask
                val maskRegions = ctx.text_regions.filter { region ->
                    !BubbleDetector.isIgnore(region, processingBitmap)
                }
                val filteredCount = ctx.text_regions.size - maskRegions.size
                if (filteredCount > 0) {
                    Log.i(TAG, "BubbleDetector filtered $filteredCount non-bubble regions from mask refinement")
                }
                ctx.refined_mask = maskRefiner.refine(
                    maskRegions, processingBitmap, ctx.raw_mask,
                    config.kernelSize, config.maskDilationOffset,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error during mask refinement: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.refined_mask = ctx.raw_mask ?: Bitmap.createBitmap(processingBitmap.width, processingBitmap.height, Bitmap.Config.ARGB_8888).apply { eraseColor(0) }
            }

            // Debug: mask_final.png and inpaint_input.png after mask refinement
            if (config.verbose) {
                ctx.refined_mask?.let { ctx.debug_images["mask_final.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
                if (ctx.img_rgb != null && ctx.refined_mask != null) {
                    ctx.debug_images["inpaint_input.png"] = createMaskOverlay(ctx.img_rgb!!, ctx.refined_mask!!)
                }
            }

            // Step 8: Inpainting
            _progress.value = TranslationProgress.Processing("Inpainting...", 0.7f)
            try {
                ctx.img_inpainted = inpainter.inpaint(processingBitmap, ctx.refined_mask!!, config.inpainter)
                ctx.gimp_mask = ctx.img_inpainted
                trackModelUsage("inpainting", config.inpainter.inpainter.name)
            } catch (e: Exception) {
                Log.e(TAG, "Error during inpainting: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.img_inpainted = processingBitmap
                ctx.gimp_mask = processingBitmap
            }

            // Debug: inpainted.png after inpainting
            if (config.verbose) {
                ctx.img_inpainted?.let { ctx.debug_images["inpainted.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
            }

            // Step 9: Rendering
            _progress.value = TranslationProgress.Processing("Rendering text...", 0.85f)
            AppLogger.i(TAG, "[RENDER-PRE] Passing ${ctx.text_regions.size} regions to renderer:")
            ctx.text_regions.forEachIndexed { i, r ->
                val rect = r.minRect
                AppLogger.i(TAG, "[RENDER-PRE]   [$i] '${r.translation.take(15)}' at (${rect.left.toInt()},${rect.top.toInt()})-(${rect.right.toInt()},${rect.bottom.toInt()}) sz=${"%.0f".format(rect.width())}x${"%.0f".format(rect.height())} fontSize=${"%.0f".format(r.fontSize)} lines=${r.lines.size}")
            }
            try {
                val safeInpainted = ctx.img_inpainted?.copy(Bitmap.Config.ARGB_8888, false) ?: ctx.img_inpainted
                ctx.img_rendered = renderer.render(safeInpainted!!, ctx.text_regions, config.renderer)
            } catch (e: Exception) {
                Log.e(TAG, "Error during rendering: ${e.message}", e)
                if (!config.ignoreErrors) throw e
                ctx.img_rendered = ctx.img_inpainted
            }

            // Debug: final.png after rendering
            if (config.verbose) {
                ctx.img_rendered?.let { ctx.debug_images["final.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
            }

            val rendered = ctx.img_rendered ?: processingBitmap
            var result = dump_image(ctx.input_bitmap, rendered, ctx.img_alpha)

            if (config.upscale.revertUpscaling && processingBitmap !== inputBitmap) {
                result = image_resize(result, inputBitmap.width, inputBitmap.height)
            }

            // Step 10: Finalize
            ctx.result_bitmap = result
            _progress.value = TranslationProgress.Done(result)

            rememberPageTranslation(ctx.text_regions)
            TranslationResult.Success(result, ctx.text_regions)

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
                    if (ctx.text_regions.isEmpty()) {
                        results.add(TranslationResult.NoText(bitmap))
                        continue
                    }
                    ctx.text_regions = translateWithValidationRetry(ctx.text_regions, config, ctx.from_language)
                    completeTranslationPipeline(ctx, processingBitmap = ctx.img_rgb ?: bitmap, inputBitmap = bitmap, config)
                    rememberPageTranslation(ctx.text_regions)
                    results.add(TranslationResult.Success(ctx.result_bitmap ?: bitmap, ctx.text_regions))
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
                    if (ctx.text_regions.isEmpty()) {
                        results.add(TranslationResult.NoText(bitmap))
                        continue
                    }
                    val processingBitmap = ctx.img_rgb ?: bitmap
                    completeTranslationPipeline(ctx, processingBitmap, bitmap, config)
                    rememberPageTranslation(ctx.text_regions)
                    results.add(TranslationResult.Success(ctx.result_bitmap ?: bitmap, ctx.text_regions))
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
                for ((regionIdx, region) in ctx.text_regions.withIndex()) {
                    allTexts.add(region.text)
                    textMapping.add(ctxIdx to regionIdx)
                }
            }

            if (allTexts.isNotEmpty()) {
                val translations = batchTranslateTexts(allTexts, config)

                // Distribute translations back
                var textIdx = 0
                for ((ctxIdx, ctx) in batch.withIndex()) {
                    for ((regionIdx, region) in ctx.text_regions.withIndex()) {
                        if (textIdx < translations.size) {
                            ctx.text_regions[regionIdx] = region.copy(translation = translations[textIdx])
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
                val texts = ctx.text_regions.map { it.text }
                if (texts.isEmpty()) return@async ctx

                val translations = batchTranslateTexts(texts, config)

                for ((i, region) in ctx.text_regions.withIndex()) {
                    if (i < translations.size) {
                        ctx.text_regions[i] = region.copy(translation = translations[i])
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
        val ctx = TranslationContext(input_bitmap = inputBitmap, config = config)
        // Step 1: Optional preprocessing
        var processingBitmap = inputBitmap
        if (config.colorizer.colorizer != ColorizerType.NONE) {
            processingBitmap = colorizer.colorize(processingBitmap, config.colorizer)
        }
        ctx.img_colorized = processingBitmap

        if (config.upscale.upscaleRatio != null && config.upscale.upscaleRatio > 1) {
            processingBitmap = upscaler.upscale(processingBitmap, config.upscale)
        }
        ctx.img_upscaled = processingBitmap

        // Step 2: Downsample large images to prevent OOM
        if (maxOf(processingBitmap.width, processingBitmap.height) > config.detector.detectionSize) {
            processingBitmap = downsample_to_max_size(processingBitmap, config.detector.detectionSize)
        }
        ctx.original_bitmap = if (processingBitmap !== inputBitmap) inputBitmap else null
        ctx.img_rgb = processingBitmap

        // Debug: input.png after preprocessing
        if (config.verbose) {
            ctx.debug_images["input.png"] = processingBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        // Step 3: Detection
        val detectionResult = detector.detect(processingBitmap, config.detector)
        ctx.textlines = detectionResult.textlines.toMutableList()
        ctx.raw_mask = detectionResult.rawMask
        // Debug: mask_raw.png and bboxes_unfiltered.png after detection
        if (config.verbose) {
            ctx.raw_mask?.let { ctx.debug_images["mask_raw.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
            ctx.img_rgb?.let { ctx.debug_images["bboxes_unfiltered.png"] = drawQuadrilaterals(it, ctx.textlines) }
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
            return ctx  // caller checks ctx.text_regions.isEmpty()
        }

        // Step 5: Textline merge
        ctx.text_regions = merger.merge(ctx.textlines, processingBitmap.width, processingBitmap.height).toMutableList()
        if (ctx.text_regions.isEmpty()) {
            Log.i(TAG, "NoText after merge: lines=${ctx.textlines.size}")
            return ctx
        }

        ctx.text_regions = RegionSorter.sortRegions(
            ctx.text_regions,
            rightToLeft = config.renderer.rtl,
            image = processingBitmap,
            forceSimpleSort = config.forceSimpleSort,
        ).toMutableList()

        // Debug: bboxes.png after textline merge and sorting (visualize_textblocks)
        if (config.verbose) {
            ctx.debug_images["bboxes.png"] = VisualizeUtils.visualizeTextBlocks(
                ctx.img_rgb!!, ctx.text_regions,
                showPanels = !config.forceSimpleSort,
                rightToLeft = config.renderer.rtl,
            )
        }

        // Apply pre-dictionary
        ctx.text_regions = applyPreDictionary(ctx.text_regions, config).toMutableList()

        // Fix unmatched/mismatched brackets
        ctx.text_regions = fixBrackets(ctx.text_regions).toMutableList()
        AppLogger.i(TAG, "[BATCH-BRACKETS] After bracket fix: ${ctx.text_regions.size} regions")
        ctx.text_regions.take(3).forEachIndexed { i, r ->
            AppLogger.i(TAG, "[BATCH-BRACKETS]   [$i] '${r.text}'")
        }

        // Detect source language from merged text
        val allText = ctx.text_regions.joinToString("") { it.text }
        ctx.from_language = detectSourceLanguage(allText).first

        return ctx  // contains text_regions ready for translation
    }

    /**
     * Fixes unmatched/mismatched brackets in text regions.
     * Ported from Python _run_textline_merge (manga_translator.py L806-888).
     *
     * Two-pass algorithm:
     * 1. First pass: mark unmatched left/right brackets for removal
     * 2. Second pass: replace mismatched right brackets with correct counterparts
     */
    private fun fixBrackets(regions: List<TextBlock>): List<TextBlock> {
        val bracketPairs = mapOf(
            '(' to ')', '（' to '）', '[' to ']', '【' to '】', '{' to '}',
            '〔' to '〕', '〈' to '〉', '「' to '」', '"' to '"', '＂' to '＂',
            '\'' to '\'', '“' to '”', '《' to '》', '『' to '』',
            '〝' to '〞', '﹁' to '﹂', '﹃' to '﹄',
            '⸂' to '⸃', '⸄' to '⸅', '⸉' to '⸊', '⸌' to '⸍', '⸜' to '⸝', '⸠' to '⸡',
            '‹' to '›', '«' to '»', '＜' to '＞', '<' to '>',
        )
        val leftSymbols = bracketPairs.keys
        val rightSymbols = bracketPairs.values.toSet()

        return regions.map { region ->
            val strippedText = region.text.trim()
            if (strippedText.isEmpty()) return@map region

            val hasBrackets = strippedText.any { it in leftSymbols || it in rightSymbols }
            if (!hasBrackets) return@map region

            // First pass: mark unmatched brackets
            val toSkip = mutableSetOf<Int>()
            val stack = ArrayDeque<Pair<Int, Char>>()
            for ((i, ch) in strippedText.withIndex()) {
                if (ch in leftSymbols) {
                    stack.addLast(i to ch)
                } else if (ch in rightSymbols) {
                    if (stack.isNotEmpty()) {
                        stack.removeLast()
                    } else {
                        toSkip.add(i) // Unmatched right bracket
                    }
                }
            }
            // Mark unmatched left brackets
            for ((pos, _) in stack) {
                toSkip.add(pos)
            }

            val removedSymbols = toSkip.isNotEmpty()

            // Second pass: fix mismatched brackets and filter
            val resultChars = mutableListOf<Char>()
            val bracketStack = ArrayDeque<Char>()
            for ((i, ch) in strippedText.withIndex()) {
                if (i in toSkip) continue

                if (ch in leftSymbols) {
                    bracketStack.addLast(ch)
                    resultChars.add(ch)
                } else if (ch in rightSymbols) {
                    if (bracketStack.isNotEmpty()) {
                        val leftBracket = bracketStack.removeLast()
                        val expectedRight = bracketPairs[leftBracket]
                        if (ch != expectedRight) {
                            resultChars.add(expectedRight ?: ch)
                        } else {
                            resultChars.add(ch)
                        }
                    }
                } else {
                    resultChars.add(ch)
                }
            }

            val cleaned = resultChars.joinToString("").trim()
            region.copy(text = cleaned)
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
        // Filter text regions through BubbleDetector — remove non-bubble regions from mask
        val maskRegions = ctx.text_regions.filter { region ->
            !BubbleDetector.isIgnore(region, processingBitmap)
        }
        val filteredCount = ctx.text_regions.size - maskRegions.size
        if (filteredCount > 0) {
            Log.i(TAG, "BubbleDetector filtered $filteredCount non-bubble regions from mask refinement")
        }
        ctx.refined_mask = maskRefiner.refine(
            maskRegions, processingBitmap, ctx.raw_mask,
            config.kernelSize, config.maskDilationOffset,
        )

        // Debug: mask_final.png and inpaint_input.png after mask refinement
        if (config.verbose) {
            ctx.refined_mask?.let { ctx.debug_images["mask_final.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
            ctx.img_rgb?.let { img ->
                ctx.refined_mask?.let { mask ->
                    ctx.debug_images["inpaint_input.png"] = createMaskOverlay(img, mask)
                }
            }
        }

        // Step 8: Inpainting
        ctx.img_inpainted = inpainter.inpaint(processingBitmap, ctx.refined_mask!!, config.inpainter)
        ctx.gimp_mask = ctx.img_inpainted

        // Debug: inpainted.png after inpainting
        if (config.verbose) {
            ctx.img_inpainted?.let { ctx.debug_images["inpainted.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
        }

        // Step 9: Rendering
        val safeInpainted = ctx.img_inpainted?.copy(Bitmap.Config.ARGB_8888, false) ?: ctx.img_inpainted
        ctx.img_rendered = renderer.render(safeInpainted!!, ctx.text_regions, config.renderer)

        // Debug: final.png after rendering
        if (config.verbose) {
            ctx.img_rendered?.let { ctx.debug_images["final.png"] = it.copy(Bitmap.Config.ARGB_8888, false) }
        }

        // Revert upscale if needed
        if (config.upscale.revertUpscaling && processingBitmap !== inputBitmap) {
            ctx.img_rendered = image_resize(ctx.img_rendered!!, inputBitmap.width, inputBitmap.height)
        }

        // Step 10: Finalize
        val result = ctx.img_rendered ?: processingBitmap
        ctx.result_bitmap = result

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

        var filteredBlank = 0
        var filteredSkipLang = 0
        var filteredSameLang = 0
        var filteredDigits = 0
        var filteredRegex = 0
        var filteredUnchanged = 0

        val result = regions.filter { region ->
            val translation = region.translation
            if (translation.isBlank()) { filteredBlank++; return@filter false }

            val sourceLanguage = detectSourceLanguage(region.text).first
            if (skipLanguages.isNotEmpty() && sourceLanguage in skipLanguages) {
                Log.i(TAG, "Filtered out: $translation")
                Log.i(TAG, "Reason: sourceLanguage=$sourceLanguage in skipLanguages=$skipLanguages")
                filteredSkipLang++; return@filter false
            }

            if (sourceLanguage != "UNKNOWN" && sourceLanguage == config.translator.targetLanguage.uppercase()) {
                Log.i(TAG, "Filtered out: $translation")
                Log.i(TAG, "Reason: sourceLanguage=$sourceLanguage matches targetLanguage")
                filteredSameLang++; return@filter false
            }

            if (config.translator.translator != TranslatorType.NONE) {
                if (translation.all { it.isDigit() }) {
                    Log.i(TAG, "Filtered out: $translation")
                    Log.i(TAG, "Reason: translation is all digits")
                    filteredDigits++; return@filter false
                }
                if (filterTextRegex != null && filterTextRegex!!.containsMatchIn(translation)) {
                    Log.i(TAG, "Filtered out: $translation")
                    Log.i(TAG, "Reason: matches filterText=${config.filterText}")
                    filteredRegex++; return@filter false
                }
                if (config.translator.translator != TranslatorType.ORIGINAL &&
                    region.text.trim().lowercase() == translation.trim().lowercase()
                ) {
                    Log.i(TAG, "Filtered out: $translation")
                    Log.i(TAG, "Reason: translation unchanged from source text")
                    filteredUnchanged++; return@filter false
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

        AppLogger.i(TAG, "[FILTER] ${regions.size} → ${result.size} regions (blank=$filteredBlank skipLang=$filteredSkipLang sameLang=$filteredSameLang digits=$filteredDigits regex=$filteredRegex unchanged=$filteredUnchanged)")
        return result
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
