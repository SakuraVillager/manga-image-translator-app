package com.sakuravillager.manga_translator.translation.data

import android.graphics.PointF
import android.graphics.RectF
import com.sakuravillager.manga_translator.translation.translator.common.TextUtils
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.roundToInt
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

data class TextBlock(
    val lines: List<List<PointF>> = emptyList(),
    val texts: List<String> = emptyList(),
    val text: String = "",
    /**
     * The original OCR-recognized text, NEVER modified by pre-dictionary, bracket fixing,
     * or any pre-processing step. Set once at merge time and preserved through all pipeline stages.
     *
     * Used by the renderer for region expansion calculations (matches Python's `text_raw`).
     * The renderer compares `textRaw.length` vs `translation.length` to decide whether
     * the text box needs to be scaled.
     *
     * Contract:
     * - Set in [DefaultTextlineMerger.buildTextBlock] from concatenated line texts
     * - Never overwritten by applyPreDictionary, fixBrackets, or any pre-translation step
     * - May be null-safe (empty string means no text)
     */
    val textRaw: String = "",
    val translation: String = "",
    val language: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val fontSize: Float = 0f,
    /**
     * Rotation angle in RADIANS.
     *
     * 0 = unrotated (horizontal text), positive = clockwise rotation of the text baseline.
     * Used by [unrotatedPolygons] and [minRect] for coordinate rotation via `cos(angle)` / `sin(angle)`.
     *
     * Note: Python's `TextBlock.angle` is in DEGREES. This Kotlin port uses radians consistently
     * throughout the pipeline (merger, predicates, renderer). The values are equivalent after
     * the Python `deg2rad` / `rad2deg` conversions.
     */
    val angle: Float = 0f,
    val fontFamily: String = "",
    val bold: Boolean = false,
    val underline: Boolean = false,
    val italic: Boolean = false,
    val fgColor: Int? = null,
    val bgColor: Int? = null,
    val opacity: Float = 1f,
    val lineSpacing: Float = 0f,
    val letterSpacing: Float = 0f,
    val shadowRadius: Float = 0f,
    val shadowStrength: Float = 0f,
    val shadowColor: Int? = null,
    val shadowOffsetX: Float = 0f,
    val shadowOffsetY: Float = 0f,
    private val _direction: TextDirection = TextDirection.AUTO,
    val probability: Float = 0f,
    val panelIndex: Int = -1,
) {
    private var _fgColor: Int = fgColor ?: 0
    private var _bgColor: Int = bgColor ?: 0xFFFFFF

    val direction: TextDirection get() {
        // 1. If explicitly set (not AUTO), use it directly
        if (_direction != TextDirection.AUTO) return _direction

        // 2. Check language orientation presets
        val lang = targetLanguage
        if (lang != null) {
            val preset = LANGUAGE_ORIENTATION_PRESETS[lang]
            if (preset != null && preset != TextDirection.AUTO) return preset
        }

        // 3. Fall back to aspect ratio of largest line
        if (lines.isNotEmpty()) {
            var maxArea = 0f
            var largestBoxAspectRatio = 1f

            for (line in lines) {
                val area = polygonArea(line)
                if (area > maxArea) {
                    maxArea = area
                    val xs = line.map { it.x }
                    val ys = line.map { it.y }
                    val width = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f)
                    val height = (ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f)
                    largestBoxAspectRatio = if (height > 0f) width / height else 1f
                }
            }

            return if (largestBoxAspectRatio < 1f) TextDirection.VERTICAL else TextDirection.HORIZONTAL
        }

        return TextDirection.HORIZONTAL
    }
    val isHorizontal: Boolean get() = direction != TextDirection.VERTICAL
    val isVertical: Boolean get() = direction == TextDirection.VERTICAL
    val isBulletedList: Boolean get() {
        if (texts.size <= 1) return false

        val bulletRegexes = listOf(
            Regex("""^[^\w\s]\s*"""),        // Special characters: ○ ● ■ etc.
            Regex("""^[\d]+\.\s*"""),         // Numbered: 1. 2. etc.
            Regex("""^[QA]:\s*"""),           // Q: A: etc.
        )

        var bulletTypeIndex = -1
        for (lineText in texts) {
            var matchedIndex = -1
            for ((i, regex) in bulletRegexes.withIndex()) {
                if (regex.containsMatchIn(lineText)) {
                    matchedIndex = i
                    break
                }
            }
            if (matchedIndex >= 0) {
                if (bulletTypeIndex < 0) {
                    bulletTypeIndex = matchedIndex
                } else if (bulletTypeIndex != matchedIndex) {
                    return false  // Different bullet types → not a list
                }
            } else {
                // A line without any bullet prefix → not a list
                return false
            }
        }
        return bulletTypeIndex >= 0
    }
    val alignment: TextAlignment get() {
        if (lines.size == 1) return TextAlignment.CENTER
        if (direction == TextDirection.HORIZONTAL) return TextAlignment.CENTER
        if (direction == TextDirection.HORIZONTAL_RTL) return TextAlignment.RIGHT
        return TextAlignment.LEFT
    }

    val unrotatedPolygons: List<List<PointF>> get() {
        if (angle == 0f) return lines
        val allPoints = lines.flatten()
        if (allPoints.isEmpty()) return lines
        val minX = allPoints.minOf { it.x }
        val minY = allPoints.minOf { it.y }
        val maxX = allPoints.maxOf { it.x }
        val maxY = allPoints.maxOf { it.y }
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val cos = cos(-angle)
        val sin = sin(-angle)
        return lines.map { line ->
            line.map { p ->
                val dx = p.x - cx
                val dy = p.y - cy
                pointF(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
            }
        }
    }

    val minRect: RectF get() {
        val polygons = unrotatedPolygons
        val flatPts = polygons.flatten()
        if (flatPts.isEmpty()) return RectF()
        val minX = flatPts.minOf { it.x }; val minY = flatPts.minOf { it.y }
        val maxX = flatPts.maxOf { it.x }; val maxY = flatPts.maxOf { it.y }
        if (angle != 0f) {
            val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
            val cos = cos(angle)
            val sin = sin(angle)
            val corners = listOf(
                pointF(minX, minY), pointF(maxX, minY),
                pointF(maxX, maxY), pointF(minX, maxY),
            ).map { p ->
                val dx = p.x - cx; val dy = p.y - cy
                pointF(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
            }
            return rectF(
                corners.minOf { it.x },
                corners.minOf { it.y },
                corners.maxOf { it.x },
                corners.maxOf { it.y },
            )
        }
        return rectF(minX, minY, maxX, maxY)
    }

    val center: PointF get() {
        val r = minRect
        return pointF((r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
    }

    val unrotatedSize: Pair<Float, Float> get() {
        val polygons = unrotatedPolygons
        val flatPts = polygons.flatten()
        if (flatPts.isEmpty()) return Pair(0f, 0f)
        val minX = flatPts.minOf { it.x }
        val minY = flatPts.minOf { it.y }
        val maxX = flatPts.maxOf { it.x }
        val maxY = flatPts.maxOf { it.y }
        return Pair(maxX - minX, maxY - minY)
    }

    val aspectRatio: Float get() {
        val (w, h) = unrotatedSize
        if (h == 0f) return 0f
        return w / h
    }

    fun setFontColors(fg: Int, bg: Int) {
        _fgColor = fg
        _bgColor = bg
    }

    fun updateFontColors(fg: Int, bg: Int) {
        val n = lines.size.coerceAtLeast(1).toFloat()
        fun addColor(acc: Int, new: Int): Int {
            val r = ((acc shr 16) and 0xFF) + ((((new shr 16) and 0xFF) / n).roundToInt())
            val g = ((acc shr 8) and 0xFF) + ((((new shr 8) and 0xFF) / n).roundToInt())
            val b = (acc and 0xFF) + (((new and 0xFF) / n).roundToInt())
            return (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        _fgColor = addColor(_fgColor, fg)
        _bgColor = addColor(_bgColor, bg)
    }

    fun getFontColors(bgr: Boolean = false): Pair<Int, Int> {
        var fg = _fgColor
        var bg = _bgColor
        if (bgr) {
            fg = rgbToBgr(fg)
            bg = rgbToBgr(bg)
        }
        val fgR = (fg shr 16) and 0xFF
        val fgG = (fg shr 8) and 0xFF
        val fgB = fg and 0xFF
        val fgAvg = (fgR + fgG + fgB) / 3
        if (colorDifference(fg, bg) < 30f) {
            bg = if (fgAvg <= 127) 0xFFFFFF else 0x000000
        }
        return fg to bg
    }

    private fun rgbToBgr(rgb: Int): Int {
        return ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)
    }

    /**
     * Computes stroke (outline) width for text rendering.
     *
     * Mirrors Python's `stroke_radius = max(0.07 * font_size, 1)` (text_render.py L451).
     * The width is proportional to font size with a minimum of 1px.
     *
     * @return stroke width in pixels, or 0 if fg/bg contrast is too low
     */
    val strokeWidth: Float get() {
        val (fg, bg) = getFontColors()
        val diff = colorDifference(fg, bg)
        return if (diff > 15f) computeStrokeWidth(fontSize) else 0f
    }

    private fun polygonArea(pts: List<PointF>): Float {
        if (pts.size < 3) return 0f
        var area = 0f
        val n = pts.size
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += pts[i].x * pts[j].y
            area -= pts[j].x * pts[i].y
        }
        return abs(area) / 2f
    }

    private fun colorDifference(rgb1: Int, rgb2: Int): Float {
        val mat1 = Mat(1, 1, CvType.CV_8UC3)
        val mat2 = Mat(1, 1, CvType.CV_8UC3)
        val lab1 = Mat()
        val lab2 = Mat()
        try {
            mat1.put(0, 0, byteArrayOf(
                ((rgb1 shr 16) and 0xFF).toByte(),
                ((rgb1 shr 8) and 0xFF).toByte(),
                (rgb1 and 0xFF).toByte(),
            ))
            mat2.put(0, 0, byteArrayOf(
                ((rgb2 shr 16) and 0xFF).toByte(),
                ((rgb2 shr 8) and 0xFF).toByte(),
                (rgb2 and 0xFF).toByte(),
            ))
            Imgproc.cvtColor(mat1, lab1, Imgproc.COLOR_RGB2Lab)
            Imgproc.cvtColor(mat2, lab2, Imgproc.COLOR_RGB2Lab)

            val buf1 = ByteArray(3)
            val buf2 = ByteArray(3)
            lab1.get(0, 0, buf1)
            lab2.get(0, 0, buf2)

            val l1 = (buf1[0].toInt() and 0xFF).toFloat()
            val a1 = (buf1[1].toInt() and 0xFF).toFloat()
            val b1 = (buf1[2].toInt() and 0xFF).toFloat()
            val l2 = (buf2[0].toInt() and 0xFF).toFloat()
            val a2 = (buf2[1].toInt() and 0xFF).toFloat()
            val b2 = (buf2[2].toInt() and 0xFF).toFloat()

            // L* channel weight 0.392 (matching Python generic2.py:16)
            val dl = (l1 - l2) * 0.392f
            val da = a1 - a2
            val db = b1 - b2
            return sqrt(dl * dl + da * da + db * db).toFloat()
        } finally {
            mat1.release(); mat2.release()
            lab1.release(); lab2.release()
        }
    }

    fun getTransformedRegion(
        bitmap: Bitmap,
        lineIndex: Int,
        textHeight: Int,
        maxWidth: Int? = null,
    ): Bitmap {
        if (lineIndex >= lines.size || textHeight <= 0) {
            return Bitmap.createBitmap(textHeight.coerceAtLeast(1), textHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }

        val line = lines[lineIndex]
        if (line.size < 4) {
            return Bitmap.createBitmap(textHeight.coerceAtLeast(1), textHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }

        val imW = bitmap.width
        val imH = bitmap.height
        val xs = line.map { it.x }; val ys = line.map { it.y }
        val x1 = xs.min().toInt().coerceIn(0, imW)
        val y1 = ys.min().toInt().coerceIn(0, imH)
        val x2 = xs.max().toInt().coerceIn(0, imW)
        val y2 = ys.max().toInt().coerceIn(0, imH)
        if (x2 <= x1 || y2 <= y1) {
            return Bitmap.createBitmap(textHeight.coerceAtLeast(1), textHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }

        val cropped = Bitmap.createBitmap(bitmap, x1, y1, x2 - x1, y2 - y1)

        // Convert line points to crop-local coordinates
        val srcPts = line.map { PointF(it.x - x1, it.y - y1) }

        // Calculate midpoint vectors for direction detection
        val midTop = PointF((srcPts[0].x + srcPts[3].x) / 2f, (srcPts[0].y + srcPts[3].y) / 2f)
        val midBottom = PointF((srcPts[1].x + srcPts[2].x) / 2f, (srcPts[1].y + srcPts[2].y) / 2f)
        val midRight = PointF((srcPts[2].x + srcPts[3].x) / 2f, (srcPts[2].y + srcPts[3].y) / 2f)
        val midLeft = PointF((srcPts[0].x + srcPts[1].x) / 2f, (srcPts[0].y + srcPts[1].y) / 2f)
        val vecVx = midBottom.x - midTop.x; val vecVy = midBottom.y - midTop.y
        val vecHx = midRight.x - midLeft.x; val vecHy = midRight.y - midLeft.y
        val normV = sqrt(vecVx * vecVx + vecVy * vecVy)
        val normH = sqrt(vecHx * vecHx + vecHy * vecHy)
        if (normV <= 0f || normH <= 0f) {
            return Bitmap.createBitmap(textHeight.coerceAtLeast(1), textHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        }

        // Determine direction: use direction property to decide vertical/horizontal
        val useVertical = direction == TextDirection.VERTICAL
        val ratio = normV / normH

        val safeHeight = textHeight.coerceAtLeast(1)
        val safeRatio = ratio.coerceAtLeast(0.0001f)
        val (dstW, dstH) = if (useVertical) {
            maxOf(safeHeight, 2) to maxOf(kotlin.math.round(safeHeight * safeRatio).toInt(), 2)
        } else {
            maxOf(kotlin.math.round(safeHeight / safeRatio).toInt(), 2) to maxOf(safeHeight, 2)
        }

        // OpenCV perspective transform
        val srcMat = Mat(4, 2, CvType.CV_32F)
        val dstMat = Mat(4, 2, CvType.CV_32F)
        val cropMat = Mat()
        val warped = Mat()

        try {
            srcMat.put(0, 0, floatArrayOf(
                srcPts[0].x, srcPts[0].y,
                srcPts[1].x, srcPts[1].y,
                srcPts[2].x, srcPts[2].y,
                srcPts[3].x, srcPts[3].y,
            ))
            dstMat.put(0, 0, floatArrayOf(
                0f, 0f,
                (dstW - 1).toFloat(), 0f,
                (dstW - 1).toFloat(), (dstH - 1).toFloat(),
                0f, (dstH - 1).toFloat(),
            ))

            Utils.bitmapToMat(cropped, cropMat)
            val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
            Imgproc.warpPerspective(cropMat, warped, transform, Size(dstW.toDouble(), dstH.toDouble()))
            transform.release()

            val output = if (useVertical) {
                val rotated = Mat()
                Core.rotate(warped, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                warped.release()
                val result = Bitmap.createBitmap(rotated.cols(), rotated.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rotated, result)
                rotated.release()
                result
            } else {
                val result = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped, result)
                warped.release()
                result
            }

            // Scale down if maxWidth is specified
            if (maxWidth != null && output.width > maxWidth) {
                return Bitmap.createScaledBitmap(output, maxWidth, (output.height * maxWidth / output.width).coerceAtLeast(1), true).also { output.recycle() }
            }

            return output
        } finally {
            srcMat.release()
            dstMat.release()
            cropMat.release()
            if (!warped.empty()) warped.release()
        }
    }

    fun getTranslationForRendering(): String {
        if (direction != TextDirection.HORIZONTAL_RTL) return translation

        val textList = translation.toMutableList()
        var ltrStartIndex = -1

        for (i in textList.indices) {
            val ch = textList[i]
            if (!isRightToLeftChar(ch) && isValuableChar(ch)) {
                if (ltrStartIndex < 0) ltrStartIndex = i
            } else {
                if (ltrStartIndex >= 0 && i - ltrStartIndex > 1) {
                    // Reverse LTR block between ltrStartIndex and i
                    var left = ltrStartIndex
                    var right = i - 1
                    while (left < right) {
                        val temp = textList[left]
                        textList[left] = textList[right]
                        textList[right] = temp
                        left++; right--
                    }
                    ltrStartIndex = -1
                }
            }
        }

        // Handle trailing LTR block
        if (ltrStartIndex >= 0 && textList.size - ltrStartIndex > 1) {
            var left = ltrStartIndex
            var right = textList.size - 1
            while (left < right) {
                val temp = textList[left]
                textList[left] = textList[right]
                textList[right] = temp
                left++; right--
            }
        }

        return textList.joinToString("")
    }

    private fun isRightToLeftChar(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x0590..0x05FF) ||  // Hebrew
               (code in 0x0600..0x06FF) ||  // Arabic
               (code in 0x0750..0x077F) ||  // Arabic Supplement
               (code in 0x08A0..0x08FF) ||  // Arabic Extended-A
               (code in 0xFB1D..0xFDFF) ||  // Hebrew/Arabic Presentation Forms
               (code in 0xFE70..0xFEFF) ||  // Arabic Presentation Forms-B
               (code in 0x1EE00..0x1EEFF)   // Arabic Mathematical
    }

    private fun isValuableChar(ch: Char): Boolean = TextUtils.isValuableChar(ch)

    companion object {
        /**
         * Computes stroke width proportional to font size.
         *
         * Matches Python `text_render.py L451`: `stroke_radius = max(0.07 * font_size, 1)`.
         * The result is in pixels. Minimum is 1.0px to ensure stroke visibility
         * even at small font sizes.
         */
        fun computeStrokeWidth(fontSize: Float): Float = maxOf(0.07f * fontSize, 1f)

        @Deprecated("Use computeStrokeWidth(fontSize) instead", ReplaceWith("computeStrokeWidth(fontSize)"))
        const val defaultStrokeWidth: Float = 0.2f

        /**
         * Creates a [PointF] via direct field assignment.
         *
         * The `PointF(Float, Float)` constructor is stubbed (returns (0,0)) in the mockable
         * Android JAR used for JVM unit tests.  Direct field write works on both JVM and
         * Android, so tests can verify geometry computations without Robolectric.
         */
        fun pointF(x: Float, y: Float): PointF {
            val p = PointF()
            p.x = x
            p.y = y
            return p
        }

        /**
         * Creates a [RectF] via direct field assignment.
         *
         * Same rationale as [pointF]: the 4-arg constructor is stubbed in the mockable JAR,
         * but public field writes always work.
         */
        fun rectF(left: Float, top: Float, right: Float, bottom: Float): RectF {
            val r = RectF()
            r.left = left
            r.top = top
            r.right = right
            r.bottom = bottom
            return r
        }

        private val LANGUAGE_ORIENTATION_PRESETS = mapOf(
            "CHS" to TextDirection.AUTO,
            "CHT" to TextDirection.AUTO,
            "CSY" to TextDirection.HORIZONTAL,
            "NLD" to TextDirection.HORIZONTAL,
            "ENG" to TextDirection.HORIZONTAL,
            "FRA" to TextDirection.HORIZONTAL,
            "DEU" to TextDirection.HORIZONTAL,
            "HUN" to TextDirection.HORIZONTAL,
            "ITA" to TextDirection.HORIZONTAL,
            "JPN" to TextDirection.AUTO,
            "KOR" to TextDirection.HORIZONTAL,
            "POL" to TextDirection.HORIZONTAL,
            "PTB" to TextDirection.HORIZONTAL,
            "ROM" to TextDirection.HORIZONTAL,
            "RUS" to TextDirection.HORIZONTAL,
            "ESP" to TextDirection.HORIZONTAL,
            "TRK" to TextDirection.HORIZONTAL,
            "UKR" to TextDirection.HORIZONTAL,
            "VIN" to TextDirection.HORIZONTAL,
            "ARA" to TextDirection.HORIZONTAL_RTL,
            "FIL" to TextDirection.HORIZONTAL,
        )
    }
}
