package com.sakuravillager.manga_translator.translation.translator

import android.util.Log
import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import com.sakuravillager.manga_translator.translation.translator.common.CommonTranslator
import com.sakuravillager.manga_translator.translation.util.RateLimitRetry
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
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
) : CommonTranslator() {

    override val name = "DeepL"

    override val _LANGUAGE_CODE_MAP: Map<String, String> = mapOf(
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

    override val supportedSourceLanguages: Set<String>
        get() = _LANGUAGE_CODE_MAP.keys

    override suspend fun prepare() {
        Log.d(name, "DeeplTranslator prepared")
    }

    override suspend fun release() {
        Log.d(name, "DeeplTranslator released")
    }

    // ─── Config storage ─────────────────────────────────────────────
    // _translate() does not receive config, so we store the values from
    // the most recent translate() call on the instance (same pattern as
    // the Python original where config is stored on self).

    private var _apiKey: String = ""
    private var _apiBase: String = "https://api-free.deepl.com/v2"

    override suspend fun translate(
        texts: List<String>,
        fromLanguage: String,
        toLanguage: String,
        config: TranslatorConfig,
    ): List<String> {
        // Store config for _translate()
        _apiKey = config.apiKey ?: ""
        _apiBase = config.apiBase?.trimEnd('/') ?: "https://api-free.deepl.com/v2"

        // DeepL-specific: unsupported target language → return original
        if (_LANGUAGE_CODE_MAP[toLanguage] == null) {
            Log.w(name, "Unsupported target language: $toLanguage")
            return texts
        }

        return super.translate(texts, fromLanguage, toLanguage, config)
    }

    override suspend fun _translate(
        fromLang: String,
        toLang: String,
        queries: List<String>,
    ): List<String> {
        return try {
            val endpoint = "${_apiBase}/translate"

            val request = DeeplTranslateRequest(
                text = queries,
                targetLang = toLang,
                sourceLang = fromLang.takeIf { it != "auto" },
            )

            val response: DeeplTranslateResponse = RateLimitRetry.retryWithBackoff(TAG) {
                httpClient.post(endpoint) {
                    header(HttpHeaders.Authorization, "DeepL-Auth-Key $_apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }.body()
            }

            response.translations.map { it.text }
        } catch (e: Exception) {
            Log.e(TAG, "Translation API error: ${e.message}", e)
            queries // Graceful degradation - return original text
        }
    }

    override fun supportsLanguagePair(from: String, to: String): Boolean {
        val sourceOk = _LANGUAGE_CODE_MAP.containsKey(from)
        val targetOk = _LANGUAGE_CODE_MAP.containsKey(to)
        return sourceOk || targetOk
    }

    companion object {
        private const val TAG = "DeeplTranslator"
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
