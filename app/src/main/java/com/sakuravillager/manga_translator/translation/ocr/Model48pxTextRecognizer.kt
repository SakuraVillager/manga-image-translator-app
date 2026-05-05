package com.sakuravillager.manga_translator.translation.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextDirection
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * CTC-based 48px OCR text recognizer using ONNX Runtime.
 *
 * Pipeline:
 * 1. Load the ONNX model from assets/models/ocr_ctc_48px.onnx
 * 2. Perspective-crop each Quadrilateral to 48px height
 * 3. Sort regions by width (matching Python reference)
 * 4. Batch-pad to uniform width, normalize to [-1, 1]
 * 5. Single ONNX forward -> (logits, colors)
 * 6. CTC greedy decode (argmax -> collapse -> blank removal)
 * 7. Extract fg/bg colors
 *
 * Memory-safe design:
 * - Batch size is dynamically adjusted based on region widths to bound
 *   the input tensor allocation (the main remaining large allocation).
 * - Per-sample logits are read from the FloatBuffer one timestep at a time
 *   via a reused FloatArray(D) buffer (~77KB) instead of allocating a
 *   FloatArray(TxD) (~46MB for wide regions).
 * - Regions are sorted by width (matching Python) so similar-width regions
 *   are batched together, maximizing padding efficiency.
 */
