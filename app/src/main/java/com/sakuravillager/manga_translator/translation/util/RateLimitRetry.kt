package com.sakuravillager.manga_translator.translation.util

import android.util.Log
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlin.random.Random

object RateLimitRetry {
    suspend fun <T> retryWithBackoff(
        tag: String,
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
                    val jitter = if (e is ClientRequestException && e.response.status.value == 429) {
                        0L // 429 uses Retry-After header value
                    } else {
                        (delayMs * (Random.nextFloat() * 0.4f + 0.1f)).toLong()
                    }
                    Log.w(
                        tag,
                        "Attempt ${attempt + 1}/${maxRetries + 1} failed: ${e.message}. " +
                            "Retrying with delay ${delayMs + jitter}ms...",
                    )
                    delay(delayMs + jitter)
                }
            }
        }
        throw lastException!!
    }
}
