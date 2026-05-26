package com.sakuravillager.manga_translator.translation.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for [Model48pxBeamRecognizer].
 *
 * Uses the "NoOp TextRecognizer" pattern — tests verify interface
 * contract and structural behaviour without real ONNX models.
 *
 * `Context` is passed as `null` since the constructor accepts `Context?`
 * and only dereferences it in `prepare()` (not tested here).
 *
 * Note: `recognize(bitmap, textlines, config)` cannot be called directly
 * from JVM tests because Kotlin 2.0+ enforces non-null Bitmap parameter
 * at the call site and `Bitmap.createBitmap()` throws "Stub!" in plain
 * JVM tests.  The `callRecognizeViaReflection` helper uses Java
 * reflection to bypass the null check — safe because the early-return
 * path never dereferences the bitmap.
 *
 * Pipeline-level correctness is verified in separate component tests:
 * [BeamSearchDecoderTest], [ColorExtractorTest], [ArDictionaryTest].
 */
class Model48pxBeamRecognizerTest {

    // ── normalizePixel (companion object, pure Kotlin math) ───────────

    @Test
    fun `normalizePixel maps black to negative one`() {
        val (r, g, b) = Model48pxBeamRecognizer.normalizePixel(0xFF000000.toInt())
        assertEquals(-1f, r, 0.0001f)
        assertEquals(-1f, g, 0.0001f)
        assertEquals(-1f, b, 0.0001f)
    }

    @Test
    fun `normalizePixel maps white to positive one`() {
        val (r, g, b) = Model48pxBeamRecognizer.normalizePixel(0xFFFFFFFF.toInt())
        assertEquals(1f, r, 0.0001f)
        assertEquals(1f, g, 0.0001f)
        assertEquals(1f, b, 0.0001f)
    }

    @Test
    fun `normalizePixel maps mid gray near zero`() {
        val (r, g, b) = Model48pxBeamRecognizer.normalizePixel(0xFF808080.toInt())
        assertEquals(0.0039216f, r, 0.0001f)
        assertEquals(0.0039216f, g, 0.0001f)
        assertEquals(0.0039216f, b, 0.0001f)
    }

    // ── name property ─────────────────────────────────────────────────

    @Test
    fun `testNameProperty returns Model48pxBeamRecognizer`() {
        val recognizer = Model48pxBeamRecognizer(null)
        assertEquals("Model48pxBeamRecognizer", recognizer.name)
    }

    // ── isReady property ───────────────────────────────────────────────

    @Test
    fun `testIsReadyInitiallyFalse`() {
        val recognizer = Model48pxBeamRecognizer(null)
        assertFalse("isReady should be false before prepare()", recognizer.isReady)
    }

    @Test
    fun `testIsReadyFalseAfterRelease`() = runBlocking {
        val recognizer = Model48pxBeamRecognizer(null)
        recognizer.release()
        assertFalse("isReady should remain false after release", recognizer.isReady)
    }

    // ── recognize early-return path (models not loaded) ───────────────

    @Test
    fun `testEmptyTextlines returns empty list`() = runBlocking {
        val recognizer = Model48pxBeamRecognizer(null)
        val result = callRecognizeViaReflection(recognizer, emptyList())
        assertTrue("Empty textlines should return empty list", result.isEmpty())
    }

    @Test
    fun `testSingleRegion returns region unchanged when models not loaded`() = runBlocking {
        val recognizer = Model48pxBeamRecognizer(null)
        val quad = createTestQuad()
        val result = callRecognizeViaReflection(recognizer, listOf(quad))
        assertEquals(1, result.size)
        assertEquals("", result[0].text)
        assertEquals(0f, result[0].probability, 0.0001f)
    }

    @Test
    fun `testMultipleRegions returns all regions unchanged`() = runBlocking {
        val recognizer = Model48pxBeamRecognizer(null)
        val quads = listOf(createTestQuad(0f, 0f), createTestQuad(60f, 0f))
        val result = callRecognizeViaReflection(recognizer, quads)
        assertEquals(2, result.size)
        result.forEachIndexed { i, q ->
            assertEquals("Region $i text should be empty", "", q.text)
            assertEquals("Region $i probability should be 0", 0f, q.probability, 0.0001f)
        }
    }

    @Test
    fun `testSourceIndexNullWhenModelsNotLoaded`() = runBlocking {
        val recognizer = Model48pxBeamRecognizer(null)
        val quad = createTestQuad()
        // In the early-return path (encoderSession == null), recognize()
        // returns the original textlines without modification, so sourceIndex
        // is null (not set).
        val result = callRecognizeViaReflection(recognizer, listOf(quad))
        assertEquals(null, result[0].sourceIndex)
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun createTestQuad(left: Float = 10f, top: Float = 10f) =
        com.sakuravillager.manga_translator.translation.data.Quadrilateral(
            points = listOf(
                android.graphics.PointF(left, top),
                android.graphics.PointF(left + 90f, top),
                android.graphics.PointF(left + 90f, top + 50f),
                android.graphics.PointF(left, top + 50f),
            ),
        )
}

// ── Reflection bridge ─────────────────────────────────────────────────

/**
 * Calls [Model48pxBeamRecognizer.recognize] with a null bitmap by
 * using Java reflection to bypass Kotlin 2.0+'s non-null enforcement.
 *
 * Safe because the early-return path ([encoderSession] == null) executes
 * before the bitmap is ever dereferenced.
 */
private suspend fun callRecognizeViaReflection(
    recognizer: Model48pxBeamRecognizer,
    textlines: List<com.sakuravillager.manga_translator.translation.data.Quadrilateral>,
): List<com.sakuravillager.manga_translator.translation.data.Quadrilateral> {
    val config = com.sakuravillager.manga_translator.translation.data.config.OcrConfig()
    val method = Model48pxBeamRecognizer::class.java.getDeclaredMethod(
        "recognize",
        android.graphics.Bitmap::class.java,
        List::class.java,
        com.sakuravillager.manga_translator.translation.data.config.OcrConfig::class.java,
        kotlin.coroutines.Continuation::class.java,
    )
    method.isAccessible = true

    @Suppress("UNCHECKED_CAST")
    val result = method.invoke(
        recognizer,
        null,   // bitmap — null is safe for the early-return path
        textlines,
        config,
        object : kotlin.coroutines.Continuation<List<com.sakuravillager.manga_translator.translation.data.Quadrilateral>> {
            override val context: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: kotlin.Result<List<com.sakuravillager.manga_translator.translation.data.Quadrilateral>>) {
                // Not called for synchronous early-return
            }
        },
    )
    return result as List<com.sakuravillager.manga_translator.translation.data.Quadrilateral>
}
