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
import java.io.File

class GptTranslatorGlossaryTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun createMockClient(): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    // Read the request body to verify glossary was injected
                    val body = request.body.toByteArray().decodeToString()
                    respond(
                        content = """{"choices":[{"message":{"role":"assistant","content":"<|1|>Hello"}}]}""",
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

    private fun createTempGlossary(content: String): File {
        val file = File.createTempFile("glossary", ".txt")
        file.writeText(content)
        file.deleteOnExit()
        return file
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate injects glossary terms into system message`() = runTest {
        val glossaryFile = createTempGlossary("""
            こんにちは Hello
            世界 World
        """.trimIndent())

        var capturedBody: String? = null
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedBody = request.body.toByteArray().decodeToString()
                    respond(
                        content = """{"choices":[{"message":{"role":"assistant","content":"<|1|>Hello"}}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        val translator = GptTranslator(client, glossaryPath = glossaryFile.absolutePath)
        val result = translator.translate(
            listOf("こんにちは"),
            "JPN",
            "ENG",
            TranslatorConfig(),
        )

        assertEquals(listOf("Hello"), result)
        assertTrue(capturedBody != null)
        assertTrue("Glossary should be in system message", capturedBody!!.contains("こんにちは → Hello"))
        assertTrue("Request should contain glossary text", capturedBody!!.contains("Please translate based on the following glossary"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate works without glossaryPath`() = runTest {
        val translator = GptTranslator(createMockClient())
        val result = translator.translate(
            listOf("こんにちは"),
            "JPN",
            "ENG",
            TranslatorConfig(),
        )
        assertEquals(listOf("Hello"), result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate works with empty glossary file`() = runTest {
        val glossaryFile = createTempGlossary("")
        val translator = GptTranslator(createMockClient(), glossaryPath = glossaryFile.absolutePath)
        val result = translator.translate(
            listOf("こんにちは"),
            "JPN",
            "ENG",
            TranslatorConfig(),
        )
        assertEquals(listOf("Hello"), result)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `translate works when no glossary terms match`() = runTest {
        val glossaryFile = createTempGlossary("""
            猫 cat
            犬 dog
        """.trimIndent())
        val translator = GptTranslator(createMockClient(), glossaryPath = glossaryFile.absolutePath)
        val result = translator.translate(
            listOf("こんにちは"),
            "JPN",
            "ENG",
            TranslatorConfig(),
        )
        assertEquals(listOf("Hello"), result)
    }
}
