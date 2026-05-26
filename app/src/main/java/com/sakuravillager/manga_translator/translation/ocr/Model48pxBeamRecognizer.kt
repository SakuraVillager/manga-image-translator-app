package com.sakuravillager.manga_translator.translation.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.sakuravillager.manga_translator.data.logging.AppLogger
import com.sakuravillager.manga_translator.translation.api.TextRecognizer
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextDirection
import com.sakuravillager.manga_translator.translation.data.config.OcrConfig
import com.sakuravillager.manga_translator.translation.merge.quadrilateralCanMergeRegion
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.ArrayDeque

/**
 * Beam-search 48px OCR text recognizer using ONNX encoder + pure-Kotlin AR decoder.
 *
 * Pipeline:
 * 1. Download/load the encoder ONNX model (ocr_ar_48px_encoder.onnx)
 * 2. Load decoder weights for BeamSearchDecoder (embedding + transformer layers + pred heads)
 * 3. Perspective-crop each Quadrilateral to 48px height
 * 4. Sort regions by width, batch-pad to uniform width
 * 5. Pixel normalization: (pixel / 127.5f) - 1.0f → NCHW float32 [-1, 1]
 * 6. Encoder ONNX forward → memory + input_mask
 * 7. BeamSearchDecoder.decode(memory, mask, batch) → (tokenIds, probability)
 * 8. ArDictionary.decode(tokenIds) → text string
 * 9. ColorExtractor.extract(hiddenState) → CharacterColor
 * 10. Assemble results with original ordering
 *
 * Memory-safe design:
 * - Batch size is dynamically adjusted based on region widths to bound
 *   the input tensor allocation.
 * - Uses reusable FloatArray + IntArray buffers instead of per-sample allocations.
 * - Graceful no-op when decoder weights are not yet available.
 */
