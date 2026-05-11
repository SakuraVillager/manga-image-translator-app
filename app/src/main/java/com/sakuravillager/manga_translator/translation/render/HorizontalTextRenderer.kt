package com.sakuravillager.manga_translator.translation.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
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

        for (region in textRegions) {
            val renderText = region.getTranslationForRendering()
            // Skip regions without translation text
            if (renderText.isEmpty()) continue

            val rect = region.minRect
            // Skip empty/invalid bounding rects
            if (rect.isEmpty) continue

            // --- Font size ---
            var textSize = region.fontSize + config.fontSizeOffset
            textSize = if (config.fontSizeMinimum > 0) {
                maxOf(textSize, config.fontSizeMinimum.toFloat())
            } else {
                maxOf(textSize, 1f)
            }

            val (regionFg, regionBg) = region.getFontColors()
            val fgColor = config.fontColor?.let { parseColorOrNull(it) } ?: regionFg
            val bgColor = regionBg
            val paint = buildPaint(region, textSize, fgColor)
            val effectiveLineSpacing = if (region.lineSpacing > 0f) region.lineSpacing else config.lineSpacing

            when (region.direction) {
                TextDirection.VERTICAL -> {
                    renderVerticalText(canvas, renderText, rect, paint, fgColor, bgColor, config.disableFontBorder, region.strokeWidth)
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
    ) {
        var fontSize = paint.textSize

        // Estimate max characters per column based on available height
        var maxCharsPerColumn =
            (rect.height() / (fontSize * 1.2f)).toInt().coerceAtLeast(1)
        var numColumns =
            kotlin.math.ceil(text.length.toFloat() / maxCharsPerColumn).toInt()
        val maxColumns =
            (rect.width() / (fontSize * 1.1f)).toInt().coerceAtLeast(1)

        // Scale down if too many columns for available width
        if (numColumns > maxColumns) {
            fontSize *= (maxColumns.toFloat() / numColumns.toFloat())
            paint.textSize = fontSize
            // Recompute after scaling
            maxCharsPerColumn =
                (rect.height() / (fontSize * 1.2f)).toInt().coerceAtLeast(1)
            numColumns =
                kotlin.math.ceil(text.length.toFloat() / maxCharsPerColumn).toInt()
        }

        // Draw characters top-to-bottom, columns right-to-left
        var colX = rect.right - fontSize
        var charIndex = 0
        while (charIndex < text.length) {
            var charY = rect.top + fontSize
            val columnEnd = minOf(charIndex + maxCharsPerColumn, text.length)
            while (charIndex < columnEnd) {
                val char = text[charIndex].toString()
                val fm = paint.fontMetrics
                val charHeight = (-fm.top + fm.bottom) * 1.2f

                // Border pass (stroke)
                if (!disableBorder) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = strokeWidth
                    paint.color = bgColor
                    canvas.drawText(char, colX, charY, paint)
                }
                // Fill pass (foreground)
                paint.style = Paint.Style.FILL
                paint.color = fgColor
                canvas.drawText(char, colX, charY, paint)

                charY += charHeight
                charIndex++
            }
            colX -= fontSize * 1.1f // Move to next column (leftwards)
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
}
