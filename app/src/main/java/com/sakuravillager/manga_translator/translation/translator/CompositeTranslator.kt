package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig

data class TranslatorStep(
    val translator: Translator,
    val targetLanguage: String,
)

class CompositeTranslator(
    private val steps: List<TranslatorStep>,
) : Translator {
    override val name: String = "CompositeTranslator"
    private var _isReady = false

    override val isReady: Boolean
        get() = _isReady && steps.all { it.translator.isReady }

    override val supportedSourceLanguages: Set<String>
        get() = steps.firstOrNull()?.translator?.supportedSourceLanguages ?: emptySet()

    override val supportedTargetLanguages: Set<String>
        get() = steps.lastOrNull()?.translator?.supportedTargetLanguages ?: emptySet()

    override suspend fun prepare() {
        for (step in steps) {
            if (!step.translator.isReady) {
                step.translator.prepare()
            }
        }
        _isReady = true
    }

    override suspend fun release() {
        for (step in steps.asReversed()) {
            if (step.translator.isReady) {
                step.translator.release()
            }
        }
        _isReady = false
    }

    override suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig,
    ): List<String> {
        if (texts.isEmpty() || texts.all { it.isBlank() }) return texts
        if (steps.isEmpty()) return texts

        var currentTexts = texts
        var currentSourceLanguage = fromLanguage

        for ((index, step) in steps.withIndex()) {
            val stepConfig = config.copy(targetLanguage = step.targetLanguage)
            val translatedTexts = try {
                step.translator.translate(
                    currentTexts,
                    currentSourceLanguage,
                    step.targetLanguage,
                    stepConfig,
                )
            } catch (e: Exception) {
                Log.w(name, "Step ${index + 1} (${step.translator.name}) failed: ${e.message}")
                currentTexts
            }

            if (translatedTexts.size != currentTexts.size) {
                Log.w(
                    name,
                    "Step ${index + 1} (${step.translator.name}) returned ${translatedTexts.size} lines for ${currentTexts.size} inputs; keeping previous text",
                )
                continue
            }

            currentTexts = translatedTexts
            currentSourceLanguage = step.targetLanguage
        }

        return currentTexts
    }

    override fun supportsLanguagePair(from: String, to: String): Boolean {
        if (steps.isEmpty()) return false
        return steps.all { it.translator.supportsLanguagePair(from, to) }
    }
}