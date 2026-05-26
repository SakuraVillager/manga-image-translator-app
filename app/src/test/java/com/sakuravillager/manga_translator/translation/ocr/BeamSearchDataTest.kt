package com.sakuravillager.manga_translator.translation.ocr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp

class BeamSearchDataTest {

    // ── Hypothesis Tests ──────────────────────────────────────────────

    @Test
    fun `testHypothesisExtendImmutability extend returns new original unchanged`() {
        val cached = arrayOf(floatArrayOf(0.1f, 0.2f))
        val original = Hypothesis(
            memoryIdx = 0,
            outIdx = listOf(1),
            logProb = 0f,
            cachedActivations = cached
        )
        val extended = original.extend(token = 5, newLogProb = -0.5f)

        // Original unchanged
        assertEquals(listOf(1), original.outIdx)
        assertEquals(0f, original.logProb, 0f)
        assertArrayEquals(arrayOf(floatArrayOf(0.1f, 0.2f)), original.cachedActivations)

        // New hypothesis has updated values
        assertEquals(listOf(1, 5), extended.outIdx)
        assertEquals(-0.5f, extended.logProb, 0f)

        // Cached activations are deep-copied (not same reference)
        assertNotSame(original.cachedActivations, extended.cachedActivations)
        assertNotSame(original.cachedActivations[0], extended.cachedActivations[0])

        // Modifying original's cache does NOT affect extended
        original.cachedActivations[0][0] = 999f
        assertEquals(0.1f, extended.cachedActivations[0][0], 0f)
    }

    @Test
    fun `testSeqEnd outIdx ending with 2 returns true`() {
        val ended = Hypothesis(
            memoryIdx = 0,
            outIdx = listOf(1, 3, 7, 2),
            logProb = -1.2f,
            cachedActivations = emptyArray()
        )
        assertTrue(ended.seqEnd())
    }

    @Test
    fun `testSeqEnd outIdx not ending with 2 returns false`() {
        val notEnded = Hypothesis(
            memoryIdx = 0,
            outIdx = listOf(1, 3, 7),
            logProb = -1.2f,
            cachedActivations = emptyArray()
        )
        assertEquals(false, notEnded.seqEnd())
    }

    @Test
    fun `testSortKey average log probability`() {
        // logProb = -2.0, length = 3 (indices: 0=start, 1, 2)
        // sortKey = -2.0 / (3 - 1) = -2.0 / 2 = -1.0
        val hyp = Hypothesis(
            memoryIdx = 0,
            outIdx = listOf(1, 10, 20),
            logProb = -2.0f,
            cachedActivations = emptyArray()
        )
        assertEquals(-1.0f, hyp.sortKey(), 1e-6f)
    }

    @Test
    fun `testSortKey single token after start`() {
        // logProb = -0.5, length = 2 (start + one token)
        // sortKey = -0.5 / (2 - 1) = -0.5
        val hyp = Hypothesis(
            memoryIdx = 1,
            outIdx = listOf(1, 42),
            logProb = -0.5f,
            cachedActivations = emptyArray()
        )
        assertEquals(-0.5f, hyp.sortKey(), 1e-6f)
    }

    @Test
    fun `testOutput excludes start token and returns probability`() {
        // outIdx = [1, 10, 20, 30], logProb = -3.0
        // Expected tokens = [10, 20, 30]
        // sortKey = -3.0 / (4 - 1) = -1.0
        // prob = exp(-1.0)
        val hyp = Hypothesis(
            memoryIdx = 0,
            outIdx = listOf(1, 10, 20, 30),
            logProb = -3.0f,
            cachedActivations = emptyArray()
        )
        val (tokens, prob) = hyp.output()
        assertArrayEquals(intArrayOf(10, 20, 30), tokens)
        assertEquals(exp(-1.0), prob.toDouble(), 1e-6)
    }

    @Test
    fun `testProb returns exp of sortKey`() {
        val hyp = Hypothesis(
            memoryIdx = 0,
            outIdx = listOf(1, 10, 20),
            logProb = -2.0f,
            cachedActivations = emptyArray()
        )
        // sortKey = -2.0 / 2 = -1.0
        // prob = exp(-1.0)
        assertEquals(exp(-1.0), hyp.prob().toDouble(), 1e-6)
    }

    // ── initBeams Tests ───────────────────────────────────────────────

