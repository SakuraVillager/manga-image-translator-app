package com.sakuravillager.manga_translator.translation.mask

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.sakuravillager.manga_translator.translation.api.MaskRefiner
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.util.bitmapToMat
import com.sakuravillager.manga_translator.translation.util.ensureOpenCVLoaded
import com.sakuravillager.manga_translator.translation.util.matToBitmap
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import android.os.Environment
import com.sakuravillager.manga_translator.MangaTranslatorApp
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OpenCV-based mask refiner implementing the `complete_mask` algorithm.
 *
 * ## Algorithm (Python text_mask_utils.py reference)
 * 1. **Scale down** the mask if it's large relative to the image (for performance).
 * 2. **Binarise** the raw mask (threshold at 127/255).
 * 3. **Draw** white-filled text bounding boxes on the mask.
 * 4. **Draw** 1px black outlines to separate touching regions.
 * 5. **Connected components analysis** (OpenCV `connectedComponentsWithStats`) to isolate blobs.
 * 6. **Associate** each CC with the nearest text line (by overlap ratio, centroid distance).
 * 7. **Bilateral filter** on the source image as edge-preserving preprocessing.
 * 8. **Color-guided mask expansion** per region (DenseCRF approximation):
 *    dilate seed mask → compute mean text color → expand to border pixels with similar color.
 * 9. **Dilate** to expand coverage.
 * 10. Optional **bubble filtering** (if `ignoreBubble` is set).
 *
 * ## Differences from Python reference
 * | Python (pydensecrf) | Kotlin (CompleteMaskRefiner) |
 * |----------------------|------------------------------|
 * | pydensecrf (Dense CRF) for edge refinement | Bilateral filter + Otsu thresholding |
 * | felzenszwalb superpixel segmentation | Connected components analysis |
 * | CRF produces smoother mask edges | Slightly rougher edges, but sufficient for LAMA/SDD inpainting |
 * | Requires compiled C extension | Pure OpenCV — runs on Android |
 *
 * ## Parameter tuning
 * - `bilateralFilter(d=17, sigmaColor=80, sigmaSpace=80)`: controls edge preservation strength.
 * - Color-guided expansion: `borderWidth=5`, `colorThresh=50` — approximates DenseCRF (sxy=23, srgb=7).
 *
 * @see com.sakuravillager.manga_translator.translation.api.MaskRefiner
 */
class CompleteMaskRefiner : MaskRefiner {

    override val name: String = "CompleteMaskRefiner"
    override var isReady: Boolean = false
        private set

    override suspend fun prepare() {
        if (!ensureOpenCVLoaded()) {
            throw IllegalStateException("Failed to load OpenCV")
        }
        isReady = true
    }

    override suspend fun release() {
        isReady = false
    }

