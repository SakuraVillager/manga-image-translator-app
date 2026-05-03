package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class GptTranslator(private val httpClient: HttpClient = HttpClient {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            isLenient = true
        })
    }
}) : Translator {
    override val name = "GPT Compatible"
    private var _isReady = false
    override val isReady get() = _isReady

    override suspend fun prepare() {
        Log.d(name, "GptTranslator prepared")
        _isReady = true
    }

    override suspend fun release() {
        Log.d(name, "GptTranslator released")
        _isReady = false
    }

    override val supportedSourceLanguages: Set<String> = VALID_LANGUAGES.keys
    override val supportedTargetLanguages: Set<String> = VALID_LANGUAGES.keys

    override suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig,
    ): List<String> {
        if (texts.isEmpty() || texts.all { it.isBlank() }) return texts

        return try {
            val endpoint = "${config.apiBase?.trimEnd('/') ?: "https://api.openai.com/v1"}/chat/completions"
            val sourceLang = VALID_LANGUAGES[fromLanguage] ?: fromLanguage
            val targetLang = VALID_LANGUAGES[toLanguage] ?: toLanguage

            val systemPrompt = "You are a professional manga translator. Translate the following text lines from $sourceLang to $targetLang. Preserve line count exactly. Return only the translations, one per line, no explanations, no numbering."

            val numberedTexts = texts.mapIndexed { i, t -> "<|${i + 1}|>$t" }.joinToString("\n")

            val request = ChatCompletionRequest(
                model = config.model ?: "gpt-4o-mini",
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = numberedTexts),
                ),
            )

            val response: ChatCompletionResponse = httpClient.post(endpoint) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey ?: ""}")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            val content = response.choices.firstOrNull()?.message?.content ?: return texts

            // Parse response: remove <|N|> markers and split by lines
            val lines = content.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.replace(Regex("<\\|\\d+\\|>"), "").trim() }

            // Ensure output count matches input count
            if (lines.size != texts.size) {
                Log.w(name, "Line count mismatch: expected ${texts.size}, got ${lines.size}. Falling back.")
                return texts
            }

            lines
        } catch (e: Exception) {
            Log.e(name, "Translation API error: ${e.message}", e)
            texts  // Graceful degradation - return original
        }
    }

    override fun supportsLanguagePair(from: String, to: String): Boolean = true

    companion object {
        val VALID_LANGUAGES = mapOf(
            "CHS" to "Simplified Chinese",
            "CHT" to "Traditional Chinese",
            "CSY" to "Czech",
            "NLD" to "Dutch",
            "ENG" to "English",
            "FRA" to "French",
            "DEU" to "German",
            "HUN" to "Hungarian",
            "ITA" to "Italian",
            "JPN" to "Japanese",
            "KOR" to "Korean",
            "PLK" to "Polish",
            "PTB" to "Portuguese (Brazil)",
            "ROM" to "Romanian",
            "RUS" to "Russian",
            "ESP" to "Spanish",
            "TRK" to "Turkish",
            "UKR" to "Ukrainian",
            "VIN" to "Vietnamese",
            "ARA" to "Arabic",
            "IND" to "Indonesian",
        )
    }
}
