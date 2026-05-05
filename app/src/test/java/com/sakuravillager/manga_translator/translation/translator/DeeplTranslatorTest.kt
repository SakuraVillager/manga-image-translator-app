package com.sakuravillager.manga_translator.translation.translator

import com.sakuravillager.manga_translator.translation.data.config.TranslatorConfig
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeeplTranslatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createMockClient(
        responseBody: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = responseBody,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate returns correct translations on success`() = runTest {
        val responseJson = """
            {
                "translations": [
                    {"detected_source_language": "JA", "text": "Hello"},
                    {"detected_source_language": "JA", "text": "World"}
                ]
            }
        """.trimIndent()
        val client = createMockClient(responseJson)
        val translator = DeeplTranslator(client)
        val result = translator.translate(
            listOf("こんにちは", "世界"),
            "JPN",
            "ENG",
            TranslatorConfig(),
        )
        assertEquals(listOf("Hello", "World"), result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate returns original on API error`() = runTest {
        val client = createMockClient("Unauthorized", HttpStatusCode.Unauthorized)
        val translator = DeeplTranslator(client)
        val input = listOf("こんにちは", "世界")
        val result = translator.translate(input, "JPN", "ENG", TranslatorConfig())
        assertEquals(input, result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate returns empty for empty input`() = runTest {
        val client = createMockClient("{}")
        val translator = DeeplTranslator(client)
        val result = translator.translate(emptyList(), "JPN", "ENG", TranslatorConfig())
        assertTrue(result.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate returns original for unsupported target language`() = runTest {
        val client = createMockClient("{}")
        val translator = DeeplTranslator(client)
        val input = listOf("hello", "world")
        val result = translator.translate(input, "JPN", "ARA", TranslatorConfig())
        assertEquals(input, result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate handles blank texts without API call`() = runTest {
        val client = createMockClient("{}")
        val translator = DeeplTranslator(client)
        val input = listOf("", "  ", "")
        val result = translator.translate(input, "JPN", "ENG", TranslatorConfig())
        assertEquals(input, result)
    }

    @Test
    fun `supportsLanguagePair returns true for supported pair`() {
        val translator = DeeplTranslator(createMockClient("{}"))
        assertTrue(translator.supportsLanguagePair("JPN", "ENG"))
        assertTrue(translator.supportsLanguagePair("CHS", "ENG"))
        assertTrue(translator.supportsLanguagePair("JPN", "CHS"))
    }

    @Test
    fun `supportsLanguagePair returns false when both languages are unsupported`() {
        val translator = DeeplTranslator(createMockClient("{}"))
        assertFalse(translator.supportsLanguagePair("ARA", "VIN"))
    }

    @Test
    fun `supportsLanguagePair returns true when at least one language is supported`() {
        val translator = DeeplTranslator(createMockClient("{}"))
        assertTrue(translator.supportsLanguagePair("JPN", "ARA"))
        assertTrue(translator.supportsLanguagePair("ARA", "ENG"))
    }

    @Test
    fun `language code mapping covers expected codes`() {
        val translator = DeeplTranslator(createMockClient("{}"))
        assertTrue(translator.supportedSourceLanguages.contains("CHS"))
        assertTrue(translator.supportedSourceLanguages.contains("CHT"))
        assertTrue(translator.supportedSourceLanguages.contains("ENG"))
        assertTrue(translator.supportedSourceLanguages.contains("JPN"))
        assertTrue(translator.supportedSourceLanguages.contains("KOR"))
        assertTrue(translator.supportedSourceLanguages.contains("FRA"))
        assertTrue(translator.supportedSourceLanguages.contains("DEU"))
        assertTrue(translator.supportedSourceLanguages.contains("ESP"))
        assertTrue(translator.supportedSourceLanguages.contains("ITA"))
        assertTrue(translator.supportedSourceLanguages.contains("NLD"))
        assertTrue(translator.supportedSourceLanguages.contains("PLK"))
        assertTrue(translator.supportedSourceLanguages.contains("PTB"))
        assertTrue(translator.supportedSourceLanguages.contains("RUS"))
        assertEquals(translator.supportedSourceLanguages, translator.supportedTargetLanguages)
    }

    @Test
    fun `name is DeepL`() {
        val translator = DeeplTranslator(createMockClient("{}"))
        assertEquals("DeepL", translator.name)
    }
}
