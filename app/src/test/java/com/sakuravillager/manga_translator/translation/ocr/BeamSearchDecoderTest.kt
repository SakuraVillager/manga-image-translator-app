package com.sakuravillager.manga_translator.translation.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * TDD tests for BeamSearchDecoder: decoder layers + beam search loop.
 *
 * Reference: model_48px.py:678-801 (infer_beam_batch_tensor)
 * All tests use manual stubs / mock weights, no MockK.
 */
class BeamSearchDecoderTest {

    // ── Constants ─────────────────────────────────────────────────────
    companion object {
        const val SMALL_DIM = 8
        const val SMALL_NUM_HEADS = 2
        const val SMALL_HEAD_DIM = 4
        const val SMALL_FF_DIM = 16
        const val SMALL_DICT_SIZE = 10
        const val SMALL_MAX_SEQ = 16
        const val BEAM_K = 5
        const val START_TOK = 1
        const val END_TOK = 2
        const val PAD_TOK = 0
    }

    // ── Helper: build identity-like weights ────────────────────────────

    /** Create identity-like linear weights: [outDim, inDim] with identity matrix. */
    private fun identityWeights(inDim: Int, outDim: Int): FloatArray {
        val w = FloatArray(inDim * outDim)
        for (i in 0 until minOf(inDim, outDim)) {
            w[i * inDim + i] = 1.0f
        }
        return w
    }

    /** Create linear weights + bias = all zeros. */
    private fun zeroWeights(inDim: Int, outDim: Int): FloatArray = FloatArray(inDim * outDim)

    private fun zeroBias(dim: Int): FloatArray = FloatArray(dim)

    /** LayerNorm weights (all 1s) + bias (all 0s). */
    private fun identityLNWeight(dim: Int): FloatArray = FloatArray(dim) { 1f }
    private fun zeroLNBias(dim: Int): FloatArray = FloatArray(dim)

    private fun mockDecoderLayerWeights(dim: Int, ffDim: Int): DecoderLayerWeights {
        return DecoderLayerWeights(
            selfAttnQProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            selfAttnKProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            selfAttnVProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            selfAttnOutProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            crossAttnQProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            crossAttnKProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            crossAttnVProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            crossAttnOutProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            norm1 = LayerNormWeights(identityLNWeight(dim), zeroLNBias(dim)),
            norm2 = LayerNormWeights(identityLNWeight(dim), zeroLNBias(dim)),
            norm3 = LayerNormWeights(identityLNWeight(dim), zeroLNBias(dim)),
            ffLinear1 = LinearWeights(identityWeights(dim, ffDim), zeroBias(ffDim)),
            ffLinear2 = LinearWeights(identityWeights(ffDim, dim), zeroBias(dim))
        )
    }

