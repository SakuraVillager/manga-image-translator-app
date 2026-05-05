package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import com.sakuravillager.manga_translator.translation.api.Translator
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class DeeplTranslator(
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    },
) : Translator {
    override val name = "DeepL"
    private var _isReady = false
    override val isReady get() = _isReady

    override suspend fun prepare() {
        Log.d(name, "DeeplTranslator prepared")
        _isReady = true
    }

    override suspend fun release() {
        Log.d(name, "DeeplTranslator released")
        _isReady = false
    }

    override val supportedSourceLanguages: Set<String> = LANGUAGE_CODE_MAP.keys
    override val supportedTargetLanguages: Set<String> = LANGUAGE_CODE_MAP.keys

    override suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig,
    ): List<String> {
        // Pass through empty input
        if (texts.isEmpty() || texts.all { it.isBlank() }) return texts

        // Convert internal language codes to DeepL API codes
        val targetLang = LANGUAGE_CODE_MAP[toLanguage]
        val sourceLang = LANGUAGE_CODE_MAP[fromLanguage]

        // If target language is not supported, we cannot translate
        if (targetLang == null) {
            Log.w(name, "Unsupported target language: $toLanguage")
            return texts
        }

        return try {
            val endpoint = "${config.apiBase?.trimEnd('/') ?: "https://api-free.deepl.com/v2"}/translate"

            val request = DeeplTranslateRequest(
                text = texts,
                targetLang = targetLang,
                sourceLang = sourceLang,
            )

            val response: DeeplTranslateResponse = retryWithBackoff {
                httpClient.post(endpoint) {
                    header(HttpHeaders.Authorization, "DeepL-Auth-Key ${config.apiKey ?: ""}")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body()
            }

            response.translations.map { it.text }
        } catch (e: Exception) {
            Log.e(name, "Translation API error: ${e.message}", e)
            texts // Graceful degradation - return original
        }
    }

    override fun supportsLanguagePair(from: String, to: String): Boolean {
        val sourceOk = LANGUAGE_CODE_MAP.containsKey(from)
        val targetOk = LANGUAGE_CODE_MAP.containsKey(to)
        return sourceOk || targetOk
    }

    private suspend fun <T> retryWithBackoff(
        maxRetries: Int = 3,
        baseDelayMs: Long = 1000L,
        maxDelayMs: Long = 30_000L,
        block: suspend () -> T,
    ): T {
        var lastException: Exception? = null
        for (attempt in 0..maxRetries) {
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries) {
                    val delayMs = when {
                        e is ClientRequestException && e.response.status.value == 429 -> {
                            val retryAfter = e.response.headers[HttpHeaders.RetryAfter]
                            retryAfter?.toLongOrNull()?.times(1000L)
                                ?: minOf(baseDelayMs * (1L shl attempt), maxDelayMs)
                        }
                        else -> minOf(baseDelayMs * (1L shl attempt), maxDelayMs)
                    }
                    Log.w(TAG, "Attempt ${attempt + 1}/${maxRetries + 1} failed: ${e.message}. Retrying in ${delayMs}ms...")
                    delay(delayMs)
                }
            }
        }
        throw lastException!!
    }

    companion object {
        private const val TAG = "DeeplTranslator"

        private val LANGUAGE_CODE_MAP = mapOf(
            "CHS" to "ZH",
            "CHT" to "ZH",
            "ENG" to "EN-US",
            "JPN" to "JA",
            "KOR" to "KO",
            "FRA" to "FR",
            "DEU" to "DE",
            "ESP" to "ES",
            "ITA" to "IT",
            "NLD" to "NL",
            "PLK" to "PL",
            "PTB" to "PT-BR",
            "RUS" to "RU",
        )
    }
}

@Serializable
data class DeeplTranslateRequest(
    val text: List<String>,
    @SerialName("target_lang") val targetLang: String,
    @SerialName("source_lang") val sourceLang: String? = null,
)

@Serializable
data class DeeplTranslateResponse(
    val translations: List<DeeplTranslation>,
)

@Serializable
data class DeeplTranslation(
    @SerialName("detected_source_language") val detectedSourceLanguage: String,
    val text: String,
)