class Model48pxBeamRecognizer(
    private val ctx: Context?,
    private val modelDownloadManager: ModelDownloadManager? = null,
) : TextRecognizer {

    override val name: String = "Model48pxBeamRecognizer"

    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    // -- Model components (internal settable for test injection) --

    internal var encoderSession: OrtSession? = null
    internal var beamSearchDecoder: BeamSearchDecoder? = null
    internal var colorExtractor: ColorExtractor? = null

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    companion object {
        const val TEXT_HEIGHT = 48
        const val MAX_CHUNK_SIZE = 1
        const val TAG = "BeamRecognizer"

        /**
         * Maximum estimated bytes for the input tensor [N, 3, 48, maxW] × 4 bytes/float.
         * Same budget as the CTC recognizer.
         */
        private const val MAX_INPUT_TENSOR_BYTES = 10 * 1024 * 1024L

        /**
         * Normalize pixel to [-1, 1] range.
         * Matches Python model_48px.py:102: (pixel / 127.5) - 1.0
         */
        fun normalizePixel(px: Int): Triple<Float, Float, Float> {
            val r = ((px shr 16) and 0xFF) / 127.5f - 1.0f
            val g = ((px shr 8) and 0xFF) / 127.5f - 1.0f
            val b = (px and 0xFF) / 127.5f - 1.0f
            return Triple(r, g, b)
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    override suspend fun prepare() {
        val context = requireNotNull(ctx) { "Context required for prepare()" }
        AppLogger.i(TAG, "prepare() — loading encoder ONNX model ...")

        // 1. Load encoder ONNX model
        //    Use ModelDownloadManager where available; fall back to assets for dev/test.
        val modelFile = if (modelDownloadManager != null) {
            modelDownloadManager.ensureModel(ModelRegistry.OCR_AR_48PX_ENCODER)
        } else {
            File(context.filesDir, "models/ocr_ar_48px_encoder.onnx").also { f ->
                if (!f.exists()) {
                    try {
                        f.parentFile?.mkdirs()
                        context.assets.open("models/ocr_ar_48px_encoder.onnx").use { input ->
                            f.outputStream().use { output -> input.copyTo(output) }
                        }
                        AppLogger.i(TAG, "Loaded encoder from assets")
                    } catch (assetEx: Exception) {
                        throw java.io.IOException(
                            "Encoder ONNX model not found. " +
                                "Place ocr_ar_48px_encoder.onnx in assets/models/ or " +
                                "download to ${f.absolutePath}", assetEx
                        )
                    }
                }
            }
        }
        encoderSession = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())

        // Log encoder model I/O specs
        val sess = encoderSession!!
        AppLogger.i(TAG, "Encoder ONNX loaded (${modelFile.length() / 1024} KB)")
        AppLogger.i(TAG, "Encoder input names: ${sess.inputNames}")
        AppLogger.i(TAG, "Encoder output names: ${sess.outputNames}")
        for (name in sess.inputNames) {
            val info = sess.getInputInfo().getValue(name)
            AppLogger.i(TAG, "Encoder input '$name': $info")
        }

        // 2. Load decoder weights
        //    Production: load from serialised weight files exported from Python.
        //    Fallback: null (recognize() will no-op and return textlines unchanged).
        beamSearchDecoder = loadDecoderWeights(context)
        if (beamSearchDecoder == null) {
            AppLogger.w(TAG, "Decoder weights not available — recognize() will return textlines unchanged")
        }

        // 3. Initialise color extractor with real weights if available
        colorExtractor = ColorExtractor.createDefault()

        // 4. Load AR dictionary
        ArDictionary.load(context)
        AppLogger.i(TAG, "prepare() — ArDictionary loaded, size=${ArDictionary.size}")

        _isReady = true
        AppLogger.i(TAG, "prepare() — isReady=$_isReady, decoder=${beamSearchDecoder != null}")
    }

    override suspend fun release() {
        encoderSession?.close()
        encoderSession = null
        beamSearchDecoder = null
        colorExtractor = null
        _isReady = false
        AppLogger.i(TAG, "release() — all components released")
    }

    // ── Recognise ──────────────────────────────────────────────────────

    override suspend fun recognize(
        bitmap: Bitmap,
        textlines: List<Quadrilateral>,
        config: OcrConfig,
    ): List<Quadrilateral> {
        // Early return: no textlines or models not fully loaded
        if (textlines.isEmpty() || encoderSession == null) {
            AppLogger.i(TAG, "Early return: textlines=${textlines.size}, encoder=${encoderSession != null}")
            return textlines
        }
        if (beamSearchDecoder == null) {
            AppLogger.w(TAG, "recognize() — decoder not loaded, returning textlines unchanged")
            return textlines
        }

        val sess = encoderSession!!
        val decoder = beamSearchDecoder!!
        AppLogger.i(TAG, "recognize() — regions=${textlines.size}, img=${bitmap.width}x${bitmap.height}")

        // 1. Generate text directions (connected-component grouping)
        val grouped = generateTextDirections(textlines)
        textlines.forEachIndexed { i, q ->
            AppLogger.i(TAG, "quad[$i]: area=${"%.1f".format(q.area)} AR=${"%.2f".format(q.aspectRatio)} pts=${q.points.size} dir=${q.direction}")
        }

        // Optional debug directories
        val debugRoot = if (config.debugSaveCrops || config.debugSaveTokens) {
            File(requireNotNull(ctx) { "Context required for debug output" }.filesDir, "ocr_debug_beam_${System.currentTimeMillis()}")
        } else null
        debugRoot?.mkdirs()

        // 2. Extract perspective-cropped regions
        val regions = grouped.mapIndexed { gi, (origIdx, quad, direction) ->
            val cropDirection = when (direction) {
                TextDirection.VERTICAL -> TextDirection.VERTICAL
                TextDirection.HORIZONTAL_RTL, TextDirection.HORIZONTAL, TextDirection.AUTO -> TextDirection.HORIZONTAL
            }
            val crop = quad.getTransformedRegion(bitmap, cropDirection, TEXT_HEIGHT, debugRoot)
                ?: Bitmap.createBitmap(1, TEXT_HEIGHT, Bitmap.Config.ARGB_8888)
            if (gi < 3) {
                AppLogger.i(TAG, "crop[$origIdx]: dir=${direction} cropSize=${crop.width}x${crop.height}")
            }
            crop
        }

        if (config.debugSaveCrops && debugRoot != null) {
            for (i in regions.indices) {
                val origIndex = grouped[i].first
                val f = File(debugRoot, "region_${origIndex}_w${regions[i].width}.png")
                try {
                    FileOutputStream(f).use { out -> regions[i].compress(Bitmap.CompressFormat.PNG, 90, out) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save debug crop: ${e.message}")
                }
            }
        }

        val results = textlines.mapIndexed { index, quad -> quad.copy(sourceIndex = index) }.toMutableList()

        // 3. Sort by width (matching Python reference) for efficient batching
        val sortedIndices = regions.indices.sortedBy { regions[it].width }

        var batchStart = 0
        while (batchStart < sortedIndices.size) {
            // 3a. Dynamically size batch based on region widths
            var batchEnd = batchStart
            var batchMaxW = 0
            while (batchEnd < sortedIndices.size && (batchEnd - batchStart) < MAX_CHUNK_SIZE) {
                val testW = regions[sortedIndices[batchEnd]].width
                val candidateMaxW = maxOf(batchMaxW, testW)
                val batchSize = batchEnd - batchStart + 1
                val estimatedInputBytes = batchSize.toLong() * 3L * TEXT_HEIGHT * candidateMaxW * 4L
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
            // Python reference: max_width = (4 * (max(widths) + 7) // 4) + 128
            val maxW = 4 * ((widths.maxOrNull() ?: 1) + 7) / 4 + 128

            // 3b. Build input tensor [N, 3, 48, maxW] float32, normalized to [-1, 1]
            val tensorSize = N * 3 * TEXT_HEIGHT * maxW
            val floatBuf = FloatBuffer.allocate(tensorSize)
            val pixelBuf = IntArray(TEXT_HEIGHT * maxW)

            for (localIdx in 0 until N) {
                val bmp = regions[batchIndices[localIdx]]
                val w = bmp.width
                bmp.getPixels(pixelBuf, 0, maxW, 0, 0, w, TEXT_HEIGHT)

                // Zero-pad right side
                if (w < maxW) {
                    for (y in 0 until TEXT_HEIGHT) {
                        for (x in w until maxW) {
                            pixelBuf[y * maxW + x] = 0
                        }
                    }
                }

                // Write NCHW with [-1, 1] normalisation
                for (y in 0 until TEXT_HEIGHT) {
                    for (x in 0 until maxW) {
                        val px = pixelBuf[y * maxW + x]
                        val (r, g, b) = normalizePixel(px)
                        val base = localIdx * 3 * TEXT_HEIGHT * maxW
                        floatBuf.put(base + 0 * TEXT_HEIGHT * maxW + y * maxW + x, r)
                        floatBuf.put(base + 1 * TEXT_HEIGHT * maxW + y * maxW + x, g)
                        floatBuf.put(base + 2 * TEXT_HEIGHT * maxW + y * maxW + x, b)
                    }
                }
            }

            // 3c. Build image widths tensor [N] int64
            val imgWidthsData = LongArray(N) { idx -> widths[idx].toLong() }

            // 4. Run encoder ONNX
            val imgShape = longArrayOf(N.toLong(), 3L, TEXT_HEIGHT.toLong(), maxW.toLong())
            AppLogger.i(TAG, "Encoder input shape: N=$N, C=3, H=$TEXT_HEIGHT, W=$maxW")

            OnnxTensor.createTensor(env, floatBuf, imgShape).use { imgTensor ->
                OnnxTensor.createTensor(env, java.nio.LongBuffer.wrap(imgWidthsData), longArrayOf(N.toLong())).use { widthsTensor ->
                    val inputIter = sess.inputNames.iterator()
                    val inputMap = mutableMapOf<String, OnnxTensor>()
                    inputMap[inputIter.next()] = imgTensor
                    inputMap[inputIter.next()] = widthsTensor

                    val outputs = sess.run(inputMap)
                    val memoryTensor = outputs.get(0) as OnnxTensor
                    val maskTensor = outputs.get(1) as OnnxTensor

                    val memLen = memoryTensor.info.shape[1].toInt()
                    val dim = memoryTensor.info.shape[2].toInt()
                    AppLogger.i(TAG, "Encoder output: memory=[$N, $memLen, $dim], mask=${maskTensor.info.shape.contentToString()}")

                    // Read memory as flat FloatArray
                    val memSize = N * memLen * dim
                    val memoryArray = FloatArray(memSize)
                    memoryTensor.floatBuffer.get(memoryArray)

                    // Read mask as BooleanArray (bool tensor stored as bytes)
                    val maskSize = N * memLen
                    val maskBytes = ByteArray(maskSize)
                    maskTensor.byteBuffer.get(maskBytes)
                    val maskArray = BooleanArray(maskSize) { i -> maskBytes[i] != 0.toByte() }

                    // 5. Beam search decode
                    val decodedResults = decoder.decode(memoryArray, maskArray, N)

                    // 6. Process results: decode text + extract colors
                    for (localIdx in 0 until N) {
                        val (tokens, probability) = decodedResults[localIdx]
                        val text = ArDictionary.decode(tokens.toList())

                        // Color extraction with mock weights (real weights TBD)
                        val lastHidden = FloatArray(320) // placeholder until decoder exposes hidden states
                        val color = colorExtractor?.extract(lastHidden)
                            ?: CharacterColor(intArrayOf(0, 0, 0), intArrayOf(255, 255, 255), false, false)

                        val groupedIndex = batchIndices[localIdx]
                        val originalIndex = grouped[groupedIndex].first

                        val fgColor = if (color.hasFg) {
                            (0xFF shl 24) or (color.fgRgb[0] shl 16) or (color.fgRgb[1] shl 8) or color.fgRgb[2]
                        } else null
                        val bgColor = if (color.hasBg) {
                            (0xFF shl 24) or (color.bgRgb[0] shl 16) or (color.bgRgb[1] shl 8) or color.bgRgb[2]
                        } else null

                        results[originalIndex] = results[originalIndex].copy(
                            text = text,
                            probability = probability,
                            fgColor = fgColor,
                            bgColor = bgColor,
                        )

                        if (text.isBlank()) {
                            Log.d(TAG, "region#$originalIndex decoded as blank")
                        } else {
                            Log.d(TAG, "region#$originalIndex text='$text' prob=${"%.4f".format(probability)}")
                        }

                        // Debug token trace
                        if (config.debugSaveTokens && debugRoot != null) {
                            val tokenFile = File(debugRoot, "region_${originalIndex}_tokens.txt")
                            try {
                                tokenFile.writeText(tokens.joinToString("\n"))
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to write token file: ${e.message}")
                            }
                        }
                    }

                    outputs.close()
                }
            }
            batchStart = batchEnd
        }

        val refined = OcrPostProcessor.refine(results)
        val nonBlankCount = refined.count { it.text.isNotBlank() }
        AppLogger.i(TAG, "Done: ${refined.size} regions, nonBlank=$nonBlankCount")
        return refined
    }

    // ── Decoder weight loading ─────────────────────────────────────────

    /**
     * Loads decoder weights from serialised files.
     *
     * Expected files in [context.filesDir]/models/decoder/:
     *   - embedding.bin     : [dictSize × dim] float32
     *   - layer_{i}_*.bin   : per-layer weights
     *   - pred1_weight.bin  : Linear(320→320) + pred1_bias.bin
     *   - pred_weight.bin   : Linear(320→dictSize) + pred_bias.bin
     *
     * Returns null when weight files are not yet available (graceful no-op).
     */
    private fun loadDecoderWeights(context: Context): BeamSearchDecoder? {
        val decoderDir = File(context.filesDir, "models/decoder")
        if (!decoderDir.exists()) {
            AppLogger.w(TAG, "Decoder weights directory not found: ${decoderDir.absolutePath}")
            return null
        }

        return try {
            val dictSize = ArDictionary.size
            val dim = 320
            val numLayers = 5
            val ffDim = 2048

            // Load embedding [dictSize × dim]
            val embedding = loadFloatArray(File(decoderDir, "embedding.bin"), dictSize * dim)

            // Load per-layer weights
            val layerWeights = mutableListOf<DecoderLayerWeights>()
            for (i in 0 until numLayers) {
                layerWeights.add(loadDecoderLayerWeights(decoderDir, i, dim, ffDim))
            }

            // Load prediction heads
            val pred1Weight = loadFloatArray(File(decoderDir, "pred1_weight.bin"), dim * dim)
            val pred1Bias = loadFloatArray(File(decoderDir, "pred1_bias.bin"), dim)
            val predWeight = loadFloatArray(File(decoderDir, "pred_weight.bin"), dim * dictSize)
            val predBias = loadFloatArray(File(decoderDir, "pred_bias.bin"), dictSize)

            BeamSearchDecoder(
                embedding = embedding,
                decoderLayerWeights = layerWeights,
                pred1Weights = LinearWeights(pred1Weight, pred1Bias),
                predWeights = LinearWeights(predWeight, predBias),
                dim = dim,
                numHeads = 4,
                ffDim = ffDim,
                dictSize = dictSize,
                numLayers = numLayers,
                maxSeqLength = 384,
                beamK = 5,
                startTok = ArDictionary.START,
                endTok = ArDictionary.END,
                padTok = ArDictionary.PAD,
                maxFinishedHypos = 2,
            ).also {
                AppLogger.i(TAG, "Decoder weights loaded successfully: " +
                    "dictSize=$dictSize, dim=$dim, numLayers=$numLayers")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to load decoder weights: ${e.message}")
            null
        }
    }

    private fun loadDecoderLayerWeights(dir: File, layerIdx: Int, dim: Int, ffDim: Int): DecoderLayerWeights {
        val prefix = "layer_${layerIdx}_"

        fun load(name: String, size: Int): FloatArray = loadFloatArray(File(dir, "$prefix$name"), size)
        fun loadLinear(name: String, inDim: Int, outDim: Int): LinearWeights = LinearWeights(
            weight = load("${name}_weight.bin", inDim * outDim),
            bias = load("${name}_bias.bin", outDim),
        )
        fun loadLN(name: String, d: Int): LayerNormWeights = LayerNormWeights(
            weight = load("${name}_weight.bin", d),
            bias = load("${name}_bias.bin", d),
        )

        return DecoderLayerWeights(
            selfAttnQProj = loadLinear("self_attn_q_proj", dim, dim),
            selfAttnKProj = loadLinear("self_attn_k_proj", dim, dim),
            selfAttnVProj = loadLinear("self_attn_v_proj", dim, dim),
            selfAttnOutProj = loadLinear("self_attn_out_proj", dim, dim),
            crossAttnQProj = loadLinear("cross_attn_q_proj", dim, dim),
            crossAttnKProj = loadLinear("cross_attn_k_proj", dim, dim),
            crossAttnVProj = loadLinear("cross_attn_v_proj", dim, dim),
            crossAttnOutProj = loadLinear("cross_attn_out_proj", dim, dim),
            norm1 = loadLN("norm1", dim),
            norm2 = loadLN("norm2", dim),
            norm3 = loadLN("norm3", dim),
            ffLinear1 = loadLinear("ff_linear1", dim, ffDim),
            ffLinear2 = loadLinear("ff_linear2", ffDim, dim),
        )
    }

    private fun loadFloatArray(file: File, expectedSize: Int): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size == expectedSize * 4) {
            "File ${file.name}: expected ${expectedSize * 4} bytes, got ${bytes.size}"
        }
        val result = FloatArray(expectedSize)
        ByteBuffer.wrap(bytes).asFloatBuffer().get(result)
        return result
    }

    // ── Text direction grouping ────────────────────────────────────────

    /**
     * Groups quadrilaterals into connected components and assigns a shared
     * text direction per component. Mirrors Model48pxTextRecognizer.
     */
    private fun generateTextDirections(textlines: List<Quadrilateral>): List<Triple<Int, Quadrilateral, TextDirection>> {
        if (textlines.isEmpty()) return emptyList()

        val visited = BooleanArray(textlines.size)
        val output = mutableListOf<Triple<Int, Quadrilateral, TextDirection>>()

        for (startIndex in textlines.indices) {
            if (visited[startIndex]) continue

            val component = mutableListOf<Int>()
            val queue: ArrayDeque<Int> = ArrayDeque()
            queue.add(startIndex)
            visited[startIndex] = true

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                component.add(current)

                for (otherIndex in textlines.indices) {
                    if (visited[otherIndex]) continue
                    if (quadrilateralCanMergeRegion(textlines[current], textlines[otherIndex], aspectRatioTol = 1f)) {
                        visited[otherIndex] = true
                        queue.add(otherIndex)
                    }
                }
            }

            val directionCounts = component
                .map { textlines[it].direction }
                .filter { it != TextDirection.AUTO }
                .groupingBy { it }
                .eachCount()

            val majorityDirection = when {
                directionCounts.isEmpty() -> {
                    val horizontalScore = component.count { textlines[it].aspectRatio >= 1f }
                    val verticalScore = component.size - horizontalScore
                    if (verticalScore > horizontalScore) TextDirection.VERTICAL else TextDirection.HORIZONTAL
                }
                directionCounts.size == 1 -> directionCounts.keys.first()
                else -> {
                    val sortedCounts = directionCounts.entries.sortedByDescending { it.value }
                    if (sortedCounts.size == 1 || sortedCounts[0].value != sortedCounts[1].value) {
                        sortedCounts.first().key
                    } else {
                        val best = component.maxByOrNull {
                            maxOf(textlines[it].aspectRatio, if (textlines[it].aspectRatio == 0f) 0f else 1f / textlines[it].aspectRatio)
                        }
                        best?.let { textlines[it].direction.takeIf { dir -> dir != TextDirection.AUTO } }
                            ?: sortedCounts.first().key
                    }
                }
            }

            val sortedComponent = when (majorityDirection) {
                TextDirection.VERTICAL -> component.sortedByDescending { textlines[it].aabb.left + textlines[it].aabb.width() }
                else -> component.sortedBy { textlines[it].aabb.top + textlines[it].aabb.height() / 2f }
            }

            for (index in sortedComponent) {
                output.add(Triple(index, textlines[index], majorityDirection))
            }
        }

        return output
    }
}
