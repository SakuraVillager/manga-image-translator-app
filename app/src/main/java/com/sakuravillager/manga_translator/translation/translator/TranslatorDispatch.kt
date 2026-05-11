package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import com.sakuravillager.manga_translator.translation.data.config.TranslatorType
import com.sakuravillager.manga_translator.translation.translator.common.OfflineTranslator
import com.sakuravillager.manga_translator.translation.translator.common.TRANSLATOR_CACHE
import com.sakuravillager.manga_translator.translation.translator.common.getTranslator

/**
 * Represents a single step in a translator chain.
 *
 * @property translatorType The type of translator to use for this step.
 * @property targetLanguage The target language code for this step (e.g. "ENG", "CHS").
 */
data class ChainStep(
    val translatorType: TranslatorType,
    val targetLanguage: String,
)

/**
 * Parses a translator chain string into a list of [ChainStep]s.
 *
 * Chain format: `"TYPE1:LANG1;TYPE2:LANG2"`
 * e.g. `"GPT_COMPATIBLE:ENG;DEEPL:CHS"`
 *
 * Returns an empty list if [chain] is null or blank.
 */
fun parseChain(chain: String?): List<ChainStep> {
    if (chain.isNullOrBlank()) return emptyList()
    return chain.split(";").map { segment ->
        val parts = segment.split(":")
        val typeName = parts[0].trim()
        val lang = if (parts.size >= 2) parts[1].trim() else "CHS"
        val type = try {
            TranslatorType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown translator type '$typeName' in chain, falling back to NONE")
            TranslatorType.NONE
        }
        ChainStep(type, lang)
    }
}

/**
 * Creates a translator instance for the given [type].
 *
 * Used as the factory lambda for [getTranslator].
 */
