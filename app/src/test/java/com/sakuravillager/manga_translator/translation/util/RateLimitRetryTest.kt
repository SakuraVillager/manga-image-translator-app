package com.sakuravillager.manga_translator.translation.util

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RateLimitRetryTest {

    /**
     * Creates an [HttpClient] that throws [ClientRequestException]
     * for any 4xx HTTP response status.
     */
    private fun validatingClient(engine: MockEngine): HttpClient {
        return HttpClient(engine) {
            install(HttpCallValidator) {
                validateResponse { response ->
                    if (response.status.value >= 400) {
                        throw ClientRequestException(
                            response,
                            "HTTP ${response.status.value}: ${response.status.description}",
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `first attempt succeeds returns value and calls block once`() = runTest {
        var callCount = 0

        val result = RateLimitRetry.retryWithBackoff(tag = "test") {
            callCount++
            "success"
        }

        assertEquals("success", result)
        assertEquals(1, callCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `429 with RetryAfter retries then succeeds`() = runTest {
        var engineCallCount = 0
        val engine = MockEngine { _ ->
            engineCallCount++
            when {
                engineCallCount <= 3 -> respond(
                    content = "Too Many Requests",
                    status = HttpStatusCode(429, "Too Many Requests"),
                    headers = headersOf(HttpHeaders.RetryAfter, "2"),
                )
                else -> respond("OK")
            }
        }
        val client = validatingClient(engine)
        var blockCallCount = 0

        val result = RateLimitRetry.retryWithBackoff(tag = "test") {
            blockCallCount++
            client.get("http://test.com")
            "success"
        }

        assertEquals("success", result)
        assertEquals(4, blockCallCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `non-429 exception uses exponential backoff and jitter`() = runTest {
        var callCount = 0
        val error = RuntimeException("API error")

        val caught = try {
            RateLimitRetry.retryWithBackoff(
                tag = "test",
                maxRetries = 2,
                baseDelayMs = 1000L,
            ) {
                callCount++
                throw error
            }
            null
        } catch (e: RuntimeException) {
            e
        }

        assertNotNull("Expected RuntimeException to be thrown", caught)
        assertEquals("API error", caught!!.message)
        assertEquals(3, callCount)

        assertTrue("Virtual time should be >= 3000ms, got $currentTime", currentTime >= 3000L)
        assertTrue("Virtual time should be <= 5000ms, got $currentTime", currentTime <= 5000L)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `all attempts exhausted throws last exception`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = "Too Many Requests",
                status = HttpStatusCode(429, "Too Many Requests"),
                headers = headersOf(HttpHeaders.RetryAfter, "2"),
            )
        }
        val client = validatingClient(engine)
        var callCount = 0

        val caught = try {
            RateLimitRetry.retryWithBackoff(tag = "test", maxRetries = 2) {
                callCount++
                client.get("http://test.com")
                "unreachable"
            }
            null
        } catch (e: ClientRequestException) {
            e
        }

        assertNotNull("Expected ClientRequestException to be thrown", caught)
        assertEquals(429, caught!!.response.status.value)
        assertEquals(3, callCount)
    }
}
