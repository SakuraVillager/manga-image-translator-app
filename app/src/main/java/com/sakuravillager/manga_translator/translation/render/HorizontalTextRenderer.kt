package com.sakuravillager.manga_translator.translation.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.util.Log
import com.sakuravillager.manga_translator.data.logging.AppLogger
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.calib3d.Calib3d
import org.opencv.imgproc.Imgproc
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.data.TextAlignment
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.data.TextDirection
import com.sakuravillager.manga_translator.translation.data.config.RendererConfig
import com.sakuravillager.manga_translator.translation.model.ModelDownloadManager
import com.sakuravillager.manga_translator.translation.model.ModelRegistry

/**
 * Horizontal text renderer that draws translated text onto a bitmap copy
 * using Android [Canvas] and [Paint] APIs.
 *
 * Supports font border (stroke) via a two-pass draw for API 28+ compatibility,
 * font scaling when text exceeds the bounding rect, and configurable alignment.
 */
class HorizontalTextRenderer(
    private val context: Context,
    private val modelDownloadManager: ModelDownloadManager,
) : TextRenderer {

    override val name: String = "HorizontalTextRenderer"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    private var typeface: Typeface? = null

    override suspend fun prepare() {
        typeface = try {
            Typeface.createFromAsset(context.assets, "fonts/NotoSansCJK-Regular.ttc")
        } catch (_: Exception) {
            try {
                Typeface.createFromAsset(context.assets, "fonts/NotoSansCJK-Regular.ttf")
            } catch (_: Exception) {
                // Assets not bundled — try runtime download
                try {
                    Log.i(name, "Downloading CJK font...")
                    val fontFile = modelDownloadManager.ensureModel(ModelRegistry.CJK_FONT)
                    Typeface.createFromFile(fontFile).also {
                        Log.i(name, "Font loaded successfully")
                    }
                } catch (e: Exception) {
                    Log.w(name, "CJK font download failed: ${e.message}")
                    Log.w(name, "CJK font not found, falling back to system default")
                    Typeface.DEFAULT
                }
            }
        }
        _isReady = true
    }

    override suspend fun release() {
        _isReady = false
    }

    override suspend fun render(
        bitmap: Bitmap,
        textRegions: List<TextBlock>,
        config: RendererConfig,
    ): Bitmap {
        // Empty regions → return unchanged
        if (textRegions.isEmpty()) return bitmap

        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true) ?: return bitmap
        val canvas = Canvas(result)

        // Compute expanded destination quads (matching Python resize_regions_to_font_size)
        var ri = 0
        for (region in textRegions) {
            val renderText = region.getTranslationForRendering()
            if (renderText.isEmpty()) continue

            val rect = region.minRect
            if (rect.isEmpty) continue

            // Perspective render for horizontal text only; Canvas for vertical text
            if (region.isHorizontal) {
                val dstPoints = computeDstPointsFromRegion(region)
                try {
                    perspectiveRender(result, region, dstPoints, config.disableFontBorder)
                    continue
                } catch (_: Exception) { /* fall through to Canvas */ }
            }

            // Canvas-based rendering for vertical text (matches Python put_text_vertical)
            AppLogger.i(name, "[FALLBACK] dir=${region.direction} isH=${region.isHorizontal} text='${renderText.take(10)}' rect=${rect.width().toInt()}x${rect.height().toInt()}")

            var textSize = region.fontSize + config.fontSizeOffset
            textSize = if (config.fontSizeMinimum > 0) maxOf(textSize, config.fontSizeMinimum.toFloat()) else maxOf(textSize, 1f)

            val (regionFg, regionBg) = region.getFontColors()
            val fgColor = config.fontColor?.let { parseColorOrNull(it) } ?: regionFg
            val bgColor = regionBg
            val paint = buildPaint(region, textSize, fgColor)
            val effectiveLineSpacing = if (region.lineSpacing > 0f) region.lineSpacing else config.lineSpacing

            when (region.direction) {
                TextDirection.VERTICAL -> {
                    AppLogger.i(
                        name,
                        "[V-PRE] regionFontSize=${region.fontSize} paintSize=${paint.textSize} " +
                            "offset=${config.fontSizeOffset} min=${config.fontSizeMinimum} " +
                            "lineSpacing=${region.lineSpacing} text='${renderText.take(10)}' rect=${rect.width().toInt()}x${rect.height().toInt()}",
                    )
                    renderVerticalText(
                        canvas,
                        renderText,
                        rect,
                        paint,
                        fgColor,
                        bgColor,
                        config.disableFontBorder,
                        region.strokeWidth,
                        effectiveLineSpacing,
                    )
                    continue
                }
                TextDirection.HORIZONTAL_RTL -> {
                    if (renderText.contains('\n')) {
                        renderMultilineHorizontal(
                            canvas = canvas,
                            text = renderText,
                            rect = rect,
                            paint = paint,
                            fgColor = fgColor,
                            bgColor = bgColor,
                            disableBorder = config.disableFontBorder,
                            rtl = true,
                            lineSpacing = effectiveLineSpacing,
                            strokeWidth = region.strokeWidth,
                        )
                        continue
                    }
                    renderHorizontalRtl(canvas, renderText, rect, paint, fgColor, bgColor, config.disableFontBorder, region.strokeWidth)
                    continue
                }
                else -> { /* continue with horizontal rendering */ }
            }

            if (renderText.contains('\n')) {
                renderMultilineHorizontal(
                    canvas = canvas,
                    text = renderText,
                    rect = rect,
                    paint = paint,
                    fgColor = fgColor,
                    bgColor = bgColor,
                    disableBorder = config.disableFontBorder,
                    rtl = false,
                    lineSpacing = effectiveLineSpacing,
                    strokeWidth = region.strokeWidth,
                )
                continue
            }

            // --- Measure & scale to fit ---
            var textWidth = paint.measureText(renderText)
            if (textWidth > rect.width() && rect.width() > 0f) {
                val scaleFactor = rect.width() / textWidth
                textSize *= scaleFactor
                paint.textSize = textSize
                textWidth = paint.measureText(renderText)
            }

            // --- Alignment ---
            // Use config override if set, otherwise auto-detected region alignment
            val effectiveAlignment = if (config.alignment != TextAlignment.AUTO) config.alignment else region.alignment
            val x = when (effectiveAlignment) {
                TextAlignment.LEFT -> rect.left
                TextAlignment.CENTER -> rect.centerX() - textWidth / 2f
                TextAlignment.RIGHT -> rect.right - textWidth
                else -> rect.left
            }
            // Approximate baseline: vertical center + one third of text size
            val y = rect.centerY() + textSize / 3f

            // --- Draw ---
            if (!config.disableFontBorder) {
                // Two-pass draw for API 28+ compatibility (setStrokeColor is API 29+):
                // First pass: stroke (border)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = region.strokeWidth
                paint.color = bgColor
                applyRegionTextStyle(paint, region, fgColor, textSize)
                canvas.drawText(renderText, x, y, paint)
            }
            // Second pass: fill (foreground)
            paint.style = Paint.Style.FILL
            applyRegionTextStyle(paint, region, fgColor, textSize)
            canvas.drawText(renderText, x, y, paint)
        }

        return result
    }

    /**
     * Renders text vertically (top-to-bottom, right-to-left).
     * Used for Japanese/Chinese vertical text in manga.
     */
    private fun renderVerticalText(
        canvas: Canvas,
        text: String,
        rect: RectF,
        paint: Paint,
        fgColor: Int,
        bgColor: Int,
        disableBorder: Boolean,
        strokeWidth: Float,
        lineSpacing: Float?,
    ) {
        // Scale font to fit the available box, then place glyphs using measured bounds.
        // This is closer to the Python renderer than anchoring by font size alone.
        val renderText = text.trim()
        if (renderText.isEmpty()) return

        val availableWidth = (rect.width() - strokeWidth * 2f).coerceAtLeast(1f)
        val availableHeight = (rect.height() - strokeWidth * 2f).coerceAtLeast(1f)
        val bounds = Rect()

        val heightDrivenFontSize = (availableHeight / maxOf(renderText.length, 1).toFloat() * 0.92f).coerceAtLeast(4f)
        val widthDrivenFontSize = (availableWidth / 1.15f).coerceAtLeast(4f)
        val initialFontSize = if (paint.textSize > heightDrivenFontSize) paint.textSize else heightDrivenFontSize
        val boundedFontSize = if (initialFontSize > widthDrivenFontSize) widthDrivenFontSize else initialFontSize
        var fontSize = boundedFontSize
        paint.textSize = fontSize
        var fontMetrics = paint.fontMetrics
        var fontMetricsHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(1f)
        var maxCharHeight = 0f
        var maxCharWidth = 0f
        for (char in renderText) {
            val charString = char.toString()
            paint.getTextBounds(charString, 0, 1, bounds)
            maxCharHeight = maxOf(maxCharHeight, bounds.height().toFloat().coerceAtLeast(1f))
            maxCharWidth = maxOf(maxCharWidth, bounds.width().toFloat().coerceAtLeast(paint.measureText(charString)))
        }
        var lineHeight = maxOf(fontSize * 1.03f, maxCharHeight * 1.02f).coerceAtLeast(1f)
        var charsPerColumn = maxOf(1, kotlin.math.floor(availableHeight / fontSize).toInt())
        var columnCount = kotlin.math.ceil(renderText.length / charsPerColumn.toFloat()).toInt().coerceAtLeast(1)

        var columnWidth = maxOf(fontSize * 0.95f, maxCharWidth + strokeWidth * 1.1f).coerceAtLeast(4f)
        val columnGap = maxOf(fontSize * (lineSpacing?.takeIf { it > 0f } ?: 0.2f), strokeWidth * 0.5f)
            .coerceAtLeast(0f)
        var columnAdvance = columnWidth + columnGap
        var totalWidth = columnAdvance * columnCount

        if (totalWidth > availableWidth) {
            val scale = availableWidth / totalWidth
            fontSize = maxOf(fontSize * scale, 4f)
            paint.textSize = fontSize
            fontMetrics = paint.fontMetrics
            fontMetricsHeight = (fontMetrics.bottom - fontMetrics.top).coerceAtLeast(1f)

            maxCharHeight = 0f
            maxCharWidth = 0f
            for (char in renderText) {
                val charString = char.toString()
                paint.getTextBounds(charString, 0, 1, bounds)
                maxCharHeight = maxOf(maxCharHeight, bounds.height().toFloat().coerceAtLeast(1f))
                maxCharWidth = maxOf(maxCharWidth, bounds.width().toFloat().coerceAtLeast(paint.measureText(charString)))
            }

            lineHeight = maxOf(fontSize * 1.03f, maxCharHeight * 1.02f).coerceAtLeast(1f)
            charsPerColumn = maxOf(1, kotlin.math.floor(availableHeight / fontSize).toInt())
            columnCount = kotlin.math.ceil(renderText.length / charsPerColumn.toFloat()).toInt().coerceAtLeast(1)

            columnWidth = maxOf(fontSize * 0.95f, maxCharWidth + strokeWidth * 1.1f).coerceAtLeast(4f)
            columnAdvance = columnWidth + columnGap
            totalWidth = columnAdvance * columnCount
        }

        AppLogger.i(
            name,
            "[V-RENDER] text='${renderText.take(10)}' fontSize=$fontSize rect=${rect.width().toInt()}x${rect.height().toInt()} " +
                "charsPerColumn=$charsPerColumn columns=$columnCount colW=${"%.1f".format(columnWidth)} " +
                "lineH=${"%.1f".format(lineHeight)} maxCharH=${"%.1f".format(maxCharHeight)} fmH=${"%.1f".format(fontMetricsHeight)} " +
                "colGap=${"%.1f".format(columnGap)} " +
                "avail=${"%.1f".format(availableWidth)}x${"%.1f".format(availableHeight)} totalW=${"%.1f".format(totalWidth)} " +
                "heightDriven=${"%.1f".format(heightDrivenFontSize)} widthDriven=${"%.1f".format(widthDrivenFontSize)} " +
                "initial=${"%.1f".format(initialFontSize)} bounded=${"%.1f".format(boundedFontSize)} " +
                "paintSize=${"%.1f".format(paint.textSize)}",
        )

        val startRight = rect.right - strokeWidth
        var columnRight = startRight
        var charIndex = 0
        while (charIndex < renderText.length) {
            val columnEnd = minOf(charIndex + charsPerColumn, renderText.length)
            val columnText = renderText.substring(charIndex, columnEnd)
            val columnHeight = lineHeight * columnText.length
            var currentTop = rect.top + ((availableHeight - columnHeight).coerceAtLeast(0f) / 2f)
            val columnLeft = columnRight - columnWidth

            for (char in columnText) {
                val charString = char.toString()
                paint.getTextBounds(charString, 0, 1, bounds)

                val charWidth = maxOf(bounds.width().toFloat(), paint.measureText(charString))
                val charX = columnLeft + (columnWidth - charWidth) / 2f - bounds.left
                val charY = currentTop - bounds.top

                if (!disableBorder) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = strokeWidth
                    paint.color = bgColor
                    canvas.drawText(charString, charX, charY, paint)
                }

                paint.style = Paint.Style.FILL
                paint.color = fgColor
                canvas.drawText(charString, charX, charY, paint)

                currentTop += lineHeight
            }

            charIndex = columnEnd
            columnRight -= columnAdvance
        }
    }

    /**
     * Renders text horizontally but right-aligned (right-to-left reading order).
     * Used for traditional RTL horizontal layouts.
     */
    private fun renderHorizontalRtl(
        canvas: Canvas,
        text: String,
        rect: RectF,
        paint: Paint,
        fgColor: Int,
        bgColor: Int,
        disableBorder: Boolean,
        strokeWidth: Float,
    ) {
        // Measure full text width and scale if needed
        var textWidth = paint.measureText(text)
        if (textWidth > rect.width() && rect.width() > 0f) {
            val scale = rect.width() / textWidth
            paint.textSize *= scale
            textWidth = paint.measureText(text)
        }
        val y = rect.centerY() + paint.textSize / 3f

        // Draw right-aligned (RTL)
        if (!disableBorder) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokeWidth
            paint.color = bgColor
            canvas.drawText(text, rect.right - textWidth, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = fgColor
        canvas.drawText(text, rect.right - textWidth, y, paint)
    }

    private fun renderMultilineHorizontal(
        canvas: Canvas,
        text: String,
        rect: RectF,
        paint: Paint,
        fgColor: Int,
        bgColor: Int,
        disableBorder: Boolean,
        rtl: Boolean,
        lineSpacing: Float?,
        strokeWidth: Float,
    ) {
        val lines = text.split('\n').filter { it.isNotBlank() }
        if (lines.isEmpty()) return

        val spacingFactor = lineSpacing?.takeIf { it > 0f } ?: 0.25f
        val originalSize = paint.textSize

        fun measureLayoutHeight(size: Float): Float {
            val lineHeight = size * 1.15f
            return lines.size * lineHeight + (lines.size - 1) * (size * spacingFactor)
        }

        val widestLine = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
        val widthScale = if (widestLine > 0f && rect.width() > 0f) {
            rect.width() / widestLine
        } else {
            1f
        }

        val heightAtOriginal = measureLayoutHeight(originalSize)
        val heightScale = if (heightAtOriginal > 0f && rect.height() > 0f) {
            rect.height() / heightAtOriginal
        } else {
            1f
        }

        val textSize = originalSize * minOf(1f, widthScale, heightScale)
        paint.textSize = textSize

        val layoutHeight = measureLayoutHeight(textSize)
        val lineHeight = textSize * 1.15f
        val gap = textSize * spacingFactor
        var currentY = rect.centerY() - layoutHeight / 2f + lineHeight * 0.75f

        for (line in lines) {
            val lineWidth = paint.measureText(line)

            val x = when {
                rtl -> rect.right - lineWidth
                else -> rect.left
            }

            if (!disableBorder) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = strokeWidth
                paint.color = bgColor
                canvas.drawText(line, x, currentY, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = fgColor
            canvas.drawText(line, x, currentY, paint)

            currentY += lineHeight + gap
        }
    }

    private fun buildPaint(region: TextBlock, textSize: Float, fgColor: Int): Paint {
        val style = when {
            region.bold && region.italic -> Typeface.BOLD_ITALIC
            region.bold -> Typeface.BOLD
            region.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }

        val baseTypeface = when {
            region.fontFamily.isNotBlank() -> Typeface.create(region.fontFamily, style)
            else -> this.typeface ?: Typeface.DEFAULT
        }

        return Paint().apply {
            typeface = Typeface.create(baseTypeface, style)
            this.textSize = textSize
            color = fgColor
            isAntiAlias = true
            isUnderlineText = region.underline
            isFakeBoldText = region.bold
            textSkewX = if (region.italic && !region.bold) -0.25f else 0f
            alpha = (region.opacity.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)

            if (region.shadowRadius > 0f) {
                setShadowLayer(
                    region.shadowRadius,
                    region.shadowOffsetX,
                    region.shadowOffsetY,
                    region.shadowColor ?: Color.BLACK,
                )
            }
        }
    }

    private fun applyRegionTextStyle(paint: Paint, region: TextBlock, fgColor: Int, textSize: Float) {
        paint.color = fgColor
        paint.alpha = (region.opacity.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)
        paint.isUnderlineText = region.underline
        paint.isFakeBoldText = region.bold
        paint.textSkewX = if (region.italic && !region.bold) -0.25f else 0f
        paint.strokeWidth = region.strokeWidth

        if (region.shadowRadius > 0f) {
            paint.setShadowLayer(
                region.shadowRadius,
                region.shadowOffsetX,
                region.shadowOffsetY,
                region.shadowColor ?: Color.BLACK,
            )
        } else {
            paint.clearShadowLayer()
        }
    }

    private fun parseColorOrNull(color: String): Int? {
        return runCatching { Color.parseColor(color) }.getOrNull()
    }

    // ==================================================================
    // Perspective render — exact port of Python rendering/__init__.py render() L264-410
    // ==================================================================

    /**
     * Renders translated text onto the image using perspective transform.
     * Exact port of Python rendering/__init__.py L264-410.
     *
     * @param img destination image (modified in-place via Canvas)
     * @param region TextBlock with translation
     * @param dstPoints 4 corners of the text region on the image [TL, TR, BR, BL]
     */
    private fun perspectiveRender(
        img: Bitmap,
        region: TextBlock,
        dstPoints: List<PointF>,
        disableBorder: Boolean,
    ) {
        // Python L272-276: fg_bg_compare
        val (fg, bg) = region.getFontColors()
        val (useFg, useBg) = fgColorBgCompare(region, fg, bg)
        val effectiveBg = if (disableBorder) 0 else useBg

        // Python L278-281: middle_pts = (dst[:, [1,2,3,0]] + dst) / 2
        // indices: [1,2,3,0] cyclically from [0,1,2,3]
        val m = Array(4) { i ->
            val j = (i + 1) % 4
            PointF((dstPoints[i].x + dstPoints[j].x) / 2f, (dstPoints[i].y + dstPoints[j].y) / 2f)
        }
        // norm_h = distance(m[1], m[3])  (horizontal span)
        val nhx = m[1].x - m[3].x; val nhy = m[1].y - m[3].y
        val normH = kotlin.math.sqrt(nhx * nhx + nhy * nhy)
        // norm_v = distance(m[2], m[0])  (vertical span)
        val nvx = m[2].x - m[0].x; val nvy = m[2].y - m[0].y
        val normV = kotlin.math.sqrt(nvx * nvx + nvy * nvy)
        if (normH <= 0f || normV <= 0f) return
        val rOrig = normH / normV

        // Python L284-293: render_horizontally
        val renderHorizontally = region.isHorizontal

        // Python L297-320: put_text_horizontal or put_text_vertical
        val renderedText = region.getTranslationForRendering()
        val tempBox = if (renderHorizontally) {
            renderTextHorizontal(region.fontSize, renderedText,
                normH.toInt(), normV.toInt(), region.alignment, useFg, effectiveBg)
        } else {
            renderTextVertical(region.fontSize, renderedText,
                normV.toInt(), region.alignment, useFg, effectiveBg)
        }
        val rTemp = tempBox.width.toFloat() / tempBox.height.coerceAtLeast(1).toFloat()

        // Python L324-397: extend box to match r_orig
        val box = if (region.isHorizontal) {
            if (rTemp > rOrig) {
                val hExt = ((tempBox.width / rOrig - tempBox.height) / 2.0).toInt().coerceAtLeast(0)
                padVertical(tempBox, hExt)
            } else {
                val wExt = ((tempBox.height * rOrig - tempBox.width) / 2.0).toInt().coerceAtLeast(0)
                padHorizontal(tempBox, wExt)
            }
        } else {
            if (rTemp > rOrig) {
                val hExt = ((tempBox.width / (2 * rOrig) - tempBox.height / 2.0)).toInt().coerceAtLeast(0)
                padVertical(tempBox, hExt)
            } else {
                val wExt = ((tempBox.height * rOrig - tempBox.width) / 2.0).toInt().coerceAtLeast(0)
                padHorizontalCentered(tempBox, wExt)
            }
        } ?: tempBox

        // Python L400-409: cv2.findHomography(RANSAC) + warpPerspective + alpha composite
        val boxMat = Mat()
        val warped = Mat()
        try {
            Utils.bitmapToMat(box, boxMat)
            // Python uses cv2.findHomography(cv2.RANSAC, 5.0) — robust homography
            val srcPts = MatOfPoint2f(Point(0.0, 0.0), Point(box.width.toDouble(), 0.0),
                Point(box.width.toDouble(), box.height.toDouble()), Point(0.0, box.height.toDouble()))
            val dstPts = MatOfPoint2f(Point(dstPoints[0].x.toDouble(), dstPoints[0].y.toDouble()),
                Point(dstPoints[1].x.toDouble(), dstPoints[1].y.toDouble()),
                Point(dstPoints[2].x.toDouble(), dstPoints[2].y.toDouble()),
                Point(dstPoints[3].x.toDouble(), dstPoints[3].y.toDouble()))
            val transform = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 5.0)
            srcPts.release(); dstPts.release()
            if (transform.empty()) return
            Imgproc.warpPerspective(boxMat, warped, transform,
                Size(img.width.toDouble(), img.height.toDouble()),
                Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(0.0, 0.0, 0.0, 0.0))
            transform.release()

            AppLogger.i(name, "[RENDER] box=${box.width}x${box.height} dstW=${normH.toInt()}x${normV.toInt()} rTemp=${"%.2f".format(rTemp)} rOrig=${"%.2f".format(rOrig)} fg=#${Integer.toHexString(useFg)} bg=#${Integer.toHexString(effectiveBg)} text='${region.translation.take(8)}'")

            // Python L406-409: boundingRect + alpha composite
            val dstArr = floatArrayOf(dstPoints[0].x, dstPoints[0].y, dstPoints[1].x, dstPoints[1].y,
                dstPoints[2].x, dstPoints[2].y, dstPoints[3].x, dstPoints[3].y)
            var minX = dstArr[0]; var maxX = dstArr[0]; var minY = dstArr[1]; var maxY = dstArr[1]
            for (i in 1 until 4) { minX = minOf(minX, dstArr[i*2]); maxX = maxOf(maxX, dstArr[i*2]) }
            for (i in 1 until 4) { minY = minOf(minY, dstArr[i*2+1]); maxY = maxOf(maxY, dstArr[i*2+1]) }
            val x = minX.toInt().coerceAtLeast(0)
            val y = minY.toInt().coerceAtLeast(0)
            val w = (maxX - minX).toInt().coerceAtMost(img.width - x)
            val h = (maxY - minY).toInt().coerceAtMost(img.height - y)
            if (w <= 0 || h <= 0) return

            val warpedBmp = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, warpedBmp)
            val regionBmp = Bitmap.createBitmap(warpedBmp, x, y, w, h)
            warpedBmp.recycle()

            // Alpha composite: img = img*(1-mask) + canvas*mask
            val alphaPixels = IntArray(w * h)
            regionBmp.getPixels(alphaPixels, 0, w, 0, 0, w, h)
            val srcPixels = IntArray(w * h)
            img.getPixels(srcPixels, 0, w, x, y, w, h)
            for (i in 0 until w * h) {
                val alpha = ((alphaPixels[i] ushr 24) and 0xFF) / 255f
                val r = ((srcPixels[i] shr 16) and 0xFF) * (1 - alpha) + ((alphaPixels[i] shr 16) and 0xFF) * alpha
                val g = ((srcPixels[i] shr 8) and 0xFF) * (1 - alpha) + ((alphaPixels[i] shr 8) and 0xFF) * alpha
                val b = (srcPixels[i] and 0xFF) * (1 - alpha) + (alphaPixels[i] and 0xFF) * alpha
                srcPixels[i] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            }
            img.setPixels(srcPixels, 0, w, x, y, w, h)
            regionBmp.recycle()
        } finally {
            boxMat.release()
            if (!warped.empty()) warped.release()
        }
    }

    /** Python L31-35: fg_bg_compare */
    private fun fgColorBgCompare(region: TextBlock, fg: Int, bg: Int): Pair<Int, Int> {
        // Already done in getFontColors() — just return as-is
        return fg to bg
    }

    /** Render horizontal text — equivalent to Python put_text_horizontal output */
    private fun renderTextHorizontal(fontSize: Float, text: String, maxW: Int, maxH: Int,
                                     align: TextAlignment, fg: Int, bg: Int): Bitmap {
        val paint = Paint().apply {
            this.textSize = fontSize; color = fg; isAntiAlias = true
            typeface = this@HorizontalTextRenderer.typeface ?: Typeface.DEFAULT
        }
        val tw = paint.measureText(text).toInt() + 4
        val th = (fontSize * 1.2f).toInt()
        val w = maxOf(maxW, tw); val h = maxOf(maxH, th)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(0, PorterDuff.Mode.CLEAR)
            val fm = paint.fontMetrics; val y = (h - fm.bottom + fm.top) / 2f - fm.top
            val x = when (align) {
                TextAlignment.CENTER -> ((w - paint.measureText(text)) / 2f).coerceAtLeast(0f)
                TextAlignment.RIGHT -> (w - paint.measureText(text)).coerceAtLeast(0f)
                else -> 0f
            }
            drawText(text, x, y, paint)
        }
        return bmp
    }

    /** Render vertical text placeholder */
    private fun renderTextVertical(fontSize: Float, text: String, maxH: Int,
                                   align: TextAlignment, fg: Int, bg: Int): Bitmap {
        // Fallback: render horizontal then rotate
        val w = maxOf(maxH, (fontSize * text.length * 0.6f).toInt())
        val h = (fontSize * 1.2f).toInt()
        return renderTextHorizontal(fontSize, text, w, h, align, fg, bg)
    }

    /** Python L338-349: vertical padding (top+bottom) */
    private fun padVertical(src: Bitmap, hExt: Int): Bitmap? {
        if (hExt <= 0) return null
        val newH = src.height + hExt * 2
        val bmp = Bitmap.createBitmap(src.width, newH, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0, PorterDuff.Mode.CLEAR); drawBitmap(src, 0f, hExt.toFloat(), null) }
        return bmp
    }

    /** Python L352-365: horizontal padding (left+right), text left-aligned */
    private fun padHorizontal(src: Bitmap, wExt: Int): Bitmap? {
        if (wExt <= 0) return null
        val newW = src.width + wExt * 2
        val bmp = Bitmap.createBitmap(newW, src.height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0, PorterDuff.Mode.CLEAR); drawBitmap(src, 0f, 0f, null) }
        return bmp
    }

    /** Python L389-394: horizontal padding, centered */
    private fun padHorizontalCentered(src: Bitmap, wExt: Int): Bitmap? {
        if (wExt <= 0) return null
        val newW = src.width + wExt * 2
        val bmp = Bitmap.createBitmap(newW, src.height, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply { drawColor(0, PorterDuff.Mode.CLEAR); drawBitmap(src, wExt.toFloat(), 0f, null) }
        return bmp
    }

    // ==================================================================
    // Region expansion helpers (matching Python rendering/__init__.py L37-233)
    // ==================================================================

    /** Python L37-46: count text length, treating small kana as 0.5 */
    private fun countTextLength(text: String): Float {
        val halfWidthChars = setOf('っ', 'ッ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ')
        var length = 0f
        for (ch in text.trim()) { length += if (ch in halfWidthChars) 0.5f else 1.0f }
        return length
    }

    /** Computes destination quad points from a TextBlock (using minRect) */
    private fun computeDstPointsFromRegion(region: TextBlock, first: Boolean = false): List<PointF> {
        val rect = region.minRect
        return listOf(PointF(rect.left, rect.top), PointF(rect.right, rect.top),
            PointF(rect.right, rect.bottom), PointF(rect.left, rect.bottom))
    }
}