    /** Build a small decoder for testing. */
    private fun createSmallDecoder(
        numLayers: Int = 1,
        dim: Int = SMALL_DIM,
        numHeads: Int = SMALL_NUM_HEADS,
        ffDim: Int = SMALL_FF_DIM,
        dictSize: Int = SMALL_DICT_SIZE
    ): BeamSearchDecoder {
        val embedding = FloatArray(dictSize * dim)
        // Fill embedding with simple patterns per token
        for (tok in 0 until dictSize) {
            for (d in 0 until dim) {
                embedding[tok * dim + d] = (tok + 1).toFloat() + d.toFloat() * 0.1f
            }
        }

        val decoderLayerWeightsList = List(numLayers) { mockDecoderLayerWeights(dim, ffDim) }
        val pred1W = LinearWeights(zeroWeights(dim, dim), zeroBias(dim))
        val predW = LinearWeights(zeroWeights(dim, dictSize), zeroBias(dictSize))

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
            maxSeqLength = SMALL_MAX_SEQ,
            beamK = BEAM_K,
            startTok = START_TOK,
            endTok = END_TOK,
            padTok = PAD_TOK,
            maxFinishedHypos = 2
        )
    }

    /** Create mock encoder output: memory [N, memLen, dim] and mask [N, memLen]. */
    private fun mockEncoderOutput(
        N: Int,
        memLen: Int = 4,
        dim: Int = SMALL_DIM
    ): Pair<FloatArray, BooleanArray> {
        val memory = FloatArray(N * memLen * dim) { idx ->
            ((idx % 10) + 1).toFloat() * 0.1f
        }
        val mask = BooleanArray(N * memLen) { false }
        return memory to mask
    }

    // ── Test 1: XposMultiheadAttention output shape ────────────────────

    @Test
    fun `testXposMHA produces correct output shape`() {
        val dim = SMALL_DIM
        val numHeads = SMALL_NUM_HEADS
        val headDim = SMALL_HEAD_DIM
        val batch = 1
        val tgtLen = 1
        val srcLen = 3

        val mha = XposMultiheadAttention(dim, numHeads)
        val query = FloatArray(batch * tgtLen * dim) { 0.5f }
        val key = FloatArray(batch * srcLen * dim) { 0.3f }
        val value = FloatArray(batch * srcLen * dim) { 0.3f }

        val qProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim))
        val kProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim))
        val vProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim))
        val outProj = LinearWeights(identityWeights(dim, dim), zeroBias(dim))

        val output = mha.forward(
            query = query, queryBatch = batch, queryLen = tgtLen,
            key = key, keyBatch = batch, keyLen = srcLen,
            value = value,
            qProj = qProj, kProj = kProj, vProj = vProj, outProj = outProj,
            qOffset = 1, kOffset = 0
        )

        assertEquals("Output should be [batch, tgtLen, dim] flattened",
            batch * tgtLen * dim, output.size)
    }

    // ── Test 2: TransformerDecoderLayer output shape ───────────────────

    @Test
    fun `testTransformerDecoderLayer produces correct shape`() {
        val dim = SMALL_DIM
        val numHeads = SMALL_NUM_HEADS
        val ffDim = SMALL_FF_DIM
        val batch = 1
        val memLen = 4
        val tgtCacheLen = 0  // no previous tokens

        val layer = TransformerDecoderLayer(dim, numHeads, ffDim)
        val weights = mockDecoderLayerWeights(dim, ffDim)
        val xpos = XposEncoder(headDim = dim / numHeads)

        val tgt = FloatArray(batch * 1 * dim) { 0.7f }
        val tgtCache = FloatArray(batch * tgtCacheLen * dim) // empty
        val (memory, _) = mockEncoderOutput(1, memLen, dim)

        val output = layer.forward(
            tgt = tgt,
            tgtCache = tgtCache,
            tgtCacheLen = tgtCacheLen,
            memory = memory,
            memoryMask = null,
            weights = weights,
            xpos = xpos,
            qOffset = 0,
            batch = batch,
            memLen = memLen
        )

        assertEquals("Output should be [batch, 1, dim] flattened",
            batch * 1 * dim, output.size)
    }

    // ── Test 3: Decoder single step produces logits ────────────────────

    @Test
    fun `testDecoderSingleStep produces logits with correct shape`() {
        val dim = SMALL_DIM
        val dictSize = SMALL_DICT_SIZE
        val decoder = createSmallDecoder(numLayers = 1)

        val N = 1
        val memLen = 4
        val (memory, mask) = mockEncoderOutput(N, memLen, dim)

        // Simulate first decoder step (step=0) with start token
        val startTokEmbedding = decoder.embedding.sliceArray(
            START_TOK * dim until (START_TOK + 1) * dim
        )

        // Build cache: [N][numLayers+1][maxSeq * dim] initialized to 0
        val numLayers = 1
        val cache = Array(N) { Array(numLayers + 1) { FloatArray(SMALL_MAX_SEQ * dim) } }

        // Run decoder
        val decoded = decoder.decodeStepAll(
            tgt = startTokEmbedding,
            cache = cache,
            memory = memory,
            memoryMask = mask,
            step = 0,
            batch = N,
            memLen = memLen
        )

        assertEquals("decoded output should be [N, dim]",
            N * dim, decoded.size)

        // Apply pred1 + pred
        val logits = decoder.applyPred(decoded)
        assertEquals("logits should be [N, dictSize]",
            N * dictSize, logits.size)

        // Verify logits are finite
        logits.forEach { assertTrue("logit should be finite", it.isFinite()) }
    }

    // ── Test 4: Beam initial expansion ─────────────────────────────────

    @Test
    fun `testBeamInitialExpansion produces N times k beams`() {
        val dim = SMALL_DIM
        val decoder = createSmallDecoder(numLayers = 1)
        val N = 2
        val memLen = 4
        val (memory, mask) = mockEncoderOutput(N, memLen, dim)

        // Step 0: embed start tokens
        val startTokEmbedding = FloatArray(N * dim)
        for (i in 0 until N) {
            val src = decoder.embedding.sliceArray(START_TOK * dim until (START_TOK + 1) * dim)
            src.copyInto(startTokEmbedding, i * dim)
        }

        val numLayers = 1
        val cache = Array(N) { Array(numLayers + 1) { FloatArray(SMALL_MAX_SEQ * dim) } }

        // First decoder step
        val decoded = decoder.decodeStepAll(
            tgt = startTokEmbedding,
            cache = cache,
            memory = memory,
            memoryMask = mask,
            step = 0,
            batch = N,
            memLen = memLen
        )

        val logits = decoder.applyPred(decoded)
        val logProbs = BeamSearchDecoder.logSoftmax2D(logits, N, SMALL_DICT_SIZE)

        // Top-k for each sample
        val topkVals = FloatArray(N * BEAM_K)
        val topkIdxs = IntArray(N * BEAM_K)
        BeamSearchDecoder.topk(logProbs, N, SMALL_DICT_SIZE, BEAM_K, topkVals, topkIdxs)

        // Create hypotheses: start token + top-k token per sample
        val hypos = mutableListOf<Hypothesis>()
        for (n in 0 until N) {
            for (k in 0 until BEAM_K) {
                val hypCache = cache[n].map { it.copyOf() }.toTypedArray()
                hypos.add(Hypothesis(
                    memoryIdx = n,
                    outIdx = listOf(START_TOK, topkIdxs[n * BEAM_K + k]),
                    logProb = topkVals[n * BEAM_K + k],
                    cachedActivations = hypCache
                ))
            }
        }

        // After step 0: N samples, each with k beams = N * k hypotheses
        assertEquals(N * BEAM_K, hypos.size)

        // Verify each hypothesis has 2 tokens (start + first predicted)
        hypos.forEach { h ->
            assertEquals(2, h.outIdx.size)
            assertEquals(START_TOK, h.outIdx[0])
        }

        // Verify each sample has k hypotheses
        val perSample = hypos.groupBy { it.memoryIdx }
        assertEquals(N, perSample.size)
        perSample.values.forEach { assertEquals(BEAM_K, it.size) }
    }

    // ── Test 5: Full beam search loop ──────────────────────────────────

    @Test
    fun `testFullBeamSearchLoop produces at least one hypothesis per sample`() {
        val dim = SMALL_DIM
        val dictSize = SMALL_DICT_SIZE
        // Use identity weights for predW so token 0 gets highest score
        val decoder = BeamSearchDecoder(
            embedding = FloatArray(dictSize * dim) { 0.1f },
            decoderLayerWeights = listOf(mockDecoderLayerWeights(dim, SMALL_FF_DIM)),
            pred1Weights = LinearWeights(zeroWeights(dim, dim), zeroBias(dim)),
            predWeights = LinearWeights(
                weight = FloatArray(dim * dictSize).apply {
                    // Make token 0 have the highest score for beam pruning
                    for (d in 0 until dim) this[d] = 1.0f
                },
                bias = zeroBias(dictSize)
            ),
            dim = dim, numHeads = SMALL_NUM_HEADS, ffDim = SMALL_FF_DIM,
            dictSize = dictSize, numLayers = 1,
            maxSeqLength = SMALL_MAX_SEQ, beamK = BEAM_K,
            startTok = START_TOK, endTok = END_TOK, padTok = PAD_TOK,
            maxFinishedHypos = 2
        )

        val N = 1
        val memLen = 4
        val (memory, mask) = mockEncoderOutput(N, memLen, dim)

        val results = decoder.decode(memory = memory, mask = mask, batch = N)

        assertEquals("Should have results for each sample", N, results.size)
        results.forEach { (tokens, prob) ->
            assertTrue("Tokens should not be empty", tokens.isNotEmpty())
            assertTrue("Probability should be positive", prob > 0f)
            assertTrue("Probability should be <= 1.0", prob <= 1.0f + 1e-5f)
        }
    }

    // ── Test 6: Early termination ──────────────────────────────────────

    @Test
    fun `testEarlyTermination returns before max steps`() {
        val dim = SMALL_DIM
        val dictSize = SMALL_DICT_SIZE
        // Force END token (id=2) at every beam position by making
        // predWeights produce highest logit for token 2
        val decoder = BeamSearchDecoder(
            embedding = FloatArray(dictSize * dim) { 0.1f },
            decoderLayerWeights = listOf(mockDecoderLayerWeights(dim, SMALL_FF_DIM)),
            pred1Weights = LinearWeights(zeroWeights(dim, dim), zeroBias(dim)),
            predWeights = LinearWeights(
                weight = FloatArray(dim * dictSize).apply {
                    for (d in 0 until dim) this[END_TOK * dim + d] = 100.0f
                },
                bias = zeroBias(dictSize)
            ),
            dim = dim, numHeads = SMALL_NUM_HEADS, ffDim = SMALL_FF_DIM,
            dictSize = dictSize, numLayers = 1,
            maxSeqLength = SMALL_MAX_SEQ, beamK = BEAM_K,
            startTok = START_TOK, endTok = END_TOK, padTok = PAD_TOK,
            maxFinishedHypos = 2
        )

        val N = 1
        val memLen = 4
        val (memory, mask) = mockEncoderOutput(N, memLen, dim)

        val startTime = System.nanoTime()
        val results = decoder.decode(memory = memory, mask = mask, batch = N)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        assertTrue("Early termination should finish quickly (< 5 sec for mock)",
            elapsedMs < 5000)

        assertEquals(1, results.size)
        val (tokens, prob) = results[0]
        assertTrue("Tokens should be short (early END)", tokens.size < SMALL_MAX_SEQ)
        assertTrue("Probability should be positive", prob > 0f)
    }

    // ── Test 7: Multiple samples produce correct number of outputs ─────

    @Test
    fun `testMultipleSamples produces correct number of results`() {
        val dim = SMALL_DIM
        val dictSize = SMALL_DICT_SIZE
        val decoder = BeamSearchDecoder(
            embedding = FloatArray(dictSize * dim) { 0.1f },
            decoderLayerWeights = listOf(mockDecoderLayerWeights(dim, SMALL_FF_DIM)),
            pred1Weights = LinearWeights(zeroWeights(dim, dim), zeroBias(dim)),
            predWeights = LinearWeights(
                weight = FloatArray(dim * dictSize).apply {
                    for (d in 0 until dim) this[END_TOK * dim + d] = 50.0f
                },
                bias = zeroBias(dictSize)
            ),
            dim = dim, numHeads = SMALL_NUM_HEADS, ffDim = SMALL_FF_DIM,
            dictSize = dictSize, numLayers = 1,
            maxSeqLength = SMALL_MAX_SEQ, beamK = BEAM_K,
            startTok = START_TOK, endTok = END_TOK, padTok = PAD_TOK,
            maxFinishedHypos = 2
        )

        val N = 3
        val memLen = 4
        val (memory, mask) = mockEncoderOutput(N, memLen, dim)

        val results = decoder.decode(memory = memory, mask = mask, batch = N)
        assertEquals(N, results.size)
    }

    // ── Test 8: KV cache shape and content invariants ──────────────────

    @Test
    fun `testKVCacheShapePreservedAcrossSteps`() {
        val dim = SMALL_DIM
        val numLayers = 1

        // Helper decoder wrapper to test cache
        val decoder = createSmallDecoder(numLayers = 1)

        val N = 1
        val memLen = 4
        val (memory, mask) = mockEncoderOutput(N, memLen, dim)

        val cache = Array(N) { Array(numLayers + 1) { FloatArray(SMALL_MAX_SEQ * dim) } }

        // Step 0
        val emb0 = decoder.embedding.sliceArray(START_TOK * dim until (START_TOK + 1) * dim)
        decoder.decodeStepAll(
            tgt = emb0,
            cache = cache,
            memory = memory,
            memoryMask = mask,
            step = 0,
            batch = N,
            memLen = memLen
        )

        // Cache layer 0, step 0 should be non-zero
        val cacheLayer0 = cache[0][0]
        val step0Values = cacheLayer0.sliceArray(0 until dim)
        val hasNonZero = step0Values.any { kotlin.math.abs(it) > 1e-6f }
        assertTrue("Cache should have non-zero values after step 0", hasNonZero)

        // Step 1 - use END_TOK embedding
        val emb1 = decoder.embedding.sliceArray(END_TOK * dim until (END_TOK + 1) * dim)
        decoder.decodeStepAll(
            tgt = emb1,
            cache = cache,
            memory = memory,
            memoryMask = mask,
            step = 1,
            batch = N,
            memLen = memLen
        )

        // Step 0 and step 1 cache values should differ
        val step1Values = cacheLayer0.sliceArray(dim until 2 * dim)
        var differs = false
        for (i in 0 until dim) {
            if (kotlin.math.abs(step0Values[i] - step1Values[i]) > 1e-6f) {
                differs = true
                break
            }
        }
        assertTrue("Different steps should produce different cache values", differs)
    }

    // ── Test 9: max_seq_length hard limit ──────────────────────────────

    @Test
    fun `testMaxSeqLengthHardLimit`() {
        val dim = SMALL_DIM
        val dictSize = SMALL_DICT_SIZE
        // Never predict END token — make pred1 identity, pred gives token 0 high score, END very negative
        val decoder = BeamSearchDecoder(
            embedding = FloatArray(dictSize * dim) { 0.1f },
            decoderLayerWeights = listOf(mockDecoderLayerWeights(dim, SMALL_FF_DIM)),
            pred1Weights = LinearWeights(identityWeights(dim, dim), zeroBias(dim)),
            predWeights = LinearWeights(
                weight = FloatArray(dim * dictSize).apply {
                    for (d in 0 until dim) this[0 * dim + d] = 1.0f
                    for (d in 0 until dim) this[END_TOK * dim + d] = -100.0f
                },
                bias = FloatArray(dictSize).apply {
                    this[0] = 5.0f  // token 0 gets extra boost
                    this[END_TOK] = -1000.0f  // END never appears
                }
            ),
            dim = dim, numHeads = SMALL_NUM_HEADS, ffDim = SMALL_FF_DIM,
            dictSize = dictSize, numLayers = 1,
            maxSeqLength = SMALL_MAX_SEQ, beamK = BEAM_K,
            startTok = START_TOK, endTok = END_TOK, padTok = PAD_TOK,
            maxFinishedHypos = 2
        )

        val N = 1
        val memLen = 4
        val (memory, mask) = mockEncoderOutput(N, memLen, dim)

        val results = decoder.decode(memory = memory, mask = mask, batch = N)
        assertEquals(1, results.size)
        val (tokens, prob) = results[0]
        // Without END tokens, beam search runs to max seq length
        // Total steps = maxSeqLength (step 0 + maxSeqLength-1 loop iterations), excluding start token
        assertEquals("Should produce maxSeqLength tokens without END token",
            SMALL_MAX_SEQ, tokens.size)
    }

    // ── Test 10: Decoder layer self-attention vs cross-attention shapes ─

    @Test
    fun `testDecoderLayerWithAndWithoutMemoryMask`() {
        val dim = SMALL_DIM
        val numHeads = SMALL_NUM_HEADS
        val ffDim = SMALL_FF_DIM
        val batch = 1
        val memLen = 4

        val layer = TransformerDecoderLayer(dim, numHeads, ffDim)
        val weights = mockDecoderLayerWeights(dim, ffDim)
        val xpos = XposEncoder(headDim = dim / numHeads)

        val tgt = FloatArray(batch * 1 * dim) { 0.5f }
        val tgtCache = FloatArray(0) // empty
        val (memory, mask) = mockEncoderOutput(1, memLen, dim)

        // Without mask
        val out1 = layer.forward(
            tgt = tgt, tgtCache = tgtCache, tgtCacheLen = 0,
            memory = memory, memoryMask = null,
            weights = weights, xpos = xpos, qOffset = 0,
            batch = batch, memLen = memLen
        )
        assertEquals(batch * dim, out1.size)

        // With mask
        val out2 = layer.forward(
            tgt = tgt, tgtCache = tgtCache, tgtCacheLen = 0,
            memory = memory, memoryMask = mask,
            weights = weights, xpos = xpos, qOffset = 0,
            batch = batch, memLen = memLen
        )
        assertEquals(batch * dim, out2.size)
    }
}
