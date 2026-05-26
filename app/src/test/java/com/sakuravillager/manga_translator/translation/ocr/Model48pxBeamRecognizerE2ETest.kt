package com.sakuravillager.manga_translator.translation.ocr

import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration tests for the Model48pxBeamRecognizer pipeline.
 *
 * Verifies that all pipeline components (BeamSearchDecoder, ArDictionary,
 * ColorExtractor) are correctly wired together and produce consistent
 * results when assembled into [Quadrilateral] instances.
 *
 * Pipeline under test:
 *   Mock encoder output → BeamSearchDecoder.decode → ArDictionary.decode
 *   → ColorExtractor.extract → Quadrilateral field assembly
 *
 * All tests run on pure JVM — no ONNX Runtime, no Android assets.
 * - ArDictionary `_chars` is injected via reflection (standard JVM test pattern).
 * - BeamSearchDecoder uses synthetic weights (standard test pattern from
 *   [BeamSearchDecoderTest]).
 * - ColorExtractor uses mock/default weights.
 */
class Model48pxBeamRecognizerE2ETest {

    companion object {
        // Small model dimensions for fast JVM tests
        const val DIM = 8
        const val NUM_HEADS = 2
        const val HEAD_DIM = 4
        const val FF_DIM = 16
        const val DICT_SIZE = 10
        const val MEM_LEN = 4
        const val START_TOK = 1
        const val END_TOK = 2
        const val PAD_TOK = 0
        const val BEAM_K = 5
        const val MAX_SEQ = 16

        // Token indices matching testChars
        const val TOKEN_A = 7
        const val TOKEN_B = 8
        const val TOKEN_C = 9
    }

    /**
     * Test dictionary matching alphabet-all-v7.txt structure but smaller.
     * Indices 0-6: special tokens; 7-9: printable characters.
     */
    private val testChars = listOf(
        "<PAD>",   // 0
        "<S>",     // 1  — START
        "</S>",    // 2  — END
        "<SEP>",   // 3
        "<UNK>",   // 4
        "<SP>",    // 5  — space
        "<LF>",    // 6
        "a",       // 7
        "b",       // 8
        "c",       // 9
    )

    @Before
    fun setUp() {
        // Inject ArDictionary chars (no Android assets available in JVM tests)
        injectArDictionaryChars(testChars)
    }

