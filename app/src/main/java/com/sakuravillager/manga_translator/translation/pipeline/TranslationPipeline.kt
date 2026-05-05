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
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import com.sakuravillager.manga_translator.translation.sort.RegionSorter
import com.sakuravillager.manga_translator.translation.util.downsampleToMaxSize
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
            // Step 0: Downsample large images to prevent OOM
            val processingBitmap = if (maxOf(inputBitmap.width, inputBitmap.height) > config.detector.detectionSize) {
                downsampleToMaxSize(inputBitmap, config.detector.detectionSize)
            } else {
                inputBitmap
            }
            ctx.originalBitmap = if (processingBitmap !== inputBitmap) inputBitmap else null

            // Step 1: Prepare
            _progress.value = TranslationProgress.Loading("Preparing models...")
            detector.prepare()
            recognizer.prepare()
            merger.prepare()
            translator.prepare()
            maskRefiner.prepare()
            inpainter.prepare()
            renderer.prepare()

            // Step 2: Detection
            _progress.value = TranslationProgress.Processing("Detecting text...", 0.1f)
            val detectionResult = detector.detect(processingBitmap, config.detector)
            ctx.textlines = detectionResult.textlines.toMutableList()
            ctx.rawMask = detectionResult.rawMask
            if (ctx.textlines.isEmpty()) return TranslationResult.NoText(processingBitmap)

            // Step 3: OCR
            _progress.value = TranslationProgress.Processing("Recognizing text...", 0.25f)
            ctx.textlines = recognizer.recognize(processingBitmap, ctx.textlines, config.ocr).toMutableList()
            if (ctx.textlines.all { it.text.isBlank() }) return TranslationResult.NoText(processingBitmap)

            // Step 4: Textline merge
            _progress.value = TranslationProgress.Processing("Merging text lines...", 0.35f)
            ctx.textRegions = merger.merge(ctx.textlines, processingBitmap.width, processingBitmap.height).toMutableList()
            if (ctx.textRegions.isEmpty()) return TranslationResult.NoText(processingBitmap)

            ctx.textRegions = RegionSorter.sortRegions(
                ctx.textRegions,
                rightToLeft = config.renderer.rtl,
                image = processingBitmap,
                forceSimpleSort = false,
            ).toMutableList()

            // Apply pre-dictionary (text replacement before translation)
            ctx.textRegions = applyPreDictionary(ctx.textRegions, config).toMutableList()

            // Step 5: Translation
            _progress.value = TranslationProgress.Processing("Translating...", 0.5f)
            ctx.textRegions = translateWithValidationRetry(ctx.textRegions, config)

            // Step 6: Mask refinement
            _progress.value = TranslationProgress.Processing("Refining mask...", 0.6f)
            ctx.refinedMask = maskRefiner.refine(
                ctx.textRegions, processingBitmap, ctx.rawMask,
                config.kernelSize, config.maskDilationOffset
            )

            // Step 7: Inpainting
            _progress.value = TranslationProgress.Processing("Inpainting...", 0.7f)
            ctx.imgInpainted = inpainter.inpaint(processingBitmap, ctx.refinedMask!!, config.inpainter)

            // Step 8: Rendering
            _progress.value = TranslationProgress.Processing("Rendering text...", 0.85f)
            val safeInpainted = ctx.imgInpainted?.copy(Bitmap.Config.ARGB_8888, false) ?: ctx.imgInpainted
            ctx.imgRendered = renderer.render(safeInpainted!!, ctx.textRegions, config.renderer)

            // Step 9: Finalize
            val result = ctx.imgRendered ?: processingBitmap
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

    private suspend fun translateWithValidationRetry(
        regions: List<TextBlock>,
        config: TranslationConfig,
    ): MutableList<TextBlock> {
        if (regions.isEmpty()) return mutableListOf()
        if (config.translator.translator == TranslatorType.NONE) {
            return regions.map { region -> region.copy(translation = "") }.toMutableList()
        }

        val originalTexts = regions.map { it.text }
        var candidateTranslations = originalTexts

        repeat((if (config.enablePostTranslationCheck) config.postCheckMaxRetryAttempts else 0) + 1) { attempt ->
            candidateTranslations = translator.translate(
                originalTexts,
                "auto",
                config.translator.targetLanguage,
                config.translator,
            )

            val translatedRegions = regions.zip(candidateTranslations) { region, translation ->
                region.copy(translation = translation)
            }
            val postDictRegions = applyPostDictionary(translatedRegions, config)
            val normalizedRegions = normalizePunctuation(postDictRegions)
            val validatedRegions = if (config.enablePostTranslationCheck) {
                normalizedRegions.map { region ->
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
                normalizedRegions
            }

            val filteredRegions = applyFinalFiltering(validatedRegions, config)

            val allValid = filteredRegions.all { region ->
                region.translation.isNotBlank() && region.translation != region.text || region.text.isBlank()
            }

            if (allValid || attempt == config.postCheckMaxRetryAttempts || !config.enablePostTranslationCheck) {
                return filteredRegions.toMutableList()
            }
        }

        return regions.map { region -> region.copy(translation = region.text) }.toMutableList()
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

            val sourceLanguage = detectSourceLanguage(region.text)
            if (skipLanguages.isNotEmpty() && sourceLanguage in skipLanguages) {
                return@filter false
            }

            if (sourceLanguage != "UNKNOWN" && sourceLanguage == config.translator.targetLanguage.uppercase()) {
                return@filter false
            }

            if (config.translator.translator != TranslatorType.NONE) {
                if (translation.all { it.isDigit() }) {
                    return@filter false
                }
                if (config.filterText != null && Regex(config.filterText).containsMatchIn(translation)) {
                    return@filter false
                }
                if (config.translator.translator != TranslatorType.ORIGINAL &&
                    region.text.trim().lowercase() == translation.trim().lowercase()
                ) {
                    return@filter false
                }
            }
            true
        }
    }

    private fun String.isAscii(): Boolean = all { it.code < 128 }

    private fun detectSourceLanguage(text: String): String {
        if (text.isBlank()) return "UNKNOWN"

        var cjkCount = 0
        var hiraganaCount = 0
        var katakanaCount = 0
        var koreanCount = 0
        var arabicCount = 0
        var latinCount = 0

        for (ch in text) {
            when {
                ch in '\u3040'..'\u309f' -> hiraganaCount++
                ch in '\u30a0'..'\u30ff' -> katakanaCount++
                ch in '\uac00'..'\ud7af' || ch in '\u1100'..'\u11ff' -> koreanCount++
                ch in '\u0600'..'\u06ff' || ch in '\u0750'..'\u077f' || ch in '\u08a0'..'\u08ff' -> arabicCount++
                ch.isLetter() && ch.code < 128 -> latinCount++
                ch in '\u4e00'..'\u9fff' || ch in '\u3400'..'\u4dbf' || ch in '\uf900'..'\ufaff' -> cjkCount++
            }
        }

        return when {
            hiraganaCount > 0 || katakanaCount > 0 -> "JPN"
            koreanCount > 0 -> "KOR"
            arabicCount > 0 -> "ARA"
            cjkCount > 0 -> "CHS"
            latinCount > 0 -> "ENG"
            else -> "UNKNOWN"
        }
    }
}