    @Test
    fun `testInitBeams creates correct number of beams with start token`() {
        val beams = initBeams(N = 2, k = 5)
        assertEquals(2, beams.size)
        assertEquals(5, beams[0].size)
        assertEquals(5, beams[1].size)

        // Each beam starts with [1] and logProb=0
        for (sampleBeams in beams) {
            for (hyp in sampleBeams) {
                assertEquals(listOf(1), hyp.outIdx)
                assertEquals(0f, hyp.logProb, 0f)
            }
        }
        // Different memoryIdx per sample
        for (hyp in beams[0]) assertEquals(0, hyp.memoryIdx)
        for (hyp in beams[1]) assertEquals(1, hyp.memoryIdx)
    }

    @Test
    fun `testInitBeams custom start token`() {
        val beams = initBeams(N = 1, k = 3, startTok = 99)
        assertEquals(1, beams.size)
        assertEquals(3, beams[0].size)
        for (hyp in beams[0]) {
            assertEquals(listOf(99), hyp.outIdx)
            assertEquals(0f, hyp.logProb, 0f)
        }
    }

    // ── extendAndPrune Tests ──────────────────────────────────────────

    @Test
    fun `testExtendAndPrune selects top-k from logits`() {
        // Two hypotheses (same sample), each with 4 logits
        // Hyp 0: big logit at index 3 → should be selected as top-1
        // Hyp 1: big logit at index 1 → should be selected as top-1
        val hypos = listOf(
            Hypothesis(0, listOf(1), 0f, emptyArray()),
            Hypothesis(0, listOf(1), 0f, emptyArray())
        )
        // logits: index 3 has highest value for hyp 0, index 1 for hyp 1
        val logits = listOf(
            floatArrayOf(-10f, -10f, -10f, 5f, -10f),   // best is index 3
            floatArrayOf(-10f, 8f, -10f, -10f, -10f)     // best is index 1
        )
        val result = extendAndPrune(hypos, logits, k = 1)

        // Only top-1 per sample, 1 sample → 1 hypothesis
        assertEquals(1, result.size)
        // The best overall: hyp 1 extended with index 1 (log-softmax score ~ 8 - logsumexp)
        // Actually both hypos are for sample 0, so they compete.
        // After extension there are 2 hypos (1 per original × k=1).
        // Both are for sample 0. Keep top-1 by sortKey.
        // Need to figure out which has higher average log prob.
        assertEquals(0, result[0].memoryIdx)
        // The extended hypothesis should have 3 tokens: [1, original_top_idx, extended_top_idx]
        // Wait no, hypos already have outIdx=[1] (start token).
        // Extension adds one more token.
        // So extended outIdx should be [1, X] where X is the best token.
        assertEquals(2, result[0].outIdx.size)
    }

    @Test
    fun `testExtendAndPrune multiple samples keep top-k per sample`() {
        // 2 samples, 1 beam each, k=2
        // After extension: 2*2=4 hypos
        // After pruning: 2*2=4 still, top-2 per sample
        val hypos = listOf(
            Hypothesis(0, listOf(1), 0f, emptyArray()),
            Hypothesis(1, listOf(1), 0f, emptyArray())
        )
        val logits = listOf(
            floatArrayOf(-1f, -2f, -3f, -10f),
            floatArrayOf(-3f, -2f, -1f, -10f)
        )
        val result = extendAndPrune(hypos, logits, k = 2)

        // 2 samples × 2 beams = 4 results
        assertEquals(4, result.size)

        // Each sample should have exactly 2 hypotheses
        val sample0Hypos = result.filter { it.memoryIdx == 0 }
        val sample1Hypos = result.filter { it.memoryIdx == 1 }
        assertEquals(2, sample0Hypos.size)
        assertEquals(2, sample1Hypos.size)

        // Verify tokens are extended (outIdx should have 2 elements: start + token)
        for (h in result) {
            assertEquals(2, h.outIdx.size)
            assertEquals(1, h.outIdx[0]) // start token preserved
        }
    }

