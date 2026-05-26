package com.sakuravillager.manga_translator.translation.ocr

import kotlin.math.exp
import kotlin.math.sqrt

// ═════════════════════════════════════════════════════════════════════
// Weight containers — hold the ONNX/Learned parameters for each layer
// ═════════════════════════════════════════════════════════════════════

/**
 * Weight + optional bias for a Linear layer.
 * @param weight flat array, row-major [outDim, inDim]
 * @param bias  optional flat array [outDim]
 */
class LinearWeights(val weight: FloatArray, val bias: FloatArray? = null)

/**
 * Weight + bias for LayerNorm.
 * @param weight [dim] scale (gamma)
 * @param bias   [dim] shift (beta)
 */
class LayerNormWeights(val weight: FloatArray, val bias: FloatArray)

/**
 * All learned weights for a single Transformer decoder layer.
 */
class DecoderLayerWeights(
    val selfAttnQProj: LinearWeights,
    val selfAttnKProj: LinearWeights,
    val selfAttnVProj: LinearWeights,
    val selfAttnOutProj: LinearWeights,
    val crossAttnQProj: LinearWeights,
    val crossAttnKProj: LinearWeights,
    val crossAttnVProj: LinearWeights,
    val crossAttnOutProj: LinearWeights,
    val norm1: LayerNormWeights,
    val norm2: LayerNormWeights,
    val norm3: LayerNormWeights,
    val ffLinear1: LinearWeights,  // [dim, ffDim]
    val ffLinear2: LinearWeights   // [ffDim, dim]
)

// ═════════════════════════════════════════════════════════════════════
// Activation functions
// ═════════════════════════════════════════════════════════════════════

/** GELU activation. Matches PyTorch's nn.GELU(). */
internal fun gelu(x: Float): Float {
    val c = sqrt(2f / kotlin.math.PI.toFloat())
    return 0.5f * x * (1f + kotlin.math.tanh(c * (x + 0.044715f * x * x * x)))
}

// ═════════════════════════════════════════════════════════════════════
// Linear layer forward
// ═════════════════════════════════════════════════════════════════════

/**
 * Apply a Linear layer: output = input @ W^T + bias
 * (matching PyTorch where weight shape is [outDim, inDim]).
 *
 * @param input  flat array [batch, inDim] row-major
 * @param w      weight [outDim, inDim] row-major
 * @param bias   optional bias [outDim]
 * @param batch  number of items
 * @param inDim  input feature dimension
 * @param outDim output feature dimension
 * @return flat array [batch, outDim]
 */
internal fun linearForward(
    input: FloatArray,
    w: FloatArray,
    bias: FloatArray?,
    batch: Int,
    inDim: Int,
    outDim: Int
): FloatArray {
    val result = FloatArray(batch * outDim)
    for (b in 0 until batch) {
        val baseIn = b * inDim
        val baseOut = b * outDim
        for (o in 0 until outDim) {
            var sum = 0f
            for (i in 0 until inDim) {
                sum += input[baseIn + i] * w[o * inDim + i]
            }
            if (bias != null) sum += bias[o]
            result[baseOut + o] = sum
        }
    }
    return result
}

// ═════════════════════════════════════════════════════════════════════
// LayerNorm (batch_first, feature_last)
// ═════════════════════════════════════════════════════════════════════

/**
 * Apply LayerNorm: output = (x - mean) / sqrt(var + eps) * weight + bias
 *
 * @param x      flat array [batch, seqLen, dim]
 * @param w      weight [dim] scale
 * @param b      bias [dim] shift
 * @param batch  batch size
 * @param seqLen sequence length
 * @param dim    feature dimension
 * @param eps    small constant for numerical stability
 * @return flat array same shape as [x]
 */
internal fun layerNorm(
    x: FloatArray,
    w: FloatArray,
    b: FloatArray,
    batch: Int,
    seqLen: Int,
    dim: Int,
    eps: Float = 1e-5f
): FloatArray {
    val result = FloatArray(x.size)
    for (n in 0 until batch) {
        for (s in 0 until seqLen) {
            val base = (n * seqLen + s) * dim
            var mean = 0f
            for (d in 0 until dim) mean += x[base + d]
            mean /= dim
            var variance = 0f
            for (d in 0 until dim) {
                val diff = x[base + d] - mean
                variance += diff * diff
            }
            variance /= dim
            val invStd = 1f / sqrt(variance + eps)
            for (d in 0 until dim) {
                result[base + d] = (x[base + d] - mean) * invStd * w[d] + b[d]
            }
        }
    }
    return result
}

