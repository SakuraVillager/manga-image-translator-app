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

class GptTranslatorRateLimitTest {

    private fun createMockClient(): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = """{"choices":[{"message":{"role":"assistant","content":"<|1|>Hello\n<|2|>World"}}]}""",
                        status = HttpStatusCode.OK,
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
    fun `translate with rate limiting succeeds on first call`() = runTest {
        val translator = GptTranslator(createMockClient())
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
    fun `consecutive translate calls both succeed with rate limiting`() = runTest {
        val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val count = requestCount.incrementAndGet()
                    val responseContent = if (count == 1) {
                        """{"choices":[{"message":{"role":"assistant","content":"<|1|>Hello"}}]}"""
                    } else {
                        """{"choices":[{"message":{"role":"assistant","content":"<|1|>World"}}]}"""
                    }
                    respond(
                        content = responseContent,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val translator = GptTranslator(client)
        val firstResult = translator.translate(
            listOf("こんにちは"),
            "JPN",
            "ENG",
            TranslatorConfig(),
        )
        assertEquals(listOf("Hello"), firstResult)

        val secondResult = translator.translate(
            listOf("世界"),
            "JPN",
            "ENG",
            TranslatorConfig(),
        )
        assertEquals(listOf("World"), secondResult)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `rate limiting does not affect error fallback`() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = "Unauthorized",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        val translator = GptTranslator(client)
        val input = listOf("こんにちは", "世界")
        val result = translator.translate(input, "JPN", "ENG", TranslatorConfig())
        assertEquals(input, result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `empty input bypasses rate limiting and returns immediately`() = runTest {
        val translator = GptTranslator(createMockClient())
        val result = translator.translate(emptyList(), "JPN", "ENG", TranslatorConfig())
        assertTrue(result.isEmpty())
    }
}