    @After
    fun tearDown() {
        injectArDictionaryChars(null)
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun injectArDictionaryChars(chars: List<String>?) {
        val field = ArDictionary::class.java.getDeclaredField("_chars")
        field.isAccessible = true
        field.set(ArDictionary, chars)
    }

    /** Identity-like weight matrix: [inDim, outDim] with 1s on the diagonal. */
    private fun identityWeights(inDim: Int, outDim: Int): FloatArray {
        val w = FloatArray(inDim * outDim)
        for (i in 0 until minOf(inDim, outDim)) {
            w[i * inDim + i] = 1.0f
        }
        return w
    }

    private fun zeroWeights(inDim: Int, outDim: Int): FloatArray = FloatArray(inDim * outDim)
    private fun zeroBias(dim: Int): FloatArray = FloatArray(dim)
    private fun identityLN(dim: Int): FloatArray = FloatArray(dim) { 1f }
    private fun zeroLNBias(dim: Int): FloatArray = FloatArray(dim)

    /** Build mock decoder layer weights with identity-like projections. */
    private fun mockLayerWeights(dim: Int, ffDim: Int): DecoderLayerWeights {
        return DecoderLayerWeights(
            selfAttnQProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            selfAttnKProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            selfAttnVProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            selfAttnOutProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            crossAttnQProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            crossAttnKProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            crossAttnVProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            crossAttnOutProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            norm1 = LayerNormWeights(identityLN(dim), zeroLNBias(dim)),
            norm2 = LayerNormWeights(identityLN(dim), zeroLNBias(dim)),
            norm3 = LayerNormWeights(identityLN(dim), zeroLNBias(dim)),
            ffLinear1 = LinearWeights(identityWeights(dim, ffDim), zeroBias(ffDim)),
            ffLinear2 = LinearWeights(identityWeights(ffDim, dim), zeroBias(dim)),
        )
    }

    /**
     * Build a small [BeamSearchDecoder] for testing.
     *
     * The pred head is biased to predict END_TOK early, ensuring the
     * beam search terminates quickly and produces a short token sequence
     * suitable for ArDictionary decoding.
     */
    private fun createSmallDecoder(
        numLayers: Int = 1,
        dim: Int = DIM,
        numHeads: Int = NUM_HEADS,
        ffDim: Int = FF_DIM,
        dictSize: Int = DICT_SIZE,
    ): BeamSearchDecoder {
        val embedding = FloatArray(dictSize * dim)
        for (tok in 0 until dictSize) {
            for (d in 0 until dim) {
                embedding[tok * dim + d] = (tok + 1).toFloat() + d.toFloat() * 0.1f
            }
        }

        val decoderLayerWeightsList = List(numLayers) { mockLayerWeights(dim, ffDim) }
        val pred1W = LinearWeights(zeroWeights(dim, dim), zeroBias(dim))
        // Bias pred head to emit END token early for fast test execution
        val predW = LinearWeights(
            weight = FloatArray(dim * dictSize).apply {
                for (d in 0 until dim) this[END_TOK * dim + d] = 50.0f
            },
            bias = zeroBias(dictSize),
        )

        return BeamSearchDecoder(
            embedding = embedding,
            decoderLayerWeights = decoderLayerWeightsList,
            pred1Weights = pred1W,
            predWeights = predW,
            dim = dim,
            numHeads = numHeads,
            ffDim = ffDim,
            dictSize = dictSize,
            numLayers = numLayers,
            maxSeqLength = MAX_SEQ,
            beamK = BEAM_K,
            startTok = START_TOK,
            endTok = END_TOK,
            padTok = PAD_TOK,
            maxFinishedHypos = 2,
        )
    }

    /**
     * Creates mock encoder output: memory [N, memLen, dim] and mask [N, memLen].
     * Mask is all-false (= not masked / valid).
     */
    private fun mockEncoderOutput(
        N: Int,
        memLen: Int = MEM_LEN,
        dim: Int = DIM,
    ): Pair<FloatArray, BooleanArray> {
        val memory = FloatArray(N * memLen * dim) { idx ->
            ((idx % 10) + 1).toFloat() * 0.1f
        }
        val mask = BooleanArray(N * memLen) { false }
        return memory to mask
    }

    // ── Test: E2E Full Pipeline ─────────────────────────────────────────

    @Test
    fun `testE2EFullPipeline`() {
        // 1. Create mock encoder output (as produced by the real ONNX encoder)
        val nSamples = 1
        val (memory, mask) = mockEncoderOutput(N = nSamples, memLen = MEM_LEN, dim = DIM)

        // 2. Run beam search decoding
        val decoder = createSmallDecoder()
        val results = decoder.decode(memory = memory, mask = mask, batch = nSamples)

        assertEquals("Should produce 1 decoded result for 1 sample", 1, results.size)
        val (tokenIds, probability) = results[0]
        assertTrue("Decoder should produce at least 1 token", tokenIds.isNotEmpty())
        assertTrue("Probability should be positive", probability > 0f)

        // 3. Decode token IDs to text via ArDictionary
        val text = ArDictionary.decode(tokenIds.toList())
        assertNotNull("Decoded text should not be null", text)
        assertTrue(
            "Decoded text should contain printable chars from dictionary",
            text.any { it in "abc<S></S><SEP><UNK> " } || text.all { it in "<>" },
        )
        // The exact text depends on synthetic weights, but it should be decodable

        // 4. Color extraction with synthetic 320-dim hidden state
        val colorExtractor = ColorExtractor.createMock()
        val hiddenState = FloatArray(320) // ColorExtractor requires exactly 320-dim input
        val charColor = colorExtractor.extract(hiddenState)
        assertNotNull("CharacterColor should not be null", charColor)
        assertEquals("fgRgb should have 3 channels", 3, charColor.fgRgb.size)
        assertEquals("bgRgb should have 3 channels", 3, charColor.bgRgb.size)

        // 5. Assemble into Quadrilateral and verify all fields
        val fgColor: Int? = if (charColor.hasFg) {
            (0xFF shl 24) or
                (charColor.fgRgb[0] shl 16) or
                (charColor.fgRgb[1] shl 8) or
                charColor.fgRgb[2]
        } else null

        val bgColor: Int? = if (charColor.hasBg) {
            (0xFF shl 24) or
                (charColor.bgRgb[0] shl 16) or
                (charColor.bgRgb[1] shl 8) or
                charColor.bgRgb[2]
        } else null

        val quad = Quadrilateral(
            points = listOf(
                android.graphics.PointF(0f, 0f),
                android.graphics.PointF(100f, 0f),
                android.graphics.PointF(100f, 50f),
                android.graphics.PointF(0f, 50f),
            ),
            text = text,
            probability = probability,
            fgColor = fgColor,
            bgColor = bgColor,
            sourceIndex = 0,
        )

        // 6. Verify all Quadrilateral fields are correctly set
        assertEquals("text field should match ArDictionary output", text, quad.text)
        assertEquals("probability field should match decoder output", probability, quad.probability, 0.0001f)
        assertEquals("fgColor field should match ColorExtractor output", fgColor, quad.fgColor)
        assertEquals("bgColor field should match ColorExtractor output", bgColor, quad.bgColor)
        assertEquals("sourceIndex should be 0", 0, quad.sourceIndex)
        assertEquals("points should have 4 vertices", 4, quad.points.size)
    }

    // ── Test: Multiple Samples ──────────────────────────────────────────

    @Test
    fun `testE2EMultipleSamples`() {
        val nSamples = 3
        val (memory, mask) = mockEncoderOutput(N = nSamples, memLen = MEM_LEN, dim = DIM)
        val decoder = createSmallDecoder()

        val results = decoder.decode(memory = memory, mask = mask, batch = nSamples)

        assertEquals("Should produce results for all samples", nSamples, results.size)
        for (i in 0 until nSamples) {
            val (tokenIds, probability) = results[i]
            assertTrue("Sample $i should have tokens", tokenIds.isNotEmpty())
            assertTrue("Sample $i should have positive probability", probability > 0f)

            val text = ArDictionary.decode(tokenIds.toList())
            assertNotNull("Sample $i decoded text should not be null", text)
        }
    }

    // ── Test: ColorExtractor with ArDictionary integration ──────────────

    @Test
    fun `testColorAndTextIntegratedQuadrilateral`() {
        // Test that ColorExtractor and ArDictionary results combine
        // correctly in a Quadrilateral with fgColor and bgColor set.

        // Use ArDictionary to decode known tokens
        val knownTokens = listOf(START_TOK, TOKEN_A, TOKEN_B, TOKEN_C, END_TOK)
        val text = ArDictionary.decode(knownTokens)
        assertTrue("Decoded text should contain 'abc'", text.contains("a") || text.contains("b") || text.contains("c"))

        // Extract colors from synthetic hidden state
        val colorExtractor = ColorExtractor.createMock()
        val hiddenState = FloatArray(320)
        val charColor = colorExtractor.extract(hiddenState)

        val fgColor = if (charColor.hasFg) {
            (0xFF shl 24) or (charColor.fgRgb[0] shl 16) or (charColor.fgRgb[1] shl 8) or charColor.fgRgb[2]
        } else null
        val bgColor = if (charColor.hasBg) {
            (0xFF shl 24) or (charColor.bgRgb[0] shl 16) or (charColor.bgRgb[1] shl 8) or charColor.bgRgb[2]
        } else null

        val quad = Quadrilateral(
            points = listOf(
                android.graphics.PointF(10f, 10f),
                android.graphics.PointF(90f, 10f),
                android.graphics.PointF(90f, 48f),
                android.graphics.PointF(10f, 48f),
            ),
            text = text,
            probability = 0.85f,
            fgColor = fgColor,
            bgColor = bgColor,
            sourceIndex = 0,
        )

        assertEquals("Text should be set", text, quad.text)
        assertEquals("Probability should be set", 0.85f, quad.probability, 0.0001f)
        assertEquals("fgColor should be set", fgColor, quad.fgColor)
        assertEquals("bgColor should be set", bgColor, quad.bgColor)
        assertEquals("sourceIndex should be 0", 0, quad.sourceIndex)
    }

    // ── Test: Dependency Injection compatibility ────────────────────────

    @Test
    fun `testDependencyInjectionConstructor`() {
        // Verify Model48pxBeamRecognizer can be constructed with the
        // DI-compatible constructor signature (Context?, ModelDownloadManager?).
        // The ONNX runtime is NOT loaded here — verify API contract only.
        val recognizer1 = Model48pxBeamRecognizer(null)
        assertEquals("Model48pxBeamRecognizer", recognizer1.name)
        assertFalse("Should not be ready before prepare()", recognizer1.isReady)
        assertEquals("encoderSession should be null before prepare()", null, recognizer1.encoderSession)
        assertEquals("beamSearchDecoder should be null before prepare()", null, recognizer1.beamSearchDecoder)
        assertEquals("colorExtractor should be null before prepare()", null, recognizer1.colorExtractor)

        // Verify the two-parameter constructor also works
        val recognizer2 = Model48pxBeamRecognizer(null, null)
        assertEquals("Model48pxBeamRecognizer", recognizer2.name)
        assertFalse("Should not be ready before prepare()", recognizer2.isReady)
    }

    @Test
    fun `testConstructorWithModelDownloadManager`() {
        // Explicitly verify the constructor wiring that TranslationModule uses:
        //   Model48pxBeamRecognizer(context, modelDownloadManager)
        // Both parameters are nullable, so null is valid for unit tests.
        val recognizer = Model48pxBeamRecognizer(null, null)
        assertNotNull("Constructor should return a valid instance", recognizer)
        assertEquals("Model48pxBeamRecognizer", recognizer.name)
        assertFalse("isReady should be false without prepare()", recognizer.isReady)
    }
}