    @Test
    fun `testExtendAndPrune each extension has log-softmax score`() {
        // Single hyp, k=2, verify the extended logProb reflects log-softmax
        val hypos = listOf(
            Hypothesis(0, listOf(1), 0f, emptyArray())
        )
        // logits: [0, -100, -100, -100]
        // log-softmax of index 0 ≈ 0 - ln(exp(0) + exp(-100)*3) ≈ 0 - ln(1 + tiny) ≈ 0
        val logits = listOf(
            floatArrayOf(0f, -100f, -100f, -100f)
        )
        val result = extendAndPrune(hypos, logits, k = 2)

        // 1 hyp × 2 extensions = 2 hypos. Prune to top-2 per sample = 2.
        assertEquals(2, result.size)
        // Actually, k=2 means each hyp extends by top-2 tokens.
        // 1 hyp × 2 extensions = 2 hypos. Prune to top-2 per sample = 2. So result.size=2.
        assertEquals(2, result.size)
        // The best token should be index 0
        assertEquals(0, result[0].outIdx[1])
        // logProb should be approximately log-softmax(0) ~ 0 (very close to 0)
        assertTrue(result[0].logProb <= 0f) // log-softmax is always negative or zero
    }

    // ── selectFinished Tests ──────────────────────────────────────────

    @Test
    fun `testSelectFinished sample with maxFinished ended hypotheses is done`() {
        // One sample with 3 hypos: 2 ended, 1 active
        val hypos = listOf(
            Hypothesis(0, listOf(1, 10, 2), -0.5f, emptyArray()),  // ended
            Hypothesis(0, listOf(1, 20, 2), -0.3f, emptyArray()),  // ended
            Hypothesis(0, listOf(1, 30), -0.7f, emptyArray())      // active
        )
        val (active, finished) = selectFinished(hypos, maxFinished = 2)

        // Sample 0 has 2 finished (>=2) → done
        assertEquals(1, finished.size)
        assertTrue(finished.containsKey(0))
        // Best by sortKey: hyp with logProb=-0.3, outIdx.size=3
        // sortKey = -0.3 / (3-1) = -0.15 > -0.5/(3-1) = -0.25
        assertEquals(-0.3f, finished[0]!!.logProb, 0f)

        // Active should be empty (sample is done)
        assertTrue(active.isEmpty())
    }

    @Test
    fun `testSelectFinished sample not yet done keeps active hypos`() {
        // 2 samples, each with ended and active hypos
        val hypos = listOf(
            Hypothesis(0, listOf(1, 10, 2), -0.5f, emptyArray()),  // sample 0 ended
            Hypothesis(0, listOf(1, 30), -0.7f, emptyArray()),      // sample 0 active
            Hypothesis(1, listOf(1, 40, 2), -0.5f, emptyArray()),  // sample 1 ended (only 1)
            Hypothesis(1, listOf(1, 50), -0.7f, emptyArray())       // sample 1 active
        )
        val (active, finished) = selectFinished(hypos, maxFinished = 2)

        // Sample 0: 1 ended < 2 → not done
        // Sample 1: 1 ended < 2 → not done
        // No sample has 2 finished, so finished map is empty
        assertTrue(finished.isEmpty())

        // All non-ended hypos should be active
        assertEquals(2, active.size)
        assertTrue(active.all { !it.seqEnd() })
    }

    @Test
    fun `testSelectFinished only one sample meets maxFinished threshold`() {
        // Sample 0: 2 ended (done), sample 1: 1 ended (not done)
        val hypos = listOf(
            Hypothesis(0, listOf(1, 10, 2), -0.5f, emptyArray()),
            Hypothesis(0, listOf(1, 20, 2), -0.3f, emptyArray()),
            Hypothesis(0, listOf(1, 30), -0.7f, emptyArray()),
            Hypothesis(1, listOf(1, 40, 2), -0.4f, emptyArray()),
            Hypothesis(1, listOf(1, 50), -0.6f, emptyArray())
        )
        val (active, finished) = selectFinished(hypos, maxFinished = 2)

        // Sample 0 is done
        assertEquals(1, finished.size)
        assertTrue(finished.containsKey(0))

        // Sample 1 is not done, so its active hypo remains
        assertEquals(1, active.size)
        assertEquals(1, active[0].memoryIdx)
        assertEquals(listOf(1, 50), active[0].outIdx)
    }

    @Test
    fun `testSelectFinished custom maxFinished`() {
        val hypos = listOf(
            Hypothesis(0, listOf(1, 10, 2), -0.5f, emptyArray()),
            Hypothesis(0, listOf(1, 20, 2), -0.3f, emptyArray()),
            Hypothesis(0, listOf(1, 30, 2), -0.7f, emptyArray()),
            Hypothesis(0, listOf(1, 40), -0.6f, emptyArray())
        )
        val (active, finished) = selectFinished(hypos, maxFinished = 3)

        // 3 ended ≥ 3 → done
        assertEquals(1, finished.size)
        assertTrue(finished.containsKey(0))
        assertTrue(active.isEmpty())
    }
}