fun createTranslator(type: TranslatorType): Translator = when (type) {
    TranslatorType.GPT_COMPATIBLE -> GptTranslator()
    TranslatorType.DEEPL -> DeeplTranslator()
    TranslatorType.NONE -> NoOpTranslator()
    TranslatorType.ORIGINAL -> OriginalTranslator()
    TranslatorType.BAIDU -> {
        Log.w(TAG, "Baidu translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.YOUDAO -> {
        Log.w(TAG, "Youdao translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    // Wave 2 offline translators — placeholder stubs
    TranslatorType.NLLB -> {
        Log.w(TAG, "NLLB translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.NLLB_BIG -> {
        Log.w(TAG, "NLLB_BIG translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.SUGOI -> {
        Log.w(TAG, "SUGOI translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.JPARACRAWL -> {
        Log.w(TAG, "JPARACRAWL translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.JPARACRAWL_BIG -> {
        Log.w(TAG, "JPARACRAWL_BIG translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.M2M100 -> {
        Log.w(TAG, "M2M100 translator is not yet implemented via dispatch, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.M2M100_BIG -> {
        Log.w(TAG, "M2M100_BIG translator is not yet implemented via dispatch, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.MBART50 -> {
        Log.w(TAG, "MBART50 translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.QWEN2 -> {
        Log.w(TAG, "QWEN2 translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    TranslatorType.QWEN2_BIG -> {
        Log.w(TAG, "QWEN2_BIG translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
    else -> {
        Log.w(TAG, "$type translator is not yet implemented, falling back to NONE")
        NoOpTranslator()
    }
}

/**
 * Returns true if the given [type] corresponds to an offline / local-model translator.
 *
 * Used to determine whether special load/unload lifecycle management is needed
 * for the translator instance. All new ML model translators (NLLB, Sugoi, etc.)
 * are offline translators.
 */
fun isOfflineTranslator(type: TranslatorType): Boolean = when (type) {
    TranslatorType.NLLB,
    TranslatorType.NLLB_BIG,
    TranslatorType.SUGOI,
    TranslatorType.JPARACRAWL,
    TranslatorType.JPARACRAWL_BIG,
    TranslatorType.M2M100,
    TranslatorType.M2M100_BIG,
    TranslatorType.MBART50,
    TranslatorType.QWEN2,
    TranslatorType.QWEN2_BIG -> true
    // Existing translators are all online / API-based
    else -> false
}

/**
 * Dispatches translation through a chain of translators.
 *
 * Parses the [chain] string into steps and feeds the [queries] through each
 * translator sequentially. The output of each step becomes the input of the
 * next, allowing multi-stage translation (e.g. English → Japanese → Chinese).
 *
 * When [chain] is null or blank, falls back to using the single translator
 * specified in [translatorConfig.translator].
 *
 * Matches Python `manga_translator/translators/__init__.py` lines 89–128.
 *
 * @param chain           The translator chain string (e.g. `"GPT_COMPATIBLE:ENG;DEEPL:CHS"`).
 *                        Null or blank means no chaining — use the translator from config.
 * @param queries         The source text segments to translate.
 * @param translatorConfig Configuration for the translator(s).
 * @param useMtpe         Whether to use Machine Translation Post-Editing.
 *                        Currently reserved for future use.
 * @return The translated text segments, one per input query.
 */
suspend fun dispatch(
    chain: String?,
    queries: List<String>,
    translatorConfig: TranslatorConfig,
    useMtpe: Boolean = false,
): List<String> {
    if (queries.isEmpty()) return queries

    val steps = parseChain(chain)
    if (steps.isEmpty()) {
        // No chain — use the single translator from config
        return dispatchSingle(translatorConfig, queries, useMtpe)
    }

    var currentTexts = queries

    for ((index, step) in steps.withIndex()) {
        val translator = getTranslator(step.translatorType) {
            createTranslator(step.translatorType)
        } as Translator

        Log.d(TAG, "Chain step ${index + 1}/${steps.size}: ${step.translatorType} → ${step.targetLanguage}")

        // Offline translators: load before translate, unload after
        if (translator is OfflineTranslator) {
            translator.load("auto", step.targetLanguage, DEFAULT_DEVICE)
        }

        val stepConfig = translatorConfig.copy(targetLanguage = step.targetLanguage)
        val translated = translator.translate(
            currentTexts,
            "auto",
            step.targetLanguage,
            stepConfig,
        )

        if (translator is OfflineTranslator) {
            translator.unload()
        }

        // Validate output size
        if (translated.size != currentTexts.size) {
            Log.w(
                TAG,
                "Step ${index + 1} returned ${translated.size} translations for ${currentTexts.size} inputs; " +
                    "keeping previous output",
            )
            // Keep currentTexts unchanged for this step
            continue
        }

        currentTexts = translated
    }

    return currentTexts
}

/**
 * Single-translator dispatch for when no chain is specified.
 * Called internally by [dispatch] when [chain] is null or blank.
 */
private suspend fun dispatchSingle(
    translatorConfig: TranslatorConfig,
    queries: List<String>,
    useMtpe: Boolean,
): List<String> {
    val translator = getTranslator(translatorConfig.translator) {
        createTranslator(translatorConfig.translator)
    } as Translator

    if (translator is OfflineTranslator) {
        translator.load("auto", translatorConfig.targetLanguage, DEFAULT_DEVICE)
    }

    val result = translator.translate(
        queries,
        "auto",
        translatorConfig.targetLanguage,
        translatorConfig,
    )

    if (translator is OfflineTranslator) {
        translator.unload()
    }

    return result
}

/**
 * Prepares translators specified by the [chain] string for use.
 *
 * For each step in the chain:
 * 1. Retrieves or creates the translator instance (cached).
 * 2. If the translator is an [OfflineTranslator], calls [OfflineTranslator.load] to
 *    initialise the local model.
 * 3. Calls [Translator.prepare] on the translator.
 *
 * When [chain] is null or blank, prepares the single translator from
 * [translatorConfig.translator] instead.
 *
 * Matches Python `manga_translator/translators/__init__.py` lines 81–86.
 */
suspend fun prepare(chain: String?, translatorConfig: TranslatorConfig) {
    val steps = parseChain(chain)
    if (steps.isEmpty()) {
        prepareSingle(translatorConfig)
        return
    }

    for ((index, step) in steps.withIndex()) {
        val translator = getTranslator(step.translatorType) {
            createTranslator(step.translatorType)
        } as Translator

        Log.d(TAG, "Preparing chain step ${index + 1}/${steps.size}: ${step.translatorType}")

        if (translator is OfflineTranslator) {
            translator.load("auto", step.targetLanguage, DEFAULT_DEVICE)
        }
        translator.prepare()
    }
}

/**
 * Single-translator prepare for when no chain is specified.
 */
private suspend fun prepareSingle(translatorConfig: TranslatorConfig) {
    val translator = getTranslator(translatorConfig.translator) {
        createTranslator(translatorConfig.translator)
    } as Translator

    if (translator is OfflineTranslator) {
        translator.load("auto", translatorConfig.targetLanguage, DEFAULT_DEVICE)
    }
    translator.prepare()
}

/**
 * Unloads and removes a cached translator instance.
 *
 * 1. Removes the translator from [TRANSLATOR_CACHE].
 * 2. If the translator is an [OfflineTranslator], calls [OfflineTranslator.unload]
 *    to release local model resources.
 *
 * If no translator is cached for [key], this is a no-op.
 *
 * Matches Python `manga_translator/translators/__init__.py` lines 193–194.
 */
suspend fun unload(key: TranslatorType) {
    val translator = TRANSLATOR_CACHE.remove(key) as? Translator ?: return

    Log.d(TAG, "Unloading translator: $key")
    if (translator is OfflineTranslator) {
        translator.unload()
    }
}

/**
 * Default device string used for [OfflineTranslator.load] calls.
 * Android/iOS devices typically use "cpu".
 */
private const val DEFAULT_DEVICE = "cpu"

private const val TAG = "TranslatorDispatch"
