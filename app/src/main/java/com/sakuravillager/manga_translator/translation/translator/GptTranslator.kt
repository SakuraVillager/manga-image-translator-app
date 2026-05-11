package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import com.sakuravillager.manga_translator.translation.glossary.GlossaryLoader
import com.sakuravillager.manga_translator.translation.translator.common.CommonTranslator
import com.sakuravillager.manga_translator.translation.util.RateLimitRetry
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

class GptTranslator(
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    },
    private val glossaryPath: String? = null,
) : CommonTranslator() {

    override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
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
        "POL" to "Polish",
        "CNR" to "Montenegrin",
        "SRP" to "Serbian",
        "HRV" to "Croatian",
        "THA" to "Thai",
        "FIL" to "Filipino (Tagalog)",
    )

    override val _MAX_REQUESTS_PER_MINUTE: Int = 20

    // Captured from translate() to make available in _translate()
    private var _apiKey: String? = null
    private var _apiBase: String? = null
    private var _model: String? = null
    private var _prevContext: String? = null

    override suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig,
    ): List<String> {
        _apiKey = config.apiKey
        _apiBase = config.apiBase
        _model = config.model
        _prevContext = config.prevContext
        return super.translate(texts, fromLanguage, toLanguage, config)
    }

    override suspend fun _translate(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> {
        return try {
            val endpoint = "${_apiBase?.trimEnd('/') ?: "https://api.openai.com/v1"}/chat/completions"

            val contextPrompt = _prevContext
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "\n\nPrevious page context:\n$it" }
                .orEmpty()

            val systemPrompt = "You are a professional manga translator. Translate the following text lines from $fromLang to $toLang. Preserve line count exactly. Return only the translations, one per line, no explanations, no numbering.$contextPrompt"

            val numberedTexts = queries.mapIndexed { i, t -> "<|${i + 1}|>$t" }.joinToString("\n")

            // Glossary injection (if configured and has matching terms)
            val glossaryMessages = if (glossaryPath != null) {
                val entries = GlossaryLoader.load(glossaryPath)
                val relevant = GlossaryLoader.extractRelevantTerms(entries, queries)
                if (relevant.isNotEmpty()) {
                    val glossaryText = relevant.entries.joinToString("\n") { "${it.key} → ${it.value}" }
                    listOf(ChatMessage(role = "system", content = "Please translate based on the following glossary:\n$glossaryText"))
                } else emptyList()
            } else emptyList()

            val request = ChatCompletionRequest(
                model = _model ?: "gpt-4o-mini",
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                ) + glossaryMessages + listOf(
                    ChatMessage(role = "user", content = numberedTexts),
                ),
            )

            val response: ChatCompletionResponse = RateLimitRetry.retryWithBackoff(TAG) {
                httpClient.post(endpoint) {
                    header(HttpHeaders.Authorization, "Bearer ${_apiKey ?: ""}")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body()
            }

            val content = response.choices.firstOrNull()?.message?.content ?: return queries

            // Parse response: remove <|N|> markers and split by lines
            val lines = content.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { it.replace(Regex("<\\|\\d+\\|>"), "").trim() }

            // Ensure output count matches input count
            if (lines.size != queries.size) {
                Log.w(TAG, "Line count mismatch: expected ${queries.size}, got ${lines.size}. Falling back.")
                return queries
            }

            lines
        } catch (e: Exception) {
            Log.e(TAG, "Translation API error: ${e.message}", e)
            queries  // Graceful degradation - return original
        }
    }

    companion object {
        private const val TAG = "GptTranslator"
    }
}