    // ------------------------------------------------------------------
    // Internal data: a flattened text line extracted from a TextBlock
    // ------------------------------------------------------------------
    private data class TextLineInfo(
        val boundingRect: RectF,
        val fontSize: Float,
    )

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    override suspend fun refine(
        textRegions: List<TextBlock>,
        bitmap: Bitmap,
        rawMask: Bitmap?,
        kernelSize: Int,
        dilationOffset: Int,
        ignoreBubble: Int,
    ): Bitmap {
        // scale_factor matching Python mask_refinement/__init__.py L12:
        //   scale_factor = max(min((mask_H - image_H/3) / mask_H, 1), 0.5)
        val scaleFactor = if (rawMask != null) {
            kotlin.math.max(kotlin.math.min((rawMask.height.toFloat() - bitmap.height.toFloat() / 3f) / rawMask.height.toFloat().coerceAtLeast(1f), 1f), 0.5f)
        } else 1f

        val (procBitmap, procMask, procScale) = if (scaleFactor < 1f && rawMask != null) {
            val newW = (bitmap.width * scaleFactor).toInt()
            val newH = (bitmap.height * scaleFactor).toInt()
            val imgResized = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            val maskResized = Bitmap.createScaledBitmap(rawMask, newW, newH, true)
            Triple(imgResized, maskResized, scaleFactor)
        } else {
            Triple(bitmap, rawMask, 1f)
        }

        val width = procBitmap.width
        val height = procBitmap.height

        // --- Flatten TextBlocks into individual text lines ---------------
        val textLines = mutableListOf<TextLineInfo>()
        // Scale text line coordinates by scaleFactor to match resized image
        val s = procScale
        for (region in textRegions) {
            for (line in region.lines) {
                if (line.size < 4) continue
                val xs = line.map { it.x * s }
                val ys = line.map { it.y * s }
                val rect = RectF(xs.min(), ys.min(), xs.max(), ys.max())
                textLines.add(TextLineInfo(rect, region.fontSize))
            }
        }

        val M = textLines.size

        // Owned Mats — released in the finally block
        val ownedMats = mutableListOf<Mat>()

        try {
            // --- Step 1-2: Create & binarise mask -------------------------
            val maskMat: Mat = when {
                procMask != null -> {
                    val rawMat = bitmapToMat(procMask).also { ownedMats.add(it) }
                    val gray = Mat().also { ownedMats.add(it) }
                    Imgproc.cvtColor(rawMat, gray, Imgproc.COLOR_RGBA2GRAY)
                    gray
                }
                else -> Mat.zeros(height, width, CvType.CV_8UC1).also { ownedMats.add(it) }
            }
            Imgproc.threshold(maskMat, maskMat, 127.0, 255.0, Imgproc.THRESH_BINARY)

            // If there are no text lines just return the (binarised) mask
            if (M == 0) {
                val bmp = matToBitmap(maskMat)
                return if (procScale < 1f) Bitmap.createScaledBitmap(bmp, bitmap.width, bitmap.height, true).also { bmp.recycle() } else bmp
            }

            // --- Step 3: 1px black outlines to separate touching regions --
            // Matches Python text_mask_utils.py L99-100: only outlines, NO white fill.
            // White fill was removed because it expands mask beyond CTD-detected
            // regions, causing incorrect CC merging between adjacent text lines.
            for (tl in textLines) {
                val r = tl.boundingRect
                Imgproc.rectangle(
                    maskMat,
                    Point(r.left.toDouble(), r.top.toDouble()),
                    Point(r.right.toDouble(), r.bottom.toDouble()),
                    Scalar.all(0.0),
                    1,
                )
            }

            // --- Step 5: Connected components analysis --------------------
            val labels = Mat().also { ownedMats.add(it) }
            val stats = Mat().also { ownedMats.add(it) }
            val centroids = Mat().also { ownedMats.add(it) }

            val numLabels = Imgproc.connectedComponentsWithStats(
                maskMat, labels, stats, centroids,
            )

            // --- Step 6: Associate each CC with the nearest text line -----
            val textlineCcs = Array(M) {
                Mat.zeros(height, width, CvType.CV_8UC1).also { ownedMats.add(it) }
            }
            // Bounding rect (left, top, right, bottom) of all CCs per text line
            val textlineRects = Array(M) {
                intArrayOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MIN_VALUE)
            }

            var valid = false
            val keepThreshold = 1e-2 // 1 %

            for (label in 1 until numLabels) {
                val area = stats.get(label, Imgproc.CC_STAT_AREA)[0].toInt()
                if (area <= 9) continue // skip tiny components

                val x1 = stats.get(label, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y1 = stats.get(label, Imgproc.CC_STAT_TOP)[0].toInt()
                val w1 = stats.get(label, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h1 = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val centroidX = centroids.get(label, 0)[0]
                val centroidY = centroids.get(label, 1)[0]

                // --- Find best-matching text line for this CC ----------
                var bestOverlapIdx = 0
                var bestOverlap = 0.0
                var bestDistIdx = 0
                var bestDist = Double.MAX_VALUE

                for (tlIdx in 0 until M) {
                    val tr = textLines[tlIdx].boundingRect

                    // Rectangle‑approximated overlap ratio
                    val overlapL = maxOf(x1.toFloat(), tr.left)
                    val overlapT = maxOf(y1.toFloat(), tr.top)
                    val overlapR = minOf((x1 + w1).toFloat(), tr.right)
                    val overlapB = minOf((y1 + h1).toFloat(), tr.bottom)
                    val overlapW = maxOf(0f, overlapR - overlapL)
                    val overlapH = maxOf(0f, overlapB - overlapT)
                    val overlapArea = overlapW * overlapH

                    val tlArea = tr.width() * tr.height()
                    val ratio = if (minOf(area.toFloat(), tlArea) > 0f) {
                        (overlapArea / minOf(area.toFloat(), tlArea)).toDouble()
                    } else 0.0

                    if (ratio > bestOverlap) {
                        bestOverlap = ratio
                        bestOverlapIdx = tlIdx
                    }

                    // Minimum distance from textline rect boundary to CC centroid
                    // (approximates Shapely Polygon.distance(centroid): 0 if inside,
                    // Euclidean distance to nearest edge/corner if outside)
                    val dxr = maxOf(tr.left.toDouble() - centroidX, 0.0, centroidX - tr.right.toDouble())
                    val dyr = maxOf(tr.top.toDouble() - centroidY, 0.0, centroidY - tr.bottom.toDouble())
                    val d = sqrt(dxr * dxr + dyr * dyr)
                    if (d < bestDist) {
                        bestDist = d
                        bestDistIdx = tlIdx
                    }
                }

                // --- Decide whether to keep this CC --------------------
                // Python text_mask_utils.py L130-134: area filter BEFORE distance fallback
                val bestOverlapArea = textLines[bestOverlapIdx].boundingRect.width().toDouble() *
                        textLines[bestOverlapIdx].boundingRect.height().toDouble()
                if (area >= bestOverlapArea) continue // large CC → skip (background region)

                val avgIdx: Int
                if (bestOverlap <= keepThreshold) {
                    // No significant overlap — fall back to nearest by distance
                    avgIdx = bestDistIdx
                    val fontSize = textLines[avgIdx].fontSize
                    val unit = maxOf(minOf(fontSize, w1.toFloat(), h1.toFloat()), 10f)
                    if (bestDist >= 0.5 * unit) continue // too far
                } else {
                    avgIdx = bestOverlapIdx
                }

                // --- Draw this CC into the text line's mask -------------
                val clampedRect = org.opencv.core.Rect(
                    maxOf(0, x1), maxOf(0, y1),
                    minOf(w1, width - x1),
                    minOf(h1, height - y1),
                )
                if (clampedRect.width <= 0 || clampedRect.height <= 0) continue

                val labelSub = Mat(labels, clampedRect)  // submat — no release
                val ccSub = Mat(textlineCcs[avgIdx], clampedRect)  // submat
                val eqMask = Mat()  // owned — released inline

                Core.compare(labelSub, Scalar.all(label.toDouble()), eqMask, Core.CMP_EQ)
                ccSub.setTo(Scalar.all(255.0), eqMask)

                eqMask.release()

                // Update bounding rect for this text line
                val r = textlineRects[avgIdx]
                r[0] = minOf(r[0], x1)
                r[1] = minOf(r[1], y1)
                r[2] = maxOf(r[2], x1 + w1)
                r[3] = maxOf(r[3], y1 + h1)
                valid = true
            }

            if (!valid) {
                // No CCs were assigned — return the original (binarised) mask
                val bmp = matToBitmap(maskMat)
                return if (procScale < 1f) Bitmap.createScaledBitmap(bmp, bitmap.width, bitmap.height, true).also { bmp.recycle() } else bmp
            }

            // Convert textline rects from (l, t, r, b) → (x, y, w, h)
            val textlineRectsXywh = Array<IntArray?>(M) { i ->
                val r = textlineRects[i]
                if (r[0] == Int.MAX_VALUE) null
                else intArrayOf(r[0], r[1], r[2] - r[0], r[3] - r[1])
            }

            // --- Step 7: RGB Bilateral filter on source image (CRF alt.) ---
            // Matches Python text_mask_utils.py L169: bilateralFilter(img, 17, 80, 80)
            // Python applies bilateral filter to the RGB image (per-channel filtering),
            // preserving color-specific edge information that grayscale conversion would lose.
            // This is critical for detecting colored text (red/blue) on colored backgrounds.
            val imgMat = bitmapToMat(procBitmap).also { ownedMats.add(it) }
            val imgRgb = Mat().also { ownedMats.add(it) }
            Imgproc.cvtColor(imgMat, imgRgb, Imgproc.COLOR_RGBA2RGB)
            val bfChannels = mutableListOf<Mat>()
            Core.split(imgRgb, bfChannels)
            val bfFiltered = mutableListOf<Mat>()
            for (ch in bfChannels) {
                val filtered = Mat().also { ownedMats.add(it) }
                Imgproc.bilateralFilter(ch, filtered, 17, 80.0, 80.0)
                bfFiltered.add(filtered)
            }
            val imgFiltered = Mat().also { ownedMats.add(it) }
            Core.merge(bfFiltered, imgFiltered)

            // --- Step 8-9: Process each text line & build final mask ---
            val finalMask = Mat.zeros(height, width, CvType.CV_8UC1).also { ownedMats.add(it) }

            for (i in 0 until M) {
                val ccMask = textlineCcs[i]
                val rectInfo = textlineRectsXywh[i] ?: continue

                var x1 = rectInfo[0]
                var y1 = rectInfo[1]
                var w1 = rectInfo[2]
                var h1 = rectInfo[3]

                val textSize = maxOf(minOf(w1, h1, textLines[i].fontSize.toInt()), 1)

                // Extend region by 10 % of textSize
                val ext1 = maxOf((textSize * 0.1f).toInt(), 1)
                val e1 = extendRect(x1, y1, w1, h1, width, height, ext1)
                x1 = e1[0]; y1 = e1[1]; w1 = e1[2]; h1 = e1[3]

                if (w1 <= 0 || h1 <= 0) continue

                // Odd kernel size for morphological operations
                val dilateSize = maxOf(
                    ((((textSize + dilationOffset) * 0.3f).toInt()) / 2) * 2 + 1,
                    3,
                )

                val ccRect = org.opencv.core.Rect(x1, y1, w1, h1)

                // --- Color-guided mask expansion (DenseCRF approximation) ---
                // Python text_mask_utils.py L184: cc_region = refine_mask(img_region, cc_region)
                // DenseCRF uses the CC mask as unary prior and expands based on color similarity.
                // We approximate this: dilate seed mask → compute mean text color → keep border
                // pixels with similar color. This captures anti-aliased boundary pixels that
                // per-channel Otsu misses (they have intermediate brightness, not "dark").
                val closeKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size(dilateSize.toDouble(), dilateSize.toDouble()),
                ).also { ownedMats.add(it) }

                val ccSub = Mat(ccMask, ccRect)  // seed mask submat

                val borderWidth = 5
                val colorThresh = 300.0  // was 50.0 — see plan for numerical justification

                // Step A: border zone = dilate(ccSub) - ccSub
                val borderKernel = Imgproc.getStructuringElement(
                    Imgproc.MORPH_ELLIPSE,
                    Size(borderWidth * 2 + 1.0, borderWidth * 2 + 1.0),
                ).also { ownedMats.add(it) }
                val ccDilated = Mat().also { ownedMats.add(it) }
                Imgproc.dilate(ccSub, ccDilated, borderKernel)
                val borderZone = Mat().also { ownedMats.add(it) }
                Core.subtract(ccDilated, ccSub, borderZone)

                // Step B: mean text color (masked mean over seed region)
                val imgSubRgb = Mat(imgFiltered, ccRect)  // submat
                val meanColor = Core.mean(imgSubRgb, ccSub)  // Scalar(r, g, b)

                // Step C: Manhattan color distance (float to avoid uint8 saturation)
                val imgChannels = mutableListOf<Mat>()
                Core.split(imgSubRgb, imgChannels)
                val distSum = Mat.zeros(ccRect.height, ccRect.width, CvType.CV_32FC1)
                    .also { ownedMats.add(it) }
                for ((idx, ch) in imgChannels.withIndex()) {
                    val absDiff = Mat().also { ownedMats.add(it) }
                    Core.absdiff(ch, Scalar(meanColor.`val`[idx]), absDiff)
                    val absDiffF = Mat().also { ownedMats.add(it) }
                    absDiff.convertTo(absDiffF, CvType.CV_32FC1)
                    Core.add(distSum, absDiffF, distSum)
                }

                // Step D: threshold → restrict to border zone
                val colorSimMaskF = Mat().also { ownedMats.add(it) }
                Imgproc.threshold(distSum, colorSimMaskF, colorThresh, 255.0,
                    Imgproc.THRESH_BINARY_INV)
                val colorSimMask = Mat().also { ownedMats.add(it) }
                colorSimMaskF.convertTo(colorSimMask, CvType.CV_8UC1)
                val borderExpansion = Mat().also { ownedMats.add(it) }
                Core.bitwise_and(colorSimMask, borderZone, borderExpansion)

                // Step E: OR into seed mask
                Core.bitwise_or(ccSub, borderExpansion, ccSub)

                // --- Further extend & dilate ----------------------------
                val ext2 = (dilateSize + 1) / 2 // ceil(dilateSize / 2)
                val e2 = extendRect(x1, y1, w1, h1, width, height, ext2)
                val dx2 = e2[0]; val dy2 = e2[1]; val dw2 = e2[2]; val dh2 = e2[3]

                if (dw2 <= 0 || dh2 <= 0) continue

                val dilateRect = org.opencv.core.Rect(dx2, dy2, dw2, dh2)
                val dilateSub = Mat(ccMask, dilateRect)  // submat
                val finalSub = Mat(finalMask, dilateRect)  // submat

                Imgproc.dilate(dilateSub, dilateSub, closeKernel)
                Core.bitwise_or(finalSub, dilateSub, finalSub)
            }

            // DEBUG: clone raw mask BEFORE final dilation
            val debugRawMask = finalMask.clone().also { ownedMats.add(it) }  // will be saved at the end

            // --- Step 10: Final dilation with caller‑specified kernel ----
            val finalKernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_ELLIPSE,
                Size(kernelSize.toDouble(), kernelSize.toDouble()),
            ).also { ownedMats.add(it) }
            Imgproc.dilate(finalMask, finalMask, finalKernel)

            // --- Step 11: Bubble‑region filtering (BubbleDetector) -------
            // Matches Python dispatch(): only apply bubble filtering when the
            // configuration explicitly enables it.
            if (ignoreBubble < 1 || ignoreBubble > 50) {
                val resultBitmap = matToBitmap(finalMask)
                // DEBUG: save BOTH masks (early return path)
                debugSaveMask(matToBitmap(debugRawMask), "debug_mask_raw.png", bitmap.width, bitmap.height)
                debugSaveMask(resultBitmap, "debug_mask_dilated.png", bitmap.width, bitmap.height)
                debugRawMask.release()
                return if (procScale < 1f) {
                    Bitmap.createScaledBitmap(resultBitmap, bitmap.width, bitmap.height, true)
                        .also { if (it !== resultBitmap) resultBitmap.recycle() }
                } else resultBitmap
            }

            val bubbleKernelSize = maxOf(
                (maxOf(finalMask.rows(), finalMask.cols()) * 0.025f).toInt(),
                1,
            )
            val bubbleKernel = Mat(
                bubbleKernelSize, bubbleKernelSize,
                CvType.CV_8UC1, Scalar.all(1.0),
            ).also { ownedMats.add(it) }
            Imgproc.dilate(finalMask, finalMask, bubbleKernel)

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat().also { ownedMats.add(it) }
            Imgproc.findContours(
                finalMask, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
            )

            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                // Build a minimal TextBlock so BubbleDetector can evaluate
                // this region's edge density and pixel variance.
                val testBlock = TextBlock(
                    lines = listOf(
                        listOf(
                            PointF(rect.x.toFloat(), rect.y.toFloat()),
                            PointF((rect.x + rect.width).toFloat(), rect.y.toFloat()),
                            PointF((rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat()),
                            PointF(rect.x.toFloat(), (rect.y + rect.height).toFloat()),
                        ),
                    ),
                )
                if (BubbleDetector.isIgnore(testBlock, procBitmap, ignoreBubble)) {
                    Imgproc.drawContours(
                        finalMask, listOf(contour), -1,
                        Scalar.all(0.0), Imgproc.FILLED,
                    )
                }
                contour.release()
            }

            // --- Convert result to Bitmap --------------------------------
            val resultBitmap = matToBitmap(finalMask)

            // DEBUG: save BOTH masks (raw before dilation + dilated after all processing)
            debugSaveMask(matToBitmap(debugRawMask), "debug_mask_raw.png", bitmap.width, bitmap.height)
            debugSaveMask(resultBitmap, "debug_mask_dilated.png", bitmap.width, bitmap.height)
            debugRawMask.release()

            // Scale back to original dimensions if downscaled (matching Python L28)
            return if (procScale < 1f) {
                Bitmap.createScaledBitmap(resultBitmap, bitmap.width, bitmap.height, true)
                    .also { if (it !== resultBitmap) resultBitmap.recycle() }
            } else resultBitmap

        } finally {
            for (m in ownedMats) {
                m.release()
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Extends a rectangle [x, y, w, h] by [extend] pixels on each side,
     * clamped to image bounds.
     *
     * Returns `[x1, y1, w1, h1]` with a minimum size of 1×1.
     */
    private fun extendRect(
        x: Int, y: Int, w: Int, h: Int,
        maxX: Int, maxY: Int,
        extend: Int,
    ): IntArray {
        val x1 = maxOf(x - extend, 0)
        val y1 = maxOf(y - extend, 0)
        val w1 = minOf(w + extend * 2, maxX - x1)
        val h1 = minOf(h + extend * 2, maxY - y1)
        return intArrayOf(x1, y1, maxOf(w1, 1), maxOf(h1, 1))
    }

    /**
     * DEBUG: Save mask bitmap to phone storage for visual inspection.
     * The mask is always upscaled to [origW]×[origH] before saving,
     * so raw and dilated masks can be directly compared/overlaid.
     * TODO: Remove this function after debugging is complete.
     */
    private fun debugSaveMask(mask: Bitmap, filename: String, origW: Int, origH: Int) {
        try {
            // Use app-private external dir — no permissions needed
            val context = MangaTranslatorApp.appContext
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            val file = File(dir, filename)
            // Upscale to original resolution if the mask is at a smaller scale
            val toSave = if (mask.width != origW || mask.height != origH) {
                Bitmap.createScaledBitmap(mask, origW, origH, false)
            } else mask
            FileOutputStream(file).use { out ->
                toSave.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (toSave !== mask) toSave.recycle()
            android.util.Log.d("CompleteMaskRefiner", "DEBUG $filename saved: ${file.absolutePath} ($origW x $origH)")
        } catch (e: Exception) {
            android.util.Log.e("CompleteMaskRefiner", "DEBUG $filename save FAILED", e)
        }
    }
}
