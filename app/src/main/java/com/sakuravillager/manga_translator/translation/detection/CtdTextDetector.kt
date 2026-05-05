package com.sakuravillager.manga_translator.translation.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.TextDetector
import com.sakuravillager.manga_translator.translation.data.DetectionResult
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.config.DetectorConfig
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelRegistry
import com.sakuravillager.manga_translator.translation.onnx.OnnxSessionManager
import com.sakuravillager.manga_translator.translation.onnx.TensorConverter
import com.sakuravillager.manga_translator.translation.util.letterbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
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
class CtdTextDetector(
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
        const val INPUT_SIZE = 1024
        const val STRIDE = 64
        const val BINARY_THRESH = 0.3f
        const val BOX_THRESH = 0.6f
        const val UNCLIP_RATIO = 1.5f
        const val MAX_CANDIDATES = 1000
        const val TAG = "CtdTextDetector"
    }

    // -----------------------------------------------------------------------
    // PipelineModule
    // -----------------------------------------------------------------------

    override suspend fun prepare() {
        Log.d(TAG, "Preparing CtdTextDetector…")
        val modelFile = modelDownloadManager.ensureModel(ModelRegistry.CTD_MODEL)
        val modelBytes = modelFile.readBytes()
        Log.d(TAG, "Model loaded (${modelBytes.size} bytes), creating ONNX session…")
        session = sessionManager.createSession(modelBytes)
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
        bitmap: Bitmap,
        config: DetectorConfig,
    ): DetectionResult = withContext(Dispatchers.Default) {
        val sess = session ?: error("CtdTextDetector not prepared. Call prepare() first.")

        val srcW = bitmap.width
        val srcH = bitmap.height
        Log.d(TAG, "detect(${srcW}x${srcH}) start")

        // ---- 1. Letterbox to 1024×1024 ------------------------------------
        val lb = letterbox(bitmap, INPUT_SIZE, STRIDE)
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
            val cropY = (dh / 2f / scaleY).toInt().coerceAtLeast(0)
            val cropX = (dw / 2f / scaleX).toInt().coerceAtLeast(0)
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
                Quadrilateral(
                    points = Quadrilateral.sortPoints(scaledPoints),
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

            DetectionResult(textlines = scaledQuads, rawMask = maskBitmap, mask = null)
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
    private fun dbnetDecode(shrinkMap: Mat): List<Quadrilateral> {
        val h = shrinkMap.rows()
        val w = shrinkMap.cols()

        // 1. Binarize
        val binary = Mat()
        try {
            Imgproc.threshold(
                shrinkMap, binary,
                BINARY_THRESH.toDouble(), 255.0,
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
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "findContours failed", e)
            return emptyList()
        } finally {
            hierarchy.release()
            binary.release()
        }

        val results = mutableListOf<Quadrilateral>()

        for (contour in contours) {
            try {
                if (results.size >= MAX_CANDIDATES) {
                    contour.release()
                    continue
                }

                val area = Imgproc.contourArea(contour)
                if (area < 3.0) {
                    contour.release()
                    continue
                }

                // 3. minAreaRect
                val point2f = MatOfPoint2f(*contour.toArray())
                val rect = Imgproc.minAreaRect(point2f)
                val rectPoints = Array(4) { Point() }
                rect.points(rectPoints)

                // 4. box_score_fast: mean of pred values inside rotated rect
                val score = boxScoreFast(shrinkMap, rectPoints, h, w)
                if (score < BOX_THRESH) {
                    point2f.release()
                    contour.release()
                    continue
                }

                // 5. Unclip polygon
                val perimeter = Imgproc.arcLength(point2f, true)
                val unclipDist = area * UNCLIP_RATIO / maxOf(perimeter, 1.0)
                val expanded = unclipPolygon(contour, unclipDist)
                point2f.release()

                if (expanded.size().area() < 3.0) {
                    expanded.release()
                    contour.release()
                    continue
                }

                // 6. minAreaRect of expanded polygon
                val expandedP2f = MatOfPoint2f(*expanded.toArray())
                val expandedRect = Imgproc.minAreaRect(expandedP2f)
                val expandedPts = Array(4) { Point() }
                expandedRect.points(expandedPts)
                expandedP2f.release()
                expanded.release()
                contour.release()

                val pts = expandedPts.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                // Ensure valid quadrilateral (non-zero area)
                val quad = Quadrilateral(points = pts, text = "", probability = score)
                if (quad.area < 1f) continue

                results.add(quad)
            } catch (e: Exception) {
                Log.w(TAG, "Contour processing failed", e)
                // Release contour safely
                try { contour.release() } catch (_: Exception) {}
            }
        }

        Log.d(TAG, "dbnetDecode: ${results.size} quads after filtering")
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
            val meanVal = Core.mean(roi, mask)
            return meanVal.`val`[0].toFloat()
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

            // Crop letterbox padding
            val cropY = dh / 2
            val cropX = dw / 2
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
}
