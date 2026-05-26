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
import org.junit.Assert.assertTrue
import org.junit.Test

class GptTranslatorTest {

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
    fun `translate returns correct lines on success`() = runTest {
        val responseJson =
            """{"choices":[{"message":{"role":"assistant","content":"<|1|>Hello\n<|2|>World"}}]}"""
        val client = createMockClient(responseJson)
        val translator = GptTranslator(client)
        val result = translator.translate(
            listOf("こんにちは", "世界"),
            "auto",
            "CHS",
            TranslatorConfig(),
        )
        assertEquals(listOf("Hello", "World"), result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate returns original on API error`() = runTest {
        val client = createMockClient("Unauthorized", HttpStatusCode.Unauthorized)
        val translator = GptTranslator(client)
        val input = listOf("こんにちは", "世界")
        val result = translator.translate(input, "auto", "CHS", TranslatorConfig())
        assertEquals(input, result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate returns empty for empty input`() = runTest {
        val client = createMockClient("{}")
        val translator = GptTranslator(client)
        val result = translator.translate(emptyList(), "auto", "CHS", TranslatorConfig())
        assertTrue(result.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate falls back on line count mismatch`() = runTest {
        val responseJson =
            """{"choices":[{"message":{"role":"assistant","content":"<|1|>Only one"}}]}"""
        val client = createMockClient(responseJson)
        val translator = GptTranslator(client)
        val input = listOf("a", "b")
        val result = translator.translate(input, "auto", "CHS", TranslatorConfig())
        assertEquals(listOf("Only one", ""), result)
    }
}