class Model48pxTextRecognizer(
    private val context: Context,
) : TextRecognizer {

    override val name: String = "Model48pxCtcRecognizer"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    private var session: OrtSession? = null
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    companion object {
        const val TEXT_HEIGHT = 48
        const val MAX_CHUNK_SIZE = 16
        const val TAG = "CtcRecognizer"

        /**
         * Maximum estimated bytes for the input tensor [N, 3, 48, maxW] x 4 bytes/float.
         * This is the dominant remaining allocation after eliminating per-sample
         * FloatArray(TxD). Budget: ~10MB keeps typical batches small enough for
         * low-memory devices while remaining responsive for narrow regions.
         */
        private const val MAX_INPUT_TENSOR_BYTES = 10 * 1024 * 1024L
    }

    override suspend fun prepare() {
        val modelBytes = context.assets.open("models/ocr_ctc_48px.onnx").use { it.readBytes() }
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
        OcrDictionary.load(context)
        _isReady = true
        Log.i(TAG, "CTC OCR model loaded (${modelBytes.size / 1024} KB)")
    }

    override suspend fun release() {
        session?.close()
        session = null
        _isReady = false
    }

    override suspend fun recognize(
        bitmap: Bitmap,
        textlines: List<Quadrilateral>,
        config: OcrConfig,
    ): List<Quadrilateral> {
        if (textlines.isEmpty() || session == null) return textlines
        val sess = session!!

        // 1. Extract regions
        val regions = textlines.map { q ->
            q.getTransformedRegion(bitmap, TextDirection.AUTO, TEXT_HEIGHT)
                ?: Bitmap.createBitmap(1, TEXT_HEIGHT, Bitmap.Config.ARGB_8888)
        }

        val results = textlines.toMutableList()

        // 2. Sort by width (matching Python reference -- all 4 OCR models do this)
        //    so similar-width regions are batched together, maximizing padding efficiency.
        val sortedIndices = regions.indices.sortedBy { regions[it].width }

        var batchStart = 0
        while (batchStart < sortedIndices.size) {
            // 2a. Dynamically size batch based on region widths to bound
            //     input-tensor memory. Input shape: [N, 3, 48, maxW] x 4 bytes/float.
            var batchEnd = batchStart
            var batchMaxW = 0
            while (batchEnd < sortedIndices.size && (batchEnd - batchStart) < MAX_CHUNK_SIZE) {
                val testW = regions[sortedIndices[batchEnd]].width
                val candidateMaxW = maxOf(batchMaxW, testW)
                val batchSize = batchEnd - batchStart + 1
                val estimatedInputBytes = batchSize.toLong() * 3L * TEXT_HEIGHT * candidateMaxW * 4L
                // Accept if within budget, or always accept at least 1 item
                if (estimatedInputBytes <= MAX_INPUT_TENSOR_BYTES || batchSize == 1) {
                    batchMaxW = candidateMaxW
                    batchEnd++
                } else {
                    break
                }
            }

            val batchIndices = sortedIndices.subList(batchStart, batchEnd)
            val N = batchIndices.size
            val widths = batchIndices.map { regions[it].width }
            val maxW = (4 * ((widths.maxOrNull() ?: 1) + 7) / 4) + 128

            // 2b. Build input tensor: [N, 3, 48, maxW], float32, normalized to [-1, 1]
            val tensorSize = N * 3 * TEXT_HEIGHT * maxW
            val floatBuf = FloatBuffer.allocate(tensorSize)
            val pixelBuf = IntArray(TEXT_HEIGHT * maxW)

            for (localIdx in 0 until N) {
                val bmp = regions[batchIndices[localIdx]]
                val w = bmp.width
                bmp.getPixels(pixelBuf, 0, maxW, 0, 0, w, TEXT_HEIGHT)
                if (w < maxW) {
                    // Pad right side with zeros
                    for (y in 0 until TEXT_HEIGHT) {
                        for (x in w until maxW) {
                            pixelBuf[y * maxW + x] = 0
                        }
                    }
                }

                for (y in 0 until TEXT_HEIGHT) {
                    for (x in 0 until maxW) {
                        val px = pixelBuf[y * maxW + x]
                        val r = ((px shr 16) and 0xFF) / 127.5f - 1f
                        val g = ((px shr 8) and 0xFF) / 127.5f - 1f
                        val b = (px and 0xFF) / 127.5f - 1f
                        val base = localIdx * 3 * TEXT_HEIGHT * maxW
                        floatBuf.put(base + 0 * TEXT_HEIGHT * maxW + y * maxW + x, r)
                        floatBuf.put(base + 1 * TEXT_HEIGHT * maxW + y * maxW + x, g)
                        floatBuf.put(base + 2 * TEXT_HEIGHT * maxW + y * maxW + x, b)
                    }
                }
            }

            // 3. Run ONNX inference
            val shape = longArrayOf(N.toLong(), 3L, TEXT_HEIGHT.toLong(), maxW.toLong())
            OnnxTensor.createTensor(env, floatBuf, shape).use { imgTensor ->
                val outputs = sess.run(mapOf("img" to imgTensor))
                val logits = outputs.get(0) as OnnxTensor
                val colors = (outputs.get(1) as OnnxTensor)

                val logitsBuf = logits.floatBuffer
                val colorsBuf = colors.floatBuffer
                val T = logits.info.shape[1].toInt()
                val D = logits.info.shape[2].toInt()
                val seqLen = T * D
                val colLen = T * 6

                // 4. CTC decode per sample -- read from FloatBuffer one timestep at a time.
                //
                //    CRITICAL: We use a reused FloatArray(D) buffer (~77KB for D=19264)
                //    instead of allocating FloatArray(TxD) per sample (~46MB for wide
                //    regions). This eliminates the primary OOM vector.
                val stepLogits = FloatArray(D)
                val stepColors = FloatArray(6)

                for (localIdx in 0 until N) {
                    val decoded = mutableListOf<Pair<Int, Float>>()
                    val keptSteps = mutableListOf<Int>()

                    // Greedy CTC decode: argmax per timestep
                    var lastId = OcrDictionary.BLANK
                    for (t in 0 until T) {
                        logitsBuf.position(localIdx * seqLen + t * D)
                        logitsBuf.get(stepLogits, 0, D)

                        var maxVal = Float.NEGATIVE_INFINITY
                        var maxIdx = OcrDictionary.BLANK
                        for (d in 0 until D) {
                            if (stepLogits[d] > maxVal) {
                                maxVal = stepLogits[d]
                                maxIdx = d
                            }
                        }
                        if (maxIdx != OcrDictionary.BLANK && maxIdx != lastId) {
                            decoded.add(maxIdx to maxVal)
                            keptSteps.add(t)
                        }
                        lastId = maxIdx
                    }

                    val text = OcrDictionary.ctcDecodeToText(decoded)

                    // Extract colors from FloatBuffer per kept timestep
                    var sumFr = 0f; var sumFg = 0f; var sumFb = 0f
                    var sumBr = 0f; var sumBg = 0f; var sumBb = 0f
                    for (t in keptSteps) {
                        colorsBuf.position(localIdx * colLen + t * 6)
                        colorsBuf.get(stepColors, 0, 6)
                        sumFr += stepColors[0]; sumFg += stepColors[1]; sumFb += stepColors[2]
                        sumBr += stepColors[3]; sumBg += stepColors[4]; sumBb += stepColors[5]
                    }
                    val numKept = keptSteps.size
                    fun clamp(v: Float) = (v * 255).toInt().coerceIn(0, 255)
                    val fg = if (numKept > 0) intArrayOf(clamp(sumFr / numKept), clamp(sumFg / numKept), clamp(sumFb / numKept))
                             else intArrayOf(0, 0, 0)
                    val bg = if (numKept > 0) intArrayOf(clamp(sumBr / numKept), clamp(sumBg / numKept), clamp(sumBb / numKept))
                             else intArrayOf(255, 255, 255)

                    val qIdx = batchIndices[localIdx]
                    results[qIdx] = results[qIdx].copy(
                        text = text,
                        fgColor = (0xFF shl 24) or (fg[0] shl 16) or (fg[1] shl 8) or fg[2],
                        bgColor = (0xFF shl 24) or (bg[0] shl 16) or (bg[1] shl 8) or bg[2],
                    )
                }
                outputs.close()
            }
            batchStart = batchEnd
        }

        return results.filterNot { it.text.isBlank() }
    }
}
