package com.sakuravillager.manga_translator.translation.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.data.DetectionResult
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextDirection
import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelRegistry
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import com.sakuravillager.manga_translator.translation.onnx.TensorConverter
import com.sakuravillager.manga_translator.translation.util.letterbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.MatOfPoint
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.sqrt

/**
 * ONNX-based Comic Text Detector (CTD) with DBNet postprocessing.
 *
 * Preprocesses the input bitmap via letterbox to 1024×1024, runs the ONNX model,
 * and decodes the output probability map using the DBNet pipeline:
 * binarize → findContours → minAreaRect → box_score_fast → unclip → Quadrilateral.
 */
class CtdTextDetector private constructor(
    private val modelDownloadManager: ModelDownloadManager,
    private val sessionManager: OnnxSessionManager,
    @Suppress("unused") private val context: Context,
) : TextDetector {

    override val name: String = "CtdTextDetector"

    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    private var session: OrtSession? = null
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    companion object {
        private const val TAG = "CtdTextDetector"
        const val INPUT_SIZE = 1024
        const val STRIDE = 64
        const val BINARY_THRESH = 0.3f
        const val DEFAULT_BOX_THRESH = 0.6f
        const val DEFAULT_UNCLIP_RATIO = 1.5f
        const val MAX_CANDIDATES = 1000
        private const val MINIMUM_IMAGE_SIZE = 400

        @Volatile private var instance: CtdTextDetector? = null

        fun getInstance(): CtdTextDetector {
            return instance ?: throw IllegalStateException(
                "CtdTextDetector not initialized. Call initialize() first."
            )
        }

        fun initialize(
            modelDownloadManager: ModelDownloadManager,
            sessionManager: OnnxSessionManager,
            context: Context,
        ): CtdTextDetector {
            return instance ?: synchronized(this) {
                instance ?: CtdTextDetector(modelDownloadManager, sessionManager, context).also { instance = it }
            }
        }
    }

    // -----------------------------------------------------------------------
    // PipelineModule
    // -----------------------------------------------------------------------

    override suspend fun prepare() {
        Log.d(TAG, "Preparing CtdTextDetector…")
        val modelFile = modelDownloadManager.ensureModel(ModelRegistry.CTD_MODEL)
        Log.d(TAG, "Model loaded (${modelFile.length()} bytes), creating ONNX session…")
        session = sessionManager.createSession(modelFile)
        _isReady = true
        Log.d(TAG, "CtdTextDetector ready")
    }

    override suspend fun release() {
        Log.d(TAG, "Releasing CtdTextDetector…")
        session?.let { sessionManager.closeSession(it) }
        session = null
        _isReady = false
        Log.d(TAG, "CtdTextDetector released")
    }

    // -----------------------------------------------------------------------
    // TextDetector
    // -----------------------------------------------------------------------

    override suspend fun detect(
        image: Bitmap,
        config: DetectorConfig,
    ): DetectionResult = withContext(Dispatchers.Default) {
        val sess = session ?: error("CtdTextDetector not prepared. Call prepare() first.")

        val srcW = image.width
        val srcH = image.height
        Log.d(TAG, "infer(${srcW}x${srcH}) start")

        val prepared = prepareInputBitmap(image, config)
        var preparedResult = detectPrepared(sess, prepared.bitmap, config)
        if (config.detAutoRotate) {
            val majorityHorizontal = preparedResult.textlines
                .map { if (it.aspectRatio > 1f) "h" else "v" }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key != "v"
            if (majorityHorizontal) {
                Log.i(TAG, "Rerunning detection with 90 degree rotation")
                val rotatedConfig = config.copy(detRotate = !config.detRotate, detAutoRotate = false)
                val rotatedPrepared = prepareInputBitmap(image, rotatedConfig)
                preparedResult = detectPrepared(sess, rotatedPrepared.bitmap, rotatedConfig)
                return@withContext restoreDetectionResult(preparedResult, rotatedPrepared, srcW, srcH)
            }
        }
        return@withContext restoreDetectionResult(preparedResult, prepared, srcW, srcH)

        // ---- 1. Letterbox to 1024×1024 ------------------------------------
        val lb = letterbox(image, INPUT_SIZE, STRIDE)
        val ratio = lb.ratio
        val dw = lb.dw
        val dh = lb.dh
        Log.d(TAG, "Letterbox: ratio=$ratio dw=$dw dh=$dh")

        // ---- 2. Convert to NCHW [0,1] float32 tensor -----------------------
        val tensor = TensorConverter.bitmapToNCHWTensor01(env, lb.bitmap, INPUT_SIZE, INPUT_SIZE)

        // ---- 3. ONNX inference ---------------------------------------------
        // The CTD ONNX model (comictextdetector.pt.onnx) uses "images" as the
        // input name. Read from session metadata for robustness.
        val inputName = sess.inputNames.iterator().next()
        val inputs = mapOf(inputName to tensor)
        val results = sess.run(inputs)

        try {
            // ---- 4. Extract outputs -----------------------------------------
            // Model outputs (by index):
            //   0 — blks (unused)
            //   1 — mask (sigmoid of detection kernel)
            //   2 — lines_map (channel 0 = shrink map for DBNet decoding)
            val maskTensor = results.get(1) as OnnxTensor
            val linesTensor = results.get(2) as OnnxTensor

            val maskInfo = maskTensor.info
            val linesInfo = linesTensor.info
            val maskShape = if (maskInfo is TensorInfo) maskInfo.shape
                else longArrayOf(1L, 1L, 1024L, 1024L)
            val linesShape = if (linesInfo is TensorInfo) linesInfo.shape
                else longArrayOf(1L, 2L, 1024L, 1024L)

            val maskH = maskShape[2].toInt()
            val maskW = maskShape[3].toInt()
            val linesH = linesShape[2].toInt()
            val linesW = linesShape[3].toInt()

            val maskBuf = maskTensor.floatBuffer
            val linesBuf = linesTensor.floatBuffer

            Log.d(TAG, "Output shapes — mask: ${maskShape.contentToString()}, lines: ${linesShape.contentToString()}")

            // ---- 5. Compute crop region (remove letterbox padding) -----------
            // Scale factors from model output space → INPUT_SIZE space
            val scaleY = INPUT_SIZE.toFloat() / linesH
            val scaleX = INPUT_SIZE.toFloat() / linesW
            val cropY = 0
            val cropX = 0
            val cropH = ((INPUT_SIZE - dh) / scaleY).toInt()
                .coerceAtMost(linesH - cropY)
            val cropW = ((INPUT_SIZE - dw) / scaleX).toInt()
                .coerceAtMost(linesW - cropX)

            // ---- 6. DBNet decode on lines_map channel 0 (shrink map) ---------
            val shrinkMat = Mat(linesH, linesW, CvType.CV_32FC1)
            try {
                val shrinkArray = FloatArray(linesH * linesW)
                linesBuf.rewind()
                linesBuf.get(shrinkArray, 0, linesH * linesW)
                shrinkMat.put(0, 0, shrinkArray)
            } finally {
                // linesBuf was from the tensor — no explicit release needed
            }

            // Crop to remove letterbox padding
            val croppedShrink = shrinkMat.submat(cropY, cropY + cropH, cropX, cropX + cropW)
            val quadsInCropSpace = dbnetDecode(croppedShrink)

            // ---- 7. Scale quads to original image dimensions ------------------
            // Coordinate mapping: cropped output → original
            //   x_orig = x_cropped * scaleX / ratio
            //   y_orig = y_cropped * scaleY / ratio
            val factorX = scaleX / ratio
            val factorY = scaleY / ratio

            val scaledQuads = quadsInCropSpace.map { quad ->
                val scaledPoints = quad.points.map { p ->
                    PointF(p.x * factorX, p.y * factorY)
                }
                val sortedPoints = Quadrilateral.sortPoints(scaledPoints).first
                val minX = sortedPoints.minOf { it.x }
                Quadrilateral(
                    points = sortedPoints,
                    text = "",
                    probability = quad.probability,
                )
            }

            Log.d(TAG, "DBNet produced ${quadsInCropSpace.size} quads")

            // ---- 8. Create mask bitmap ----------------------------------------
            val maskBitmap = buildMaskBitmap(
                maskBuf, maskH, maskW,
                INPUT_SIZE,
                dw, dh,
                srcW, srcH,
            )

            DetectionResult(scaledQuads, maskBitmap, null)
        } finally {
            results.close()
            tensor.close()
        }
    }

    private data class PreparedInput(
        val bitmap: Bitmap,
        val addedBorder: Boolean,
        val rotated: Boolean,
        val originalWidth: Int,
        val originalHeight: Int,
    )

    private fun prepareInputBitmap(image: Bitmap, config: DetectorConfig): PreparedInput {
        var bitmap = image
        val originalWidth = image.width
        val originalHeight = image.height
        var rotated = false

        if (config.detRotate) {
            val matrix = Matrix().apply { postRotate(90f) }
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            rotated = true
        }

        val addedBorder = minOf(bitmap.width, bitmap.height) < MINIMUM_IMAGE_SIZE
        if (addedBorder) {
            val side = maxOf(bitmap.width, bitmap.height, MINIMUM_IMAGE_SIZE)
            val bordered = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(bordered).drawBitmap(bitmap, 0f, 0f, null)
            bitmap = bordered
        }

        if (config.detInvert || config.detGammaCorrect) {
            val mat = Mat()
            try {
                Utils.bitmapToMat(bitmap, mat)
                if (config.detInvert) Core.bitwise_not(mat, mat)
                if (config.detGammaCorrect) applyGammaCorrection(mat)
                val processed = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(mat, processed)
                bitmap = processed
            } finally {
                mat.release()
            }
        }

        return PreparedInput(bitmap, addedBorder, rotated, originalWidth, originalHeight)
    }

    private fun applyGammaCorrection(mat: Mat) {
        val gray = Mat()
        val floatMat = Mat()
        try {
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            val mean = Core.mean(gray).`val`[0].coerceAtLeast(1.0)
            val gamma = kotlin.math.ln(0.5 * 255.0) / kotlin.math.ln(mean)
            mat.convertTo(floatMat, CvType.CV_32FC4)
            Core.pow(floatMat, gamma, floatMat)
            floatMat.convertTo(mat, mat.type())
        } finally {
            gray.release()
            floatMat.release()
        }
    }

    private fun restoreDetectionResult(
        result: DetectionResult,
        prepared: PreparedInput,
        targetW: Int,
        targetH: Int,
    ): DetectionResult {
        var textlines = result.textlines
        var mask = result.rawMask

        if (prepared.addedBorder) {
            textlines = textlines.mapNotNull { quad ->
                val points = quad.points.map { p ->
                    PointF(
                        p.x.coerceIn(0f, prepared.originalWidth.toFloat()),
                        p.y.coerceIn(0f, prepared.originalHeight.toFloat()),
                    )
                }
                val clipped = quad.copy(points = points)
                if (clipped.area > 1f) clipped else null
            }
            mask = mask?.let { Bitmap.createBitmap(it, 0, 0, prepared.originalWidth, prepared.originalHeight) }
        }

        if (prepared.rotated) {
            textlines = textlines.map { quad ->
                val points = quad.points.map { p -> PointF(p.y, prepared.originalHeight - p.x) }
                quad.copy(points = Quadrilateral.sortPoints(points).first)
            }
            mask = mask?.let {
                val matrix = Matrix().apply { postRotate(-90f) }
                Bitmap.createBitmap(it, 0, 0, it.width, it.height, matrix, true)
            }
        }

        if (mask != null && (mask.width != targetW || mask.height != targetH)) {
            mask = Bitmap.createScaledBitmap(mask, targetW, targetH, true)
        }

        return DetectionResult(textlines.filter { it.area > 1f }, mask, result.mask)
    }

    private fun detectPrepared(
        sess: OrtSession,
        image: Bitmap,
        config: DetectorConfig,
    ): DetectionResult {
        val inputSize = config.detectionSize.takeIf { it > 0 } ?: INPUT_SIZE
        val boxThreshold = config.boxThreshold.takeIf { it > 0f } ?: DEFAULT_BOX_THRESH
        val unclipRatio = config.unclipRatio.takeIf { it > 0f } ?: DEFAULT_UNCLIP_RATIO
        val srcW = image.width
        val srcH = image.height

        val lb = letterbox(image, inputSize, STRIDE)
        val ratio = lb.ratio
        val dw = lb.dw
        val dh = lb.dh
        Log.d(TAG, "detectPrepared letterbox: inputSize=$inputSize ratio=$ratio dw=$dw dh=$dh srcW=$srcW srcH=$srcH")

        val tensor = TensorConverter.bitmapToNCHWTensor01(env, lb.bitmap, inputSize, inputSize)
        val inputName = sess.inputNames.iterator().next()
        val results = sess.run(mapOf(inputName to tensor))

        try {
            // Log all ONNX output names/shapes for diagnosis
            for (i in 0 until results.size()) {
                val name = results.get(i)?.javaClass?.simpleName ?: "null"
                val info = if (results.get(i) is OnnxTensor) (results.get(i) as OnnxTensor).info.toString() else "?"
                Log.d(TAG, "detectPrepared output[$i]: $name — $info")
            }

            val maskTensor = results.get(1) as OnnxTensor
            val linesTensor = results.get(2) as OnnxTensor
            val maskInfo = maskTensor.info
            val linesInfo = linesTensor.info
            val maskShape = if (maskInfo is TensorInfo) maskInfo.shape else longArrayOf(1L, 1L, inputSize.toLong(), inputSize.toLong())
            val linesShape = if (linesInfo is TensorInfo) linesInfo.shape else longArrayOf(1L, 2L, inputSize.toLong(), inputSize.toLong())
            val maskH = maskShape[2].toInt()
            val maskW = maskShape[3].toInt()
            val linesH = linesShape[2].toInt()
            val linesW = linesShape[3].toInt()

            val scaleY = inputSize.toFloat() / linesH
            val scaleX = inputSize.toFloat() / linesW
            val cropY = 0
            val cropX = 0
            val cropH = ((inputSize - dh) / scaleY).toInt().coerceAtMost(linesH)
            val cropW = ((inputSize - dw) / scaleX).toInt().coerceAtMost(linesW)
            Log.d(TAG, "detectPrepared crop: cropY=$cropY cropX=$cropX cropH=$cropH cropW=$cropW linesH=$linesH linesW=$linesW")

            // Log lines tensor value range to confirm model predictions
            linesTensor.floatBuffer.rewind()
            val sampleSize = minOf(1000, linesH * linesW)
            val sampleBuf = FloatArray(sampleSize)
            linesTensor.floatBuffer.get(sampleBuf, 0, sampleSize)
            val sMin = sampleBuf.min()
            val sMax = sampleBuf.max()
            val sMean = sampleBuf.average()
            Log.d(TAG, "detectPrepared lines tensor stats (n=$sampleSize): min=$sMin max=$sMax mean=$sMean")
            linesTensor.floatBuffer.rewind()

            val shrinkMat = Mat(linesH, linesW, CvType.CV_32FC1)
            val croppedShrink: Mat
            try {
                val shrinkArray = FloatArray(linesH * linesW)
                linesTensor.floatBuffer.rewind()
                linesTensor.floatBuffer.get(shrinkArray, 0, linesH * linesW)
                shrinkMat.put(0, 0, shrinkArray)
                croppedShrink = shrinkMat.submat(cropY, cropY + cropH, cropX, cropX + cropW)
            } catch (e: Exception) {
                shrinkMat.release()
                throw e
            }

            val quadsInCropSpace = try {
                dbnetDecode(croppedShrink, config.textThreshold, boxThreshold, unclipRatio)
            } finally {
                croppedShrink.release()
                shrinkMat.release()
            }

            val factorX = scaleX / ratio
            val factorY = scaleY / ratio
            val scaledQuads = quadsInCropSpace.map { quad ->
                val scaledPoints = quad.points.map { p -> PointF(p.x * factorX, p.y * factorY) }
                quad.copy(points = Quadrilateral.sortPoints(scaledPoints).first)
            }

            val maskBitmap = buildMaskBitmap(maskTensor.floatBuffer, maskH, maskW, inputSize, dw, dh, srcW, srcH)

            // Refine mask (Python ctd.py L177: refine_mask(image, mask, textlines))
            val refinedBitmap = if (maskBitmap != null && scaledQuads.isNotEmpty()) {
                val imageMat = Mat()
                Utils.bitmapToMat(image, imageMat)
                val rawMaskGray = Mat()
                Utils.bitmapToMat(maskBitmap, rawMaskGray)
                Imgproc.cvtColor(rawMaskGray, rawMaskGray, Imgproc.COLOR_RGBA2GRAY)
                try {
                    val refinedMat = refineMask(imageMat, rawMaskGray, scaledQuads)
                    val refined = Bitmap.createBitmap(refinedMat.cols(), refinedMat.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(refinedMat, refined)
                    refinedMat.release()
                    refined
                } catch (e: Exception) {
                    Log.w(TAG, "Mask refinement failed, using raw mask", e)
                    maskBitmap
                } finally {
                    imageMat.release()
                    rawMaskGray.release()
                }
            } else {
                maskBitmap
            }

            return DetectionResult(scaledQuads, refinedBitmap, null)
        } finally {
            results.close()
            tensor.close()
        }
    }

    // -----------------------------------------------------------------------
    // DBNet decode
    // -----------------------------------------------------------------------

    /**
     * Runs the full DBNet decoding pipeline on the [shrinkMap] (CV_32FC1).
     *
     * 1. Binarize at [BINARY_THRESH] (0.3)
     * 2. [findContours] on the binary mask
     * 3. For each contour (up to [MAX_CANDIDATES]):
     *    - [minAreaRect] → 4 rotated rect points
     *    - [boxScoreFast] on the original prediction values
     *    - score > [BOX_THRESH] (0.6) ? keep
     *    - [unclipPolygon]: expand polygon by area × [UNCLIP_RATIO] / perimeter
     *    - [minAreaRect] of expanded → 4 points
     *    - Build [Quadrilateral]
     *
     * Coordinates are relative to [shrinkMap]'s pixel grid.
     */
    private fun dbnetDecode(
        shrinkMap: Mat,
        binaryThreshold: Float = BINARY_THRESH,
        boxThreshold: Float = DEFAULT_BOX_THRESH,
        unclipRatio: Float = DEFAULT_UNCLIP_RATIO,
    ): List<Quadrilateral> {
        val h = shrinkMap.rows()
        val w = shrinkMap.cols()

        // 1. Binarize
        val binary = Mat()
        try {
            Imgproc.threshold(
                shrinkMap, binary,
                binaryThreshold.toDouble(), 255.0,
                Imgproc.THRESH_BINARY,
            )
            binary.convertTo(binary, CvType.CV_8UC1)
        } catch (e: Exception) {
            Log.w(TAG, "Binarize failed", e)
            return emptyList()
        }

        // 2. findContours
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Imgproc.findContours(
                binary, contours, hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "findContours failed", e)
            return emptyList()
        } finally {
            hierarchy.release()
            binary.release()
        }

        Log.d(TAG, "dbnetDecode: ${contours.size} contours found")
        var filteredAreaCount = 0
        var filteredScoreCount = 0
        var filteredUnclipCount = 0
        var filteredQuadCount = 0

        val results = mutableListOf<Quadrilateral>()

        for (contour in contours) {
            var point2f: MatOfPoint2f? = null
            var expanded: MatOfPoint? = null
            var expandedP2f: MatOfPoint2f? = null
            try {
                if (results.size >= MAX_CANDIDATES) { continue }

                val area = Imgproc.contourArea(contour)
                if (area < 3.0) { filteredAreaCount++; continue }

                // 3. minAreaRect
                point2f = MatOfPoint2f(*contour.toArray())
                val rect = Imgproc.minAreaRect(point2f)
                val rectPoints = Array(4) { Point() }
                rect.points(rectPoints)

                // 4. box_score_fast: mean of pred values inside rotated rect
                val score = boxScoreFast(shrinkMap, rectPoints, h, w)
                if (score < boxThreshold) { filteredScoreCount++; Log.d(TAG, "dbnetDecode: contour score=$score < thresh=$boxThreshold, filtered"); continue }

                // 5. Unclip polygon
                val perimeter = Imgproc.arcLength(point2f, true)
                val unclipDist = area * unclipRatio / maxOf(perimeter, 1.0)
                expanded = unclipPolygon(contour, unclipDist)

                if (expanded.size().area() < 3.0) { filteredUnclipCount++; continue }

                // 6. minAreaRect of expanded polygon
                expandedP2f = MatOfPoint2f(*expanded.toArray())
                val expandedRect = Imgproc.minAreaRect(expandedP2f)
                val expandedPts = Array(4) { Point() }
                expandedRect.points(expandedPts)

                val pts = expandedPts.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                // Ensure valid quadrilateral (non-zero area)
                val quad = Quadrilateral(points = pts, text = "", probability = score)
                if (quad.area < 1f) { filteredQuadCount++; continue }

                results.add(quad)
            } catch (e: Exception) {
                Log.w(TAG, "Contour processing failed", e)
            } finally {
                // Always release all Mats — each is null-safe and exception-safe
                point2f?.let { try { it.release() } catch (_: Exception) {} }
                expanded?.let { try { it.release() } catch (_: Exception) {} }
                expandedP2f?.let { try { it.release() } catch (_: Exception) {} }
                try { contour.release() } catch (_: Exception) {}
            }
        }

        Log.d(TAG, "dbnetDecode: ${results.size} quads, filtered: area=$filteredAreaCount score=$filteredScoreCount unclip=$filteredUnclipCount quad=$filteredQuadCount")
        return results
    }

    // -----------------------------------------------------------------------
    // box_score_fast
    // -----------------------------------------------------------------------

    /**
     * Computes the mean value of [predMap] (CV_32FC1) within the rotated
     * rectangle defined by [rectPoints].  Mirrors the Python
     * `box_score_fast()` in `db_utils.py`.
     */
    private fun boxScoreFast(
        predMap: Mat,
        rectPoints: Array<Point>,
        h: Int,
        w: Int,
    ): Float {
        val xs = rectPoints.map { it.x }
        val ys = rectPoints.map { it.y }
        val xmin = maxOf(0.0, xs.min()).toInt().coerceAtMost(w - 1)
        val xmax = maxOf(0.0, xs.max()).toInt().coerceAtMost(w - 1)
        val ymin = maxOf(0.0, ys.min()).toInt().coerceAtMost(h - 1)
        val ymax = maxOf(0.0, ys.max()).toInt().coerceAtMost(h - 1)

        val roiW = xmax - xmin + 1
        val roiH = ymax - ymin + 1
        if (roiW <= 0 || roiH <= 0) return 0f

        // Create mask for the rotated rectangle within the ROI
        val mask = Mat.zeros(roiH, roiW, CvType.CV_8UC1)
        try {
            val shifted = rectPoints.map { p ->
                Point(p.x - xmin, p.y - ymin)
            }
            val maskContour = MatOfPoint(*shifted.toTypedArray())
            try {
                Imgproc.fillPoly(mask, listOf(maskContour), Scalar(1.0))
            } finally {
                maskContour.release()
            }

            val roi = predMap.submat(ymin, ymax + 1, xmin, xmax + 1)
            try {
                val meanVal = Core.mean(roi, mask)
                return meanVal.`val`[0].toFloat()
            } finally {
                roi.release()
            }
        } finally {
            mask.release()
        }
    }

    // -----------------------------------------------------------------------
    // unclip (centroid scaling)
    // -----------------------------------------------------------------------

    /**
     * Expands [contour] outward by [distance] pixels along centroid→vertex
     * rays.  Falls back to centroid scaling when the contour has too few
     * vertices for a meaningful expansion.
     */
    private fun unclipPolygon(contour: MatOfPoint, distance: Double): MatOfPoint {
        val points = contour.toArray()
        if (points.size < 3) {
            return MatOfPoint(*points.map { it.clone() }.toTypedArray())
        }

        // Centroid (mass center)
        val moments = Imgproc.moments(contour)
        val cx = moments.m10 / maxOf(moments.m00, 1e-6)
        val cy = moments.m01 / maxOf(moments.m00, 1e-6)

        val expandedPoints = points.map { p ->
            val dx = p.x - cx
            val dy = p.y - cy
            val len = sqrt(dx * dx + dy * dy)
            if (len < 1e-6) {
                Point(p.x, p.y)
            } else {
                Point(
                    p.x + dx / len * distance,
                    p.y + dy / len * distance,
                )
            }
        }

        return MatOfPoint(*expandedPoints.toTypedArray())
    }

    // -----------------------------------------------------------------------
    // Mask bitmap
    // -----------------------------------------------------------------------

    /**
     * Builds a [Bitmap] mask from the model's mask output.
     *
     * 1. Extracts channel 0 from [maskBuf] → [maskH]×[maskW] float Mat
     * 2. Resizes to [inputSize]×[inputSize]
     * 3. Crops the letterbox padding
     * 4. Scales to 0–255, CV_8UC1
     * 5. Resizes to [targetW]×[targetH]
     * 6. Converts to ARGB_8888 [Bitmap]
     */
    private fun buildMaskBitmap(
        maskBuf: FloatBuffer,
        maskH: Int,
        maskW: Int,
        inputSize: Int,
        dw: Int,
        dh: Int,
        targetW: Int,
        targetH: Int,
    ): Bitmap? {
        if (maskH <= 0 || maskW <= 0) return null

        val maskMat = Mat(maskH, maskW, CvType.CV_32FC1)
        try {
            val maskArray = FloatArray(maskH * maskW)
            maskBuf.rewind()
            maskBuf.get(maskArray, 0, maskH * maskW)
            maskMat.put(0, 0, maskArray)

            // Resize to INPUT_SIZE if needed
            val maskResized: Mat = if (maskH != inputSize || maskW != inputSize) {
                val r = Mat()
                Imgproc.resize(maskMat, r, Size(inputSize.toDouble(), inputSize.toDouble()))
                r
            } else {
                maskMat
            }

            // Crop letterbox padding (bottom-right aligned, matching Python CTD)
            val cropY = 0
            val cropX = 0
            val cropH = (inputSize - dh).coerceAtLeast(1)
            val cropW = (inputSize - dw).coerceAtLeast(1)
            val cropped = maskResized.submat(cropY, cropY + cropH, cropX, cropX + cropW)

            // Scale to 0–255 uint8
            val mask8u = Mat()
            cropped.convertTo(mask8u, CvType.CV_8UC1, 255.0)

            // Resize to original image dimensions
            val maskFinal = Mat()
            Imgproc.resize(mask8u, maskFinal, Size(targetW.toDouble(), targetH.toDouble()))

            val bitmap = Bitmap.createBitmap(
                maskFinal.cols(), maskFinal.rows(), Bitmap.Config.ARGB_8888,
            )
            Utils.matToBitmap(maskFinal, bitmap)

            mask8u.release()
            if (maskResized != maskMat) maskResized.release()
            maskFinal.release()

            return bitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build mask bitmap", e)
            return null
        } finally {
            maskMat.release()
        }
    }

    // -----------------------------------------------------------------------
    // Mask refinement (Python textmask.py L158-174)
    // -----------------------------------------------------------------------

    /**
     * Refines a raw prediction mask using per-textline histogram analysis,
     * Otsu thresholding, and XOR-based matching.
     *
     * Port of Python `textmask.py::refine_mask()`.
     */
    private fun refineMask(
        image: Mat,
        predMask: Mat,
        textlines: List<Quadrilateral>,
    ): Mat {
        val imgH = image.rows()
        val imgW = image.cols()
        val maskRefined = Mat.zeros(imgH, imgW, CvType.CV_8UC1)

        val gray = Mat()
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)

        for (blk in textlines) {
            val pts = blk.points
            if (pts.size < 4) continue
            val xyxy = floatArrayOf(
                pts.minOf { it.x }, pts.minOf { it.y },
                pts.maxOf { it.x }, pts.maxOf { it.y },
            )
            val enlarged = enlargeWindow(xyxy, imgW, imgH)
            val bx1 = enlarged[0]
            val by1 = enlarged[1]
            val bx2 = enlarged[2]
            val by2 = enlarged[3]
            if (bx2 <= bx1 || by2 <= by1) continue
            if (bx1 >= imgW || by1 >= imgH || bx2 <= 0 || by2 <= 0) continue

            val imSub = Mat(gray, org.opencv.core.Rect(bx1, by1, bx2 - bx1, by2 - by1))
            val mskSub = Mat(predMask, org.opencv.core.Rect(bx1, by1, bx2 - bx1, by2 - by1))

            // Gather candidates: histogram-based + Otsu-based
            val maskList = mutableListOf<Pair<Mat, Long>>()
            maskList.addAll(getTopkMasklist(imSub, mskSub))
            maskList.addAll(getOtsuthreshMasklist(image, predMask, bx1, by1, bx2 - bx1, by2 - by1, mskSub))

            // Merge candidates with XOR matching + hole filling + dilation
            val maskMerged = mergeMaskList(maskList, mskSub)

            // Bitwise OR into result
            val resultSub = Mat(maskRefined, org.opencv.core.Rect(bx1, by1, bx2 - bx1, by2 - by1))
            Core.bitwise_or(resultSub, maskMerged, resultSub)
            maskMerged.release()
        }

        gray.release()
        return maskRefined
    }

    /**
     * Expands a bounding box using the Python enlarge_window formula.
     * (Python imgproc_utils.py L134-150)
     */
    private fun enlargeWindow(
        xyxy: FloatArray,
        imW: Int,
        imH: Int,
        ratio: Double = 2.5,
        aspectRatio: Double = 1.0,
    ): IntArray {
        val x1 = xyxy[0].toInt().coerceIn(0, imW)
        val y1 = xyxy[1].toInt().coerceIn(0, imH)
        val x2 = xyxy[2].toInt().coerceIn(0, imW)
        val y2 = xyxy[3].toInt().coerceIn(0, imH)
        val w = (x2 - x1).coerceAtLeast(1)
        val h = (y2 - y1).coerceAtLeast(1)

        // Quadratic: aspectRatio*x^2 + (w+h*aspectRatio)*x + (1-ratio)*w*h = 0
        val a = aspectRatio
        val b = w + h * aspectRatio
        val c = (1.0 - ratio) * w * h
        val disc = b * b - 4 * a * c
        val delta = if (disc >= 0) {
            val root = (-b + sqrt(disc)) / (2 * a)
            (root / 2.0).toInt().coerceAtLeast(0)
        } else 0
        val deltaW = (delta * aspectRatio).toInt()

        val dw = minOf(x1, imW - x2, deltaW)
        val dh = minOf(y1, imH - y2, delta)
        return intArrayOf(
            (x1 - dw).coerceAtLeast(0), (y1 - dh).coerceAtLeast(0),
            (x2 + dw).coerceAtMost(imW), (y2 + dh).coerceAtMost(imH),
        )
    }

    /**
     * Selects forward or inverted threshold by minimum XOR distance to pred_mask.
     * (Python textmask.py L29-41)
     */
    private fun minxorThresh(threshed: Mat, mask: Mat, dilate: Boolean): Pair<Mat, Long> {
        val negThreshed = Mat()
        Core.bitwise_not(threshed, negThreshed)

        if (dilate) {
            val el = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val tmp1 = negThreshed.clone()
            val tmp2 = threshed.clone()
            Imgproc.dilate(negThreshed, tmp1, el)
            Imgproc.dilate(threshed, tmp2, el)
            el.release()
            val negXor = Mat()
            val posXor = Mat()
            Core.bitwise_xor(tmp1, mask, negXor)
            Core.bitwise_xor(tmp2, mask, posXor)
            val negSum = Core.countNonZero(negXor).toLong()
            val posSum = Core.countNonZero(posXor).toLong()
            negXor.release(); posXor.release(); tmp1.release(); tmp2.release()
            return if (negSum < posSum) Pair(negThreshed, negSum) else { negThreshed.release(); Pair(threshed.clone(), posSum) }
        } else {
            val negXor = Mat()
            val posXor = Mat()
            Core.bitwise_xor(negThreshed, mask, negXor)
            Core.bitwise_xor(threshed, mask, posXor)
            val negSum = Core.countNonZero(negXor).toLong()
            val posSum = Core.countNonZero(posXor).toLong()
            negXor.release(); posXor.release()
            return if (negSum < posSum) Pair(negThreshed, negSum) else { negThreshed.release(); Pair(threshed.clone(), posSum) }
        }
    }

    /**
     * Generates mask candidates from grayscale histogram top-k colors.
     * (Python textmask.py L56-71)
     */
    private fun getTopkMasklist(imGray: Mat, predMask: Mat): List<Pair<Mat, Long>> {
        val eroded = Mat()
        Imgproc.erode(predMask, eroded, Mat.ones(3, 3, CvType.CV_8UC1))

        val grayBytes = ByteArray(eroded.rows() * eroded.cols())
        eroded.get(0, 0, grayBytes)
        val pixels = mutableListOf<Int>()
        for (i in grayBytes.indices) {
            if ((grayBytes[i].toInt() and 0xFF) > 127) {
                pixels.add(i)
            }
        }

        val hist = IntArray(256)
        val imgBytes = ByteArray(imGray.rows() * imGray.cols())
        imGray.get(0, 0, imgBytes)
        for (idx in pixels) {
            hist[imgBytes[idx].toInt() and 0xFF]++
        }

        // Top-k colors (k=3, color_var=10)
        val indexed = hist.mapIndexed { i, v -> i to v }.sortedByDescending { it.second }
        val topColors = mutableListOf<Int>()
        val binTol = (pixels.size * 0.001).toLong()
        for ((color, bin) in indexed) {
            if (topColors.isNotEmpty() && topColors.all { kotlin.math.abs(it - color) < 10 }) {
                if (topColors.isNotEmpty()) continue
            } else {
                topColors.add(color)
            }
            if (topColors.size >= 3 || bin < binTol) break
        }

        val result = mutableListOf<Pair<Mat, Long>>()
        val colorRange = 30
        for (color in topColors) {
            val cTop = minOf(color + colorRange, 255)
            val cBottom = cTop - 2 * colorRange
            val threshed = Mat()
            Core.inRange(imGray, Scalar(cBottom.toDouble()), Scalar(cTop.toDouble()), threshed)
            val (mask, xor) = minxorThresh(threshed, predMask, false)
            threshed.release()
            result.add(Pair(mask, xor))
        }

        eroded.release()
        return result
    }

    /**
     * Generates mask candidates from per-channel Otsu thresholding.
     * (Python textmask.py L43-54)
     */
    private fun getOtsuthreshMasklist(
        image: Mat,
        predMask: Mat,
        x: Int, y: Int, w: Int, h: Int,
        mskSub: Mat,
    ): List<Pair<Mat, Long>> {
        val channels = mutableListOf<Mat>()
        Core.extractChannel(image, channels.also { it.add(Mat()) }.last(), 0) // B
        Core.extractChannel(image, channels.also { it.add(Mat()) }.last(), 1) // G
        Core.extractChannel(image, channels.also { it.add(Mat()) }.last(), 2) // R

        val result = mutableListOf<Pair<Mat, Long>>()
        for (ch in channels) {
            val chSub = Mat(ch, org.opencv.core.Rect(x, y, w, h))
            val threshed = Mat()
            Imgproc.threshold(chSub, threshed, 1.0, 255.0, Imgproc.THRESH_OTSU or Imgproc.THRESH_BINARY)
            val (mask, xor) = minxorThresh(threshed, mskSub, false)
            threshed.release()
            chSub.release()
            result.add(Pair(mask, xor))
            ch.release()
        }

        result.sortBy { it.second }
        return listOf(result.first())
    }

    /**
     * Merges mask candidates via CC-level XOR matching + hole filling + dilation.
     * (Python textmask.py L73-132)
     */
    private fun mergeMaskList(
        maskList: List<Pair<Mat, Long>>,
        predMask: Mat,
    ): Mat {
        val sorted = maskList.sortedBy { it.second }

        // Erode pred_mask (pred_thresh=30 matching Python REFINEMASK_INPAINT)
        val eSize = 1
        val el = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size((2 * eSize + 1).toDouble(), (2 * eSize + 1).toDouble()))
        val erodedPred = Mat()
        Imgproc.erode(predMask, erodedPred, el)
        Imgproc.threshold(erodedPred, erodedPred, 60.0, 255.0, Imgproc.THRESH_BINARY)
        el.release()

        val maskMerged = Mat.zeros(predMask.rows(), predMask.cols(), CvType.CV_8UC1)
        val connectivity = 8

        for ((candidateMask, _) in sorted) {
            val labels = Mat()
            val stats = Mat()
            val centroids = Mat()
            val numLabels = Imgproc.connectedComponentsWithStats(candidateMask, labels, stats, centroids, connectivity, CvType.CV_16U)

            for (lab in 1 until numLabels) {
                val lx = stats.get(lab, Imgproc.CC_STAT_LEFT)[0].toInt()
                val ly = stats.get(lab, Imgproc.CC_STAT_TOP)[0].toInt()
                val lw = stats.get(lab, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val lh = stats.get(lab, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                if (lw * lh < 3) continue

                val x1 = lx.coerceAtLeast(0)
                val y1 = ly.coerceAtLeast(0)
                val x2 = (lx + lw).coerceAtMost(candidateMask.cols())
                val y2 = (ly + lh).coerceAtMost(candidateMask.rows())
                if (x2 <= x1 || y2 <= y1) continue

                val labSub = Mat(labels, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1))
                val tmpLocal = Mat.zeros(y2 - y1, x2 - x1, CvType.CV_8UC1)
                val eqM = Mat()
                Core.compare(labSub, Scalar(lab.toDouble()), eqM, Core.CMP_EQ)
                tmpLocal.setTo(Scalar(255.0), eqM)
                eqM.release()

                val tmpMerged = Mat()
                val mergedSub = Mat(maskMerged, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1))
                Core.bitwise_or(mergedSub, tmpLocal, tmpMerged)

                val predSub = Mat(erodedPred, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1))
                val xorNew = Mat()
                Core.bitwise_xor(tmpMerged, predSub, xorNew)
                val newSum = Core.countNonZero(xorNew).toLong()

                val xorOld = Mat()
                Core.bitwise_xor(mergedSub, predSub, xorOld)
                val oldSum = Core.countNonZero(xorOld).toLong()

                if (newSum < oldSum) {
                    tmpMerged.copyTo(mergedSub)
                }

                xorNew.release(); xorOld.release(); tmpMerged.release(); tmpLocal.release()
            }
            labels.release(); stats.release(); centroids.release()
        }

        erodedPred.release()

        // REFINEMASK_INPAINT: dilate 5x5
        val dilateKernel = Mat.ones(5, 5, CvType.CV_8UC1)
        Imgproc.dilate(maskMerged, maskMerged, dilateKernel)
        dilateKernel.release()

        // Fill holes
        val inverted = Mat()
        Core.bitwise_not(maskMerged, inverted)
        val holeLabels = Mat()
        val holeStats = Mat()
        val holeCentroids = Mat()
        val numHoleLabels = Imgproc.connectedComponentsWithStats(inverted, holeLabels, holeStats, holeCentroids, connectivity, CvType.CV_16U)
        inverted.release()

        if (numHoleLabels > 1) {
            val areas = (1 until numHoleLabels).map { holeStats.get(it, Imgproc.CC_STAT_AREA)[0].toInt() }
            val areaThresh = areas.max()

            for (lab in 1 until numHoleLabels) {
                val area = holeStats.get(lab, Imgproc.CC_STAT_AREA)[0].toInt()
                if (area >= areaThresh) continue

                val lx = holeStats.get(lab, Imgproc.CC_STAT_LEFT)[0].toInt()
                val ly = holeStats.get(lab, Imgproc.CC_STAT_TOP)[0].toInt()
                val lw = holeStats.get(lab, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val lh = holeStats.get(lab, Imgproc.CC_STAT_HEIGHT)[0].toInt()

                val x1 = lx.coerceAtLeast(0)
                val y1 = ly.coerceAtLeast(0)
                val x2 = (lx + lw).coerceAtMost(maskMerged.cols())
                val y2 = (ly + lh).coerceAtMost(maskMerged.rows())
                if (x2 <= x1 || y2 <= y1) continue

                val labSub = Mat(holeLabels, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1))
                val tmpLocal = Mat.zeros(y2 - y1, x2 - x1, CvType.CV_8UC1)
                val eqM = Mat()
                Core.compare(labSub, Scalar(lab.toDouble()), eqM, Core.CMP_EQ)
                tmpLocal.setTo(Scalar(255.0), eqM)
                eqM.release()

                val tmpMerged = Mat()
                val mergedSub = Mat(maskMerged, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1))
                Core.bitwise_or(mergedSub, tmpLocal, tmpMerged)

                // For holes, predSub should be from the original (uneroded) predMask
                val predSub = Mat(predMask, org.opencv.core.Rect(x1, y1, x2 - x1, y2 - y1))
                val xorNew = Mat()
                Core.bitwise_xor(tmpMerged, predSub, xorNew)
                val newSum = Core.countNonZero(xorNew).toLong()

                val xorOld = Mat()
                Core.bitwise_xor(mergedSub, predSub, xorOld)
                val oldSum = Core.countNonZero(xorOld).toLong()

                if (newSum < oldSum) {
                    tmpMerged.copyTo(mergedSub)
                }

                xorNew.release(); xorOld.release(); tmpMerged.release(); tmpLocal.release()
            }
        }
        holeLabels.release(); holeStats.release(); holeCentroids.release()

        return maskMerged
    }
}