// ═════════════════════════════════════════════════════════════════════
// Matrix utilities
// ═════════════════════════════════════════════════════════════════════

/**
 * Softmax along the last dimension.
 * Input:  [batch, rows, cols] flat
 * Output: same shape, softmax on each row (dim=2).
 */
internal fun softmax(x: FloatArray, batch: Int, rows: Int, cols: Int): FloatArray {
    val result = FloatArray(x.size)
    val stride = rows * cols
    for (b in 0 until batch) {
        val base = b * stride
        for (r in 0 until rows) {
            val rowBase = base + r * cols
            var maxVal = Float.NEGATIVE_INFINITY
            for (c in 0 until cols) if (x[rowBase + c] > maxVal) maxVal = x[rowBase + c]
            var sumExp = 0f
            for (c in 0 until cols) {
                result[rowBase + c] = exp((x[rowBase + c] - maxVal).toDouble()).toFloat()
                sumExp += result[rowBase + c]
            }
            for (c in 0 until cols) result[rowBase + c] /= sumExp
        }
    }
    return result
}

/**
 * Transpose the last two dimensions of a 3D array.
 * Input:  [batch, rows, cols] flat
 * Output: [batch, cols, rows] flat
 */
internal fun transposeLast2(x: FloatArray, batch: Int, rows: Int, cols: Int): FloatArray {
    val result = FloatArray(batch * cols * rows)
    for (b in 0 until batch) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                result[(b * cols + c) * rows + r] = x[(b * rows + r) * cols + c]
            }
        }
    }
    return result
}

/**
 * Batched matrix multiplication: C[b,i,j] = sum_k A[b,i,k] * B[b,k,j].
 * @param a  [batch, m, k] flat
 * @param b  [batch, k, n] flat
 * @return   [batch, m, n] flat
 */
internal fun batchBmm(
    a: FloatArray,
    b: FloatArray,
    batch: Int,
    m: Int,
    n: Int,
    k: Int
): FloatArray {
    val result = FloatArray(batch * m * n)
    for (bi in 0 until batch) {
        val aBase = bi * m * k
        val bBase = bi * k * n
        val rBase = bi * m * n
        for (i in 0 until m) {
            for (j in 0 until n) {
                var sum = 0f
                for (ki in 0 until k) sum += a[aBase + i * k + ki] * b[bBase + ki * n + j]
                result[rBase + i * n + j] = sum
            }
        }
    }
    return result
}

// ═════════════════════════════════════════════════════════════════════
// Multi-head split / merge
// ═════════════════════════════════════════════════════════════════════

/**
 * Merge batch and num_heads dimensions.
 * Input:  [batch, seqLen, dim] flat, dim = numHeads * headDim
 * Output: [batch * numHeads, seqLen, headDim] flat
 *
 * Python: x.view(batch, seqLen, numHeads, headDim).transpose(1,2)
 *          .reshape(batch * numHeads, seqLen, headDim)
 */
internal fun mergeHeads(
    x: FloatArray,
    batch: Int,
    seqLen: Int,
    numHeads: Int,
    headDim: Int
): FloatArray {
    val result = FloatArray(batch * numHeads * seqLen * headDim)
    for (b in 0 until batch) {
        for (h in 0 until numHeads) {
            for (s in 0 until seqLen) {
                for (d in 0 until headDim) {
                    val srcIdx = ((b * seqLen + s) * (numHeads * headDim)) + (h * headDim + d)
                    val dstIdx = ((b * numHeads + h) * seqLen + s) * headDim + d
                    result[dstIdx] = x[srcIdx]
                }
            }
        }
    }
    return result
}

/**
 * Un-merge batch and num_heads dimensions.
 * Input:  [batch * numHeads, seqLen, headDim] flat
 * Output: [batch, seqLen, dim] flat, dim = numHeads * headDim
 */
internal fun unmergeHeads(
    x: FloatArray,
    batch: Int,
    seqLen: Int,
    numHeads: Int,
    headDim: Int
): FloatArray {
    val dim = numHeads * headDim
    val result = FloatArray(batch * seqLen * dim)
    for (b in 0 until batch) {
        for (h in 0 until numHeads) {
            for (s in 0 until seqLen) {
                for (d in 0 until headDim) {
                    val srcIdx = ((b * numHeads + h) * seqLen + s) * headDim + d
                    val dstIdx = ((b * seqLen + s) * dim) + (h * headDim + d)
                    result[dstIdx] = x[srcIdx]
                }
            }
        }
    }
    return result
}

