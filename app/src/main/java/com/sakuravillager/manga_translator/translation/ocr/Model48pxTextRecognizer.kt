package com.sakuravillager.manga_translator.translation.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelRegistry
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer

/**
 * ONNX-based 48px OCR text recognizer.
 *
 * Pipeline:
 * 1. Perspective crop each [Quadrilateral] to 48 px height via [Quadrilateral.getTransformedRegion].
 * 2. Sort regions by width (ascending) for efficient batching.
 * 3. Partition into chunks of [MAX_CHUNK_SIZE] and build a single batch ONNX tensor
 *    in NCHW layout normalized to [-1, 1].
 * 4. Run inference – the ONNX model runs the encoder+decoder internally and produces
 *    logits, per-character colour predictions, and colour-indicator gating outputs.
 * 5. Greedy decode (argmax) each sample from the logits.
 * 6. Extract running-average foreground / background colours with indicator gating.
 *
 * Model-output layout (assumed, index-based):
 *   [0] logits         – float32[batch, max_seq_len, vocab_size]
 *   [1] fg_colors      – float32[batch, max_seq_len, 3]  (values in [0,1])
 *   [2] bg_colors      – float32[batch, max_seq_len, 3]
 *   [3] fg_indicators  – float32[batch, max_seq_len, 2]  (has_fg if ind[1] > ind[0])
 *   [4] bg_indicators  – float32[batch, max_seq_len, 2]  (has_bg if ind[1] > ind[0])
 *
 * @property modelDownloadManager  handles model-file retrieval from network or cache.
 * @property sessionManager        manages [OrtSession] lifecycle.
 * @property context               Android context for asset-based dictionary loading.
 */
