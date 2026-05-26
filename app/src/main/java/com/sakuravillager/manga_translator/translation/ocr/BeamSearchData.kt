package com.sakuravillager.manga_translator.translation.ocr

import kotlin.math.exp
import kotlin.math.ln

/**
 * Immutable hypothesis for beam search decoding.
 *
 * Mirrors the Python Hypothesis class from model_48px.py.
 * Each Hypothesis tracks a single decoding path: the sequence of token IDs,
 * its cumulative log probability, and the cached decoder activations.
 */
class Hypothesis(
    val memoryIdx: Int,
    val outIdx: List<Int>,
    val logProb: Float,
    val cachedActivations: Array<FloatArray>
) {
    /**
     * Returns a NEW Hypothesis with [token] appended and [newLogProb] added
     * to the cumulative log probability. The original Hypothesis is unchanged.
     * Cached activations are deep-copied to preserve immutability.
     */
    fun extend(token: Int, newLogProb: Float): Hypothesis {
        return Hypothesis(
            memoryIdx = memoryIdx,
            outIdx = outIdx + token,
            logProb = logProb + newLogProb,
            cachedActivations = cachedActivations.map { it.copyOf() }.toTypedArray()
        )
    }

    /**
     * Average log probability: logProb / (sequence_length - 1).
     * Follows the Python convention where the denominator excludes the start token.
     */
    fun sortKey(): Float = logProb / (outIdx.size - 1).toFloat()

    /**
     * True when the last token is the END token (2).
     */
    fun seqEnd(): Boolean = outIdx.last() == 2

    /**
     * Returns (token IDs excluding the START token, probability = exp(sortKey())).
     */
    fun output(): Pair<IntArray, Float> {
        val tokens = outIdx.drop(1).toIntArray()
        return tokens to prob()
    }

    /**
     * Probability derived from the average log probability: exp(sortKey()).
     */
    fun prob(): Float = exp(sortKey())

    // ── Structural equality (content-based for cachedActivations) ─────

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Hypothesis) return false
        return memoryIdx == other.memoryIdx &&
                outIdx == other.outIdx &&
                logProb == other.logProb &&
                cachedActivations.contentEquals(other.cachedActivations)
    }

    override fun hashCode(): Int {
        var result = memoryIdx
        result = 31 * result + outIdx.hashCode()
        result = 31 * result + logProb.hashCode()
        result = 31 * result + cachedActivations.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "Hypothesis(memoryIdx=$memoryIdx, outIdx=$outIdx, logProb=$logProb, " +
                "cachedActivations=${cachedActivations.contentDeepToString()})"
    }
}

// ── Top-level Beam Search Functions ───────────────────────────────────

/**
 * Creates [N] samples × [k] beams, each beam starting with [startTok].
 * Returns a list of [N] lists, each containing [k] identical Hypotheses.
 */
fun initBeams(N: Int, k: Int, startTok: Int = 1): List<List<Hypothesis>> {
    return List(N) { sampleIdx ->
        val emptyActivations = emptyArray<FloatArray>()
        List(k) {
            Hypothesis(
                memoryIdx = sampleIdx,
                outIdx = listOf(startTok),
                logProb = 0f,
                cachedActivations = emptyActivations
            )
        }
    }
}

/**
 * Numerically stable log-softmax for a FloatArray of logits.
 * Returns log-softmax values: logits[i] - max - ln(sum(exp(logits - max)))
 */
internal fun logSoftmax(logits: FloatArray): FloatArray {
    val max = logits.max()
    val sumExp = logits.sumOf { exp((it - max).toDouble()) }
    val logSumExp = ln(sumExp)
    return FloatArray(logits.size) { logits[it] - max - logSumExp.toFloat() }
}

/**
 * For each hypothesis in [hypos], extend with the top-[k] tokens
 * by log-softmax score from the corresponding logits in [logits].
 *
 * All N*k² extended hypotheses are then sorted by [Hypothesis.sortKey]
 * (descending — higher average log prob is better), and the top-[k]
 * per sample are retained.
 *
 * @param hypos  current active hypotheses (N items)
 * @param logits one [FloatArray] of raw logits per hypothesis in [hypos]
 * @param k      beam width (number of extensions per hypothesis,
 *               and number of survivors per sample)
 * @return pruned list of hypotheses, at most N×[k]
 */
fun extendAndPrune(
    hypos: List<Hypothesis>,
    logits: List<FloatArray>,
    k: Int
): List<Hypothesis> {
    require(hypos.size == logits.size) {
        "hypos.size (${hypos.size}) must match logits.size (${logits.size})"
    }

    // 1. Extend each hypothesis with top-k tokens via log-softmax
    val extended = mutableListOf<Hypothesis>()
    for (i in hypos.indices) {
        val hyp = hypos[i]
        val logProbs = logSoftmax(logits[i])

        // Find top-k indices and values
        val indexed = logProbs.mapIndexed { idx, value -> idx to value }
            .sortedByDescending { (_, value) -> value }
            .take(k)

        for ((tokenIdx, tokenLogProb) in indexed) {
            extended.add(hyp.extend(tokenIdx, tokenLogProb))
        }
    }

    // 2. Group by sample (memoryIdx) and keep top-k per sample by sortKey
    val perSample = extended.groupBy { it.memoryIdx }
    val pruned = mutableListOf<Hypothesis>()
    for ((_, sampleHypos) in perSample) {
        val sorted = sampleHypos.sortedByDescending { it.sortKey() }
        pruned.addAll(sorted.take(k))
    }

    return pruned
}

/**
 * Separates hypotheses into active (still decoding) and finished.
 *
 * A hypothesis is "finished" when [Hypothesis.seqEnd] is true.
 * A sample is "done" when it has accumulated at least [maxFinished]
 * finished hypotheses. Once a sample is done, its remaining active
 * hypotheses are discarded and the best finished hypothesis
 * (highest [Hypothesis.sortKey]) is recorded in the returned map.
 *
 * @param hypos       current set of hypotheses (may include ended ones)
 * @param maxFinished number of finished hypotheses needed to consider
 *                    a sample complete (default 2)
 * @return (active hypotheses, best finished hypothesis per done sample)
 */
fun selectFinished(
    hypos: List<Hypothesis>,
    maxFinished: Int = 2
): Pair<List<Hypothesis>, Map<Int, Hypothesis>> {
    val finishedPerSample = mutableMapOf<Int, MutableList<Hypothesis>>()
    val active = mutableListOf<Hypothesis>()

    // Separate into active and finished
    for (h in hypos) {
        if (h.seqEnd()) {
            finishedPerSample.getOrPut(h.memoryIdx) { mutableListOf() }.add(h)
        } else {
            active.add(h)
        }
    }

    // Determine which samples have enough finished hypotheses
    val doneSamples = finishedPerSample.filter { it.value.size >= maxFinished }.keys

    // Remove active hypotheses for done samples
    active.removeAll { it.memoryIdx in doneSamples }

    // Build result map: best finished hypothesis per done sample
    val finishedResult = mutableMapOf<Int, Hypothesis>()
    for (sample in doneSamples) {
        val best = finishedPerSample[sample]!!.maxBy { it.sortKey() }
        finishedResult[sample] = best
    }

    return active to finishedResult
}