// ═════════════════════════════════════════════════════════════════════
// XposMultiheadAttention
// ═════════════════════════════════════════════════════════════════════

/**
 * XPOS multi-head scaled dot-product attention.
 * Ported from Python model_48px.py:294-380.
 *
 * Operates on flat FloatArrays with explicit shape parameters.
 */
class XposMultiheadAttention(val dim: Int, val numHeads: Int) {
    val headDim: Int = dim / numHeads
    val scaling: Float = 1f / sqrt(headDim.toFloat())

    /**
     * Forward pass.
     *
     * @param query      [batch, tgtLen, dim] flat
     * @param queryBatch batch size
     * @param queryLen   target sequence length
     * @param key        [batch, srcLen, dim] flat
     * @param keyBatch   batch size
     * @param keyLen     source sequence length
     * @param value      [batch, srcLen, dim] flat
     * @param qProj      Q projection weights
     * @param kProj      K projection weights
     * @param vProj      V projection weights
     * @param outProj    output projection weights
     * @param keyPaddingMask optional [batch, srcLen] boolean (true = masked)
     * @param qOffset    XPOS offset for Q
     * @param kOffset    XPOS offset for K
     * @param xpos       optional XposEncoder for rotary position encoding
     * @return [batch, tgtLen, dim] flat
     */
    fun forward(
        query: FloatArray,
        queryBatch: Int,
        queryLen: Int,
        key: FloatArray,
        keyBatch: Int,
        keyLen: Int,
        value: FloatArray,
        qProj: LinearWeights,
        kProj: LinearWeights,
        vProj: LinearWeights,
        outProj: LinearWeights,
        keyPaddingMask: BooleanArray? = null,
        qOffset: Int = 0,
        kOffset: Int = 0,
        xpos: XposEncoder? = null
    ): FloatArray {
        val batch = queryBatch

        // 1. QKV projections — inputs are [batch, seqLen, dim], flatten seqLen into batch
        val q = linearForward(query, qProj.weight, qProj.bias, batch * queryLen, dim, dim)
        val k = linearForward(key, kProj.weight, kProj.bias, batch * keyLen, dim, dim)
        val v = linearForward(value, vProj.weight, vProj.bias, batch * keyLen, dim, dim)

        // 2. Scale Q
        val qScaled = FloatArray(q.size) { q[it] * scaling }

        // 3. Reshape to multi-head
        val qMh = mergeHeads(qScaled, batch, queryLen, numHeads, headDim)
        val kMh = mergeHeads(k, batch, keyLen, numHeads, headDim)
        val vMh = mergeHeads(v, batch, keyLen, numHeads, headDim)

        // 4. Apply XPOS
        val mergedBatch = batch * numHeads
        val qFinal = xpos?.let {
            it.forward(qMh, offset = qOffset, downscale = false,
                batch = mergedBatch, seqLen = queryLen)
        } ?: qMh
        val kFinal = xpos?.let {
            it.forward(kMh, offset = kOffset, downscale = true,
                batch = mergedBatch, seqLen = keyLen)
        } ?: kMh

        // 5. Batched matmul: Q @ K^T → [mergedBatch, queryLen, keyLen]
        // K is [mergedBatch, keyLen, headDim]; transpose to [mergedBatch, headDim, keyLen]
        val kTransposed = transposeLast2(kFinal, mergedBatch, keyLen, headDim)
        val attnWeights = batchBmm(qFinal, kTransposed, mergedBatch, queryLen, keyLen, headDim)

        // 6. Apply key padding mask
        if (keyPaddingMask != null) {
            for (h in 0 until mergedBatch) {
                val sampleIdx = h / numHeads
                for (qPos in 0 until queryLen) {
                    for (kPos in 0 until keyLen) {
                        if (keyPaddingMask[sampleIdx * keyLen + kPos]) {
                            attnWeights[(h * queryLen + qPos) * keyLen + kPos] = Float.NEGATIVE_INFINITY
                        }
                    }
                }
            }
        }

        // 7. Softmax over key dimension
        val attnSoft = softmax(attnWeights, mergedBatch, queryLen, keyLen)

        // 8. attn @ V
        val attnOut = batchBmm(attnSoft, vMh, mergedBatch, queryLen, headDim, keyLen)

        // 9. Unmerge heads
        val attnUnmerged = unmergeHeads(attnOut, batch, queryLen, numHeads, headDim)

        // 10. Output projection
        return linearForward(attnUnmerged, outProj.weight, outProj.bias, batch, dim, dim)
    }
}