class Model48pxTextRecognizer(
    private val modelDownloadManager: ModelDownloadManager,
    private val sessionManager: OnnxSessionManager,
    private val context: Context,
) : TextRecognizer {

    override val name: String = "Model48pxTextRecognizer"

    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    private var session: OrtSession? = null
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    companion object {
        /** Fixed input height expected by the ONNX model. */
        const val TEXT_HEIGHT = 48

        /** Maximum number of textlines forwarded in a single ONNX inference call. */
        const val MAX_CHUNK_SIZE = 16

        /** Maximum sequence length the decoder can produce. */
        const val MAX_SEQ_LENGTH = 255

        /**
         * Minimum softmax probability for a predicted token to be accepted.
         * Tokens below this threshold are treated as end-of-sequence.
         */
        const val PROB_THRESHOLD = 0.2f
    }

    // ──────────────────────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────────────────────

    override suspend fun prepare() {
        val modelFile = modelDownloadManager.ensureModel(ModelRegistry.OCR_48PX_MODEL)
        val modelBytes = modelFile.readBytes()
        session = sessionManager.createSession(modelBytes)
        OcrDictionary.load(context)
        _isReady = true
    }

    override suspend fun release() {
        session?.let { sessionManager.closeSession(it) }
        session = null
        _isReady = false
    }

    // ──────────────────────────────────────────────────────────────
    //  Recognise
    // ──────────────────────────────────────────────────────────────

    override suspend fun recognize(
        bitmap: Bitmap,
        textlines: List<Quadrilateral>,
        config: OcrConfig,
    ): List<Quadrilateral> = withContext(Dispatchers.Default) {
        if (textlines.isEmpty()) return@withContext textlines
        val sess = session ?: error("$name has not been prepared – call prepare() first")

        // 1. Perspective crop each textline to 48 px height.
        val cropped = textlines.map { quad ->
            val region = quad.getTransformedRegion(bitmap, quad.direction, TEXT_HEIGHT)
            CroppedRegion(region, quad)
        }

        // 2. Sort by width ascending for tighter batching.
        val sorted = cropped.sortedBy { it.bitmap.width }

        // 3. Process in batches of MAX_CHUNK_SIZE.
        val results = mutableListOf<Quadrilateral>()
        sorted.chunked(MAX_CHUNK_SIZE).forEach { batch ->
            processBatch(sess, batch, results)
        }
        results
    }

    // ──────────────────────────────────────────────────────────────
    //  Batch inference
    // ──────────────────────────────────────────────────────────────

    /**
     * Runs ONNX inference on a single batch of [CroppedRegion] and appends
     * the decoded [Quadrilateral] results to [out].
     */
    private fun processBatch(
        sess: OrtSession,
        batch: List<CroppedRegion>,
        out: MutableList<Quadrilateral>,
    ) {
        val batchSize = batch.size

        // Largest width in this batch, rounded up to the nearest multiple of 4.
        val maxW = batch.maxOf { it.bitmap.width }
        val alignedW = (maxW + 3) / 4 * 4

        // ── Build batch tensor ────────────────────────────────────
        // Shape: [N, 3, TEXT_HEIGHT, alignedW], NCHW, normalized to [-1, 1].
        val batchInput = FloatArray(batchSize * 3 * TEXT_HEIGHT * alignedW)

        for ((idx, cropped) in batch.withIndex()) {
            val srcW = cropped.bitmap.width
            val srcH = cropped.bitmap.height
            val pixels = IntArray(alignedW * TEXT_HEIGHT)

            // Left-align each cropped region; right-side padding stays 0 (→ black, ~ -1).
            cropped.bitmap.getPixels(pixels, 0, alignedW, 0, 0, srcW, srcH)

            val baseOffset = idx * 3 * TEXT_HEIGHT * alignedW
            val ch0Offset = baseOffset
            val ch1Offset = baseOffset + TEXT_HEIGHT * alignedW
            val ch2Offset = baseOffset + 2 * TEXT_HEIGHT * alignedW

            for (y in 0 until TEXT_HEIGHT) {
                val rowStart = y * alignedW
                for (x in 0 until alignedW) {
                    val pixel = pixels[rowStart + x]
                    val r = (((pixel shr 16) and 0xFF) - 127.5f) / 127.5f
                    val g = (((pixel shr 8) and 0xFF) - 127.5f) / 127.5f
                    val b = ((pixel and 0xFF) - 127.5f) / 127.5f
                    val chPos = rowStart + x
                    batchInput[ch0Offset + chPos] = r
                    batchInput[ch1Offset + chPos] = g
                    batchInput[ch2Offset + chPos] = b
                }
            }
        }

        // ── Inference ─────────────────────────────────────────────
        val shape = longArrayOf(
            batchSize.toLong(), 3L, TEXT_HEIGHT.toLong(), alignedW.toLong(),
        )
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(batchInput), shape)

        try {
            // The input name "input" matches the common ONNX export convention.
            // Adjust if the exported model uses a different input name.
            val outputs = sess.run(mapOf("input" to tensor))

            try {
                for (idx in 0 until batchSize) {
                    val quad = batch[idx].quadrilateral

                    // Greedy text decode from logits.
                    val charIds = greedyDecode(outputs, idx, batchSize)
                    val decoded = OcrDictionary.decodeTokenIds(charIds)

                    // Running-average colour extraction.
                    val (fgColor, bgColor) = extractColors(outputs, idx, batchSize)

                    out += quad.copy(
                        text = decoded,
                        fgColor = fgColor,
                        bgColor = bgColor,
                    )
                }
            } finally {
                outputs.close()
            }
        } finally {
            tensor.close()
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Greedy decode
    // ──────────────────────────────────────────────────────────────

    /**
     * Greedy (argmax) decoding for a single sample from the batched model output.
     *
     * Expected output [0]: logits tensor with shape `[batch, seq_len, vocab_size]`.
     * The ONNX model runs the autoregressive loop internally; this method simply
     * walks the logit sequence position by position taking the argmax and stopping
     * at the END token.
     */
    private fun greedyDecode(
        outputs: OrtSession.Result,
        sampleIdx: Int,
        batchSize: Int,
    ): IntArray {
        val logitsTensor = outputs.get(0) as OnnxTensor
        val logitsBuffer = logitsTensor.floatBuffer
        val logitsShape = logitsTensor.info.shape
        val seqLen = logitsShape[1].toInt()
        val vocabSize = logitsShape[2].toInt()

        val ids = mutableListOf<Int>()
        val sampleStride = seqLen * vocabSize
        val sampleBase = sampleIdx * sampleStride

        for (pos in 0 until seqLen.coerceAtMost(MAX_SEQ_LENGTH)) {
            val posBase = sampleBase + pos * vocabSize

            // Argmax at this position.
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (v in 0 until vocabSize) {
                val vv = logitsBuffer.get(posBase + v)
                if (vv > bestVal) {
                    bestVal = vv
                    bestIdx = v
                }
            }

            // End-of-sequence.
            if (bestIdx == OcrDictionary.END) break

            // Skip special tokens.
            if (bestIdx == OcrDictionary.PAD ||
                bestIdx == OcrDictionary.START ||
                bestIdx == OcrDictionary.SEP ||
                bestIdx == OcrDictionary.UNK
            ) continue

            ids.add(bestIdx)
        }

        return ids.toIntArray()
    }

    // ──────────────────────────────────────────────────────────────
    //  Colour extraction
    // ──────────────────────────────────────────────────────────────

    /**
     * Extracts running-average foreground and background colours from the
     * per-character colour predictions, gated by indicator outputs.
     *
     * Expected output layout (index-based):
     *   [1] fg_colors     – [batch, seq_len, 3]  values in [0, 1]
     *   [2] bg_colors     – [batch, seq_len, 3]
     *   [3] fg_indicators – [batch, seq_len, 2]  has_fg if ind[1] > ind[0]
     *   [4] bg_indicators – [batch, seq_len, 2]  has_bg if ind[1] > ind[0]
     *
     * Returns (fgColor, bgColor) as Android [Color] ints, or `null` when no
     * colour is predicted for a channel.
     */
    private fun extractColors(
        outputs: OrtSession.Result,
        sampleIdx: Int,
        batchSize: Int,
    ): Pair<Int?, Int?> {
        // Guard: fewer than 5 outputs means the model does not emit colour
        // predictions (e.g. a CTC-based variant).
        if (outputs.size() < 5) return Pair(null, null)

        val fgColTensor = outputs.get(1) as OnnxTensor
        val bgColTensor = outputs.get(2) as OnnxTensor
        val fgIndTensor = outputs.get(3) as OnnxTensor
        val bgIndTensor = outputs.get(4) as OnnxTensor

        val seqLen = fgColTensor.info.shape[1].toInt()

        val fgColBuf = fgColTensor.floatBuffer
        val bgColBuf = bgColTensor.floatBuffer
        val fgIndBuf = fgIndTensor.floatBuffer
        val bgIndBuf = bgIndTensor.floatBuffer

        val colorStride = seqLen * 3
        val indStride = seqLen * 2
        val fgColBase = sampleIdx * colorStride
        val bgColBase = sampleIdx * colorStride
        val fgIndBase = sampleIdx * indStride
        val bgIndBase = sampleIdx * indStride

        var fgSumR = 0f
        var fgSumG = 0f
        var fgSumB = 0f
        var fgCount = 0

        var bgSumR = 0f
        var bgSumG = 0f
        var bgSumB = 0f
        var bgCount = 0

        for (i in 0 until seqLen) {
            // Indicator gating.
            val fgiOff = fgIndBase + i * 2
            val bgiOff = bgIndBase + i * 2
            val hasFg = fgIndBuf.get(fgiOff + 1) > fgIndBuf.get(fgiOff)
            val hasBg = bgIndBuf.get(bgiOff + 1) > bgIndBuf.get(bgiOff)

            // RGB colour values (model outputs [0, 1], scale to [0, 255]).
            val fcOff = fgColBase + i * 3
            val bcOff = bgColBase + i * 3

            if (hasFg) {
                fgSumR += fgColBuf.get(fcOff) * 255f
                fgSumG += fgColBuf.get(fcOff + 1) * 255f
                fgSumB += fgColBuf.get(fcOff + 2) * 255f
                fgCount++
            }

            if (hasBg) {
                bgSumR += bgColBuf.get(bcOff) * 255f
                bgSumG += bgColBuf.get(bcOff + 1) * 255f
                bgSumB += bgColBuf.get(bcOff + 2) * 255f
                bgCount++
            } else {
                // Fallback: use the foreground colour as background.
                bgSumR += fgColBuf.get(fcOff) * 255f
                bgSumG += fgColBuf.get(fcOff + 1) * 255f
                bgSumB += fgColBuf.get(fcOff + 2) * 255f
                bgCount++
            }
        }

        val fgColor = if (fgCount > 0) {
            Color.rgb(
                (fgSumR / fgCount).toInt().coerceIn(0, 255),
                (fgSumG / fgCount).toInt().coerceIn(0, 255),
                (fgSumB / fgCount).toInt().coerceIn(0, 255),
            )
        } else null

        val bgColor = if (bgCount > 0) {
            Color.rgb(
                (bgSumR / bgCount).toInt().coerceIn(0, 255),
                (bgSumG / bgCount).toInt().coerceIn(0, 255),
                (bgSumB / bgCount).toInt().coerceIn(0, 255),
            )
        } else null

        return Pair(fgColor, bgColor)
    }

    // ──────────────────────────────────────────────────────────────
    //  Internal data
    // ──────────────────────────────────────────────────────────────

    /** Pairs a perspective-cropped [Bitmap] with its source [Quadrilateral]. */
    private data class CroppedRegion(
        val bitmap: Bitmap,
        val quadrilateral: Quadrilateral,
    )
}