// ═════════════════════════════════════════════════════════════════════
// TransformerDecoderLayer
// ═════════════════════════════════════════════════════════════════════

/**
 * Single Transformer decoder layer with XPOS attention.
 * norm_first=True, batch_first=True.
 */
class TransformerDecoderLayer(val dim: Int, val numHeads: Int, val ffDim: Int) {
    val headDim: Int = dim / numHeads
    val selfAttn = XposMultiheadAttention(dim, numHeads)
    val crossAttn = XposMultiheadAttention(dim, numHeads)

    /**
     * Forward for a single decoding step.
     *
     * @param tgt         [batch, 1, dim] flat — current token hidden state
     * @param tgtCache    [batch, tgtCacheLen, dim] flat — previous INPUTS to this layer
     * @param tgtCacheLen number of cached timesteps
     * @param memory      [batch, memLen, dim] flat — encoder output
     * @param memoryMask  [batch, memLen] boolean — true = padding
     * @param weights     decoder layer weights
     * @param xpos        XposEncoder (shared)
     * @param qOffset     XPOS offset = current step
     * @param batch       batch size
     * @param memLen      encoder memory length
     * @return [batch, 1, dim] flat — layer output
     */
    fun forward(
        tgt: FloatArray,
        tgtCache: FloatArray,
        tgtCacheLen: Int,
        memory: FloatArray,
        memoryMask: BooleanArray?,
        weights: DecoderLayerWeights,
        xpos: XposEncoder,
        qOffset: Int,
        batch: Int,
        memLen: Int
    ): FloatArray {
        val seqLen = tgtCacheLen + 1

        // combined = concat(tgtCache, tgt) → [batch, seqLen, dim]
        val combined = FloatArray(batch * seqLen * dim)
        for (b in 0 until batch) {
            val baseComb = b * seqLen * dim
            val baseCache = b * tgtCacheLen * dim
            val baseTgt = b * dim
            for (i in 0 until tgtCacheLen * dim) combined[baseComb + i] = tgtCache[baseCache + i]
            for (i in 0 until dim) combined[baseComb + tgtCacheLen * dim + i] = tgt[baseTgt + i]
        }

        // --- Self-attention ---
        val normTgt = layerNorm(tgt, weights.norm1.weight, weights.norm1.bias, batch, 1, dim)
        val normCombined = layerNorm(combined, weights.norm1.weight, weights.norm1.bias, batch, seqLen, dim)

        val saOut = selfAttn.forward(
            query = normTgt, queryBatch = batch, queryLen = 1,
            key = normCombined, keyBatch = batch, keyLen = seqLen,
            value = normCombined,
            qProj = weights.selfAttnQProj, kProj = weights.selfAttnKProj,
            vProj = weights.selfAttnVProj, outProj = weights.selfAttnOutProj,
            qOffset = qOffset, kOffset = qOffset, xpos = xpos
        )
        val afterSA = FloatArray(batch * dim) { tgt[it] + saOut[it] }

        // --- Cross-attention ---
        val normAfterSA = layerNorm(afterSA, weights.norm2.weight, weights.norm2.bias, batch, 1, dim)

        val caOut = crossAttn.forward(
            query = normAfterSA, queryBatch = batch, queryLen = 1,
            key = memory, keyBatch = batch, keyLen = memLen,
            value = memory,
            qProj = weights.crossAttnQProj, kProj = weights.crossAttnKProj,
            vProj = weights.crossAttnVProj, outProj = weights.crossAttnOutProj,
            keyPaddingMask = memoryMask,
            qOffset = qOffset, kOffset = 0, xpos = xpos
        )
        val afterCA = FloatArray(batch * dim) { afterSA[it] + caOut[it] }

        // --- FFN ---
        val normAfterCA = layerNorm(afterCA, weights.norm3.weight, weights.norm3.bias, batch, 1, dim)

        val ff1 = linearForward(normAfterCA, weights.ffLinear1.weight, weights.ffLinear1.bias, batch, dim, ffDim)
        val ffAct = FloatArray(ff1.size) { gelu(ff1[it]) }
        val ff2 = linearForward(ffAct, weights.ffLinear2.weight, weights.ffLinear2.bias, batch, ffDim, dim)

        return FloatArray(batch * dim) { afterCA[it] + ff2[it] }
    }
}

// ═════════════════════════════════════════════════════════════════════
// BeamSearchDecoder
// ═════════════════════════════════════════════════════════════════════

/**
 * Autoregressive decoder + beam search loop.
 *
 * Ported from Python model_48px.py infer_beam_batch_tensor (lines 678-801).
 * Uses Hypothesis + BeamSearchData helpers for beam management.
 *
 * @param embedding         [dictSize, dim] flat embedding table
 * @param decoderLayerWeights per-layer weights (size = numLayers)
 * @param pred1Weights      Linear(dim, dim) + GELU
 * @param predWeights       Linear(dim, dictSize)
 * @param dim               model dimension
 * @param numHeads          attention heads
 * @param ffDim             feed-forward hidden dimension
 * @param dictSize          vocabulary size
 * @param numLayers         decoder depth
 * @param maxSeqLength      maximum generation steps
 * @param beamK             beam width
 * @param startTok          start token ID
 * @param endTok            end token ID
 * @param padTok            padding token ID
 * @param maxFinishedHypos  stop condition per sample
 */
class BeamSearchDecoder(
    val embedding: FloatArray,
    val decoderLayerWeights: List<DecoderLayerWeights>,
    val pred1Weights: LinearWeights,
    val predWeights: LinearWeights,
    val dim: Int = 320,
    val numHeads: Int = 4,
    val ffDim: Int = 2048,
    val dictSize: Int = 19264,
    val numLayers: Int = 5,
    val maxSeqLength: Int = 384,
    val beamK: Int = 5,
    val startTok: Int = 1,
    val endTok: Int = 2,
    val padTok: Int = 0,
    val maxFinishedHypos: Int = 2
) {
    init {
        require(embedding.size == dictSize * dim) {
            "embedding size ${embedding.size} != dictSize($dictSize) * dim($dim)"
        }
        require(decoderLayerWeights.size == numLayers) {
            "Need $numLayers decoder layer weights, got ${decoderLayerWeights.size}"
        }
    }

    private val xpos = XposEncoder(headDim = dim / numHeads)
    private val decoderLayers = List(numLayers) { TransformerDecoderLayer(dim, numHeads, ffDim) }

    // ── Public API ────────────────────────────────────────────────────

    /**
     * Run beam search decoding with pre-computed encoder output.
     *
     * @param memory  [batch, memLen, dim] flat — encoder output
     * @param mask    [batch, memLen] boolean — encoder padding mask
     * @param batch   number of input samples
     * @return list of (tokenIds without start token, probability) per sample
     */
    fun decode(
        memory: FloatArray,
        mask: BooleanArray,
        batch: Int
    ): List<Pair<IntArray, Float>> {
        require(memory.size == batch * (memory.size / batch)) { "memory size must divide by batch" }
        val memLen = memory.size / (batch * dim)

        // ── Step 0: initial decoder pass with start tokens ────────────
        val startEmb = lookupEmbedding(startTok)
        val tgt = FloatArray(batch * dim) { idx -> startEmb[idx % dim] }

        // Cache: [batch][numLayers+1][maxSeqLength * dim], all zeros
        val cache = Array(batch) { Array(numLayers + 1) { FloatArray(maxSeqLength * dim) } }

        val decoded = decodeStepAll(tgt, cache, memory, mask, 0, batch, memLen)
        val logits = applyPred(decoded)
        val logProbs = logSoftmax2D(logits, batch, dictSize)

        // Top-k for first token — max possible size: batch * beamK * maxSeqLength
        // (we'll slice to actual size in each iteration)
        val maxBeams = batch * beamK
        var topkVals = FloatArray(maxBeams * maxSeqLength)
        var topkIdxs = IntArray(maxBeams * maxSeqLength)
        topk(logProbs, batch, dictSize, beamK, topkVals, topkIdxs)

        // Create initial hypotheses (2 tokens: start + first prediction)
        var hypos = mutableListOf<Hypothesis>()
        for (n in 0 until batch) {
            for (k in 0 until beamK) {
                val hypCache = cache[n].map { it.copyOf() }.toTypedArray()
                hypos.add(Hypothesis(
                    memoryIdx = n,
                    outIdx = listOf(startTok, topkIdxs[n * beamK + k]),
                    logProb = topkVals[n * beamK + k],
                    cachedActivations = hypCache
                ))
            }
        }

        // Track finished hypotheses per original sample
        val finishedHypos = mutableMapOf<Int, MutableList<Hypothesis>>()

        // ── Main beam search loop ─────────────────────────────────────
        for (step in 1 until maxSeqLength) {
            if (hypos.isEmpty()) break

            val batchSize = hypos.size  // N_remaining * k

            // Gather embeddings for each hypothesis's last token
            val tgtStep = FloatArray(batchSize * dim)
            for (i in 0 until batchSize) {
                val emb = lookupEmbedding(hypos[i].outIdx.last())
                emb.copyInto(tgtStep, i * dim)
            }

            // Gather memory/mask per hypothesis (indexed by memoryIdx)
            val stepMemory = FloatArray(batchSize * memLen * dim)
            val stepMask = BooleanArray(batchSize * memLen)
            for (i in 0 until batchSize) {
                val sidx = hypos[i].memoryIdx
                memory.copyInto(stepMemory, i * memLen * dim, sidx * memLen * dim, (sidx + 1) * memLen * dim)
                mask.copyInto(stepMask, i * memLen, sidx * memLen, (sidx + 1) * memLen)
            }

            // Use each hypothesis's cache directly (decodeStepAll updates in-place)
            val stepCache = Array(batchSize) { hypos[it].cachedActivations }
            val decodedStep = decodeStepAll(tgtStep, stepCache, stepMemory, stepMask, step, batchSize, memLen)

            // Allocate topk arrays for current batch size
            val curTopkVals = FloatArray(batchSize * beamK)
            val curTopkIdxs = IntArray(batchSize * beamK)

            // Log-softmax -> topk
            val logitsStep = applyPred(decodedStep)
            val logProbsStep = logSoftmax2D(logitsStep, batchSize, dictSize)
            topk(logProbsStep, batchSize, dictSize, beamK, curTopkVals, curTopkIdxs)

            // Force END token for finished beams
            for (i in 0 until batchSize) {
                if (hypos[i].seqEnd()) {
                    for (k in 0 until beamK) {
                        curTopkVals[i * beamK + k] = 0f
                        curTopkIdxs[i * beamK + k] = endTok
                    }
                }
            }

            // Extend each hypothesis with top-k tokens
            val extended = mutableListOf<Hypothesis>()
            for (i in 0 until batchSize) {
                for (k in 0 until beamK) {
                    extended.add(hypos[i].extend(curTopkIdxs[i * beamK + k], curTopkVals[i * beamK + k]))
                }
            }

            // Prune: keep top-k per sample
            val pruned = mutableListOf<Hypothesis>()
            val perSample = extended.groupBy { it.memoryIdx }
            for ((_, sampleHypos) in perSample) {
                val sorted = sampleHypos.sortedByDescending { it.sortKey() }
                pruned.addAll(sorted.take(beamK))
            }

            // Separate finished from active
            val active = mutableListOf<Hypothesis>()
            for (hyp in pruned) {
                if (hyp.seqEnd()) {
                    finishedHypos.getOrPut(hyp.memoryIdx) { mutableListOf() }.add(hyp)
                } else {
                    active.add(hyp)
                }
            }

            // Mark done samples: those with maxFinishedHypos finished hypotheses
            val doneSamples = finishedHypos.filter { it.value.size >= maxFinishedHypos }.keys
            active.removeAll { it.memoryIdx in doneSamples }

            hypos = active
        }

        // ── Collect remaining active hypotheses into finished ──────────
        for (hyp in hypos) {
            finishedHypos.getOrPut(hyp.memoryIdx) { mutableListOf() }.add(hyp)
        }

        // ── Return best hypothesis per sample ─────────────────────────
        val results = mutableListOf<Pair<IntArray, Float>>()
        for (sample in 0 until batch) {
            val sampleFinished = finishedHypos[sample]
            if (sampleFinished.isNullOrEmpty()) {
                results.add(intArrayOf(endTok) to 0f)
            } else {
                val best = sampleFinished.maxBy { it.sortKey() }
                results.add(best.outIdx.drop(1).toIntArray() to best.prob())
            }
        }
        return results
    }

    // ── Internal helpers ──────────────────────────────────────────────

    /** Look up embedding vector for a token. */
    fun lookupEmbedding(token: Int): FloatArray {
        val idx = token.coerceIn(0, dictSize - 1)
        return FloatArray(dim) { d -> embedding[idx * dim + d] }
    }

    /**
     * Run one decoder step across all layers.
     * Updates cache in-place.
     *
     * @param tgt        [batch, dim] flat
     * @param cache      [batch][numLayers+1][maxSeqLen*dim]
     * @param memory     [batch, memLen, dim] flat
     * @param memoryMask [batch, memLen] boolean
     * @param step       current step index (0-based)
     * @param batch      number of items
     * @param memLen     memory sequence length
     * @return [batch, dim] flat — decoder output
     */
    fun decodeStepAll(
        tgt: FloatArray,
        cache: Array<Array<FloatArray>>,
        memory: FloatArray,
        memoryMask: BooleanArray,
        step: Int,
        batch: Int,
        memLen: Int
    ): FloatArray {
        var current = tgt.copyOf()

        for (l in 0 until numLayers) {
            val layerWeights = decoderLayerWeights[l]
            val layer = decoderLayers[l]

            // Build tgtCache from cache[l]: [batch, step, dim]
            val tgtCache = FloatArray(batch * step * dim)
            for (b in 0 until batch) {
                for (s in 0 until step) {
                    for (d in 0 until dim) {
                        tgtCache[(b * step + s) * dim + d] = cache[b][l][s * dim + d]
                    }
                }
            }

            // Run the layer
            val layerOut = layer.forward(
                tgt = current,
                tgtCache = tgtCache,
                tgtCacheLen = step,
                memory = memory,
                memoryMask = memoryMask,
                weights = layerWeights,
                xpos = xpos,
                qOffset = step,
                batch = batch,
                memLen = memLen
            )

            // Save INPUT to cache[l][step]
            for (b in 0 until batch) {
                for (d in 0 until dim) {
                    cache[b][l][step * dim + d] = current[b * dim + d]
                }
            }

            current = layerOut
        }

        // Save final decoder OUTPUT to cache[numLayers][step]
        for (b in 0 until batch) {
            for (d in 0 until dim) {
                cache[b][numLayers][step * dim + d] = current[b * dim + d]
            }
        }

        return current
    }

    /**
     * Apply pred1 (Linear -> GELU) + pred (Linear) to get logits.
     * Dropout is omitted at inference (eval mode).
     */
    fun applyPred(decoded: FloatArray): FloatArray {
        val batch = decoded.size / dim
        val h1 = linearForward(decoded, pred1Weights.weight, pred1Weights.bias, batch, dim, dim)
        val h1Act = FloatArray(h1.size) { gelu(h1[it]) }
        return linearForward(h1Act, predWeights.weight, predWeights.bias, batch, dim, dictSize)
    }

    companion object {
        /** Log-softmax along last dimension. */
        fun logSoftmax2D(x: FloatArray, batch: Int, cols: Int): FloatArray {
            val result = FloatArray(x.size)
            for (b in 0 until batch) {
                val base = b * cols
                var maxV = Float.NEGATIVE_INFINITY
                for (c in 0 until cols) if (x[base + c] > maxV) maxV = x[base + c]
                var sumExp = 0.0
                for (c in 0 until cols) sumExp += exp((x[base + c] - maxV).toDouble())
                val lse = kotlin.math.ln(sumExp)
                for (c in 0 until cols) result[base + c] = x[base + c] - maxV - lse.toFloat()
            }
            return result
        }

        /** Top-k values and indices along last dimension. */
        fun topk(
            values: FloatArray,
            batch: Int,
            cols: Int,
            k: Int,
            outValues: FloatArray,
            outIndices: IntArray
        ) {
            for (b in 0 until batch) {
                val base = b * cols
                val indexed = (0 until cols)
                    .map { values[base + it] to it }
                    .sortedByDescending { it.first }
                    .take(k)
                for (i in 0 until k) {
                    outValues[b * k + i] = indexed.getOrNull(i)?.first ?: Float.NEGATIVE_INFINITY
                    outIndices[b * k + i] = indexed.getOrNull(i)?.second ?: 0
                }
            }
        }
    }
}
