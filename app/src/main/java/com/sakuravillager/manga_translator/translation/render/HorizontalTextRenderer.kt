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
            // Skip regions without translation text
            if (region.translation.isEmpty()) continue

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

            // --- Paint setup ---
            val paint = Paint().apply {
                this.typeface = this@HorizontalTextRenderer.typeface
                this.textSize = textSize
                color = region.fgColor ?: Color.BLACK
                isAntiAlias = true
            }

            val fgColor = region.fgColor ?: Color.BLACK
            val bgColor = region.bgColor ?: Color.WHITE

            when (region.direction) {
                TextDirection.VERTICAL -> {
                    renderVerticalText(canvas, region.translation, rect, paint, fgColor, bgColor, config.disableFontBorder)
                    continue
                }
                TextDirection.HORIZONTAL_RTL -> {
                    renderHorizontalRtl(canvas, region.translation, rect, paint, fgColor, bgColor, config.disableFontBorder)
                    continue
                }
                else -> { /* continue with horizontal rendering */ }
            }

            // --- Measure & scale to fit ---
            var textWidth = paint.measureText(region.translation)
            if (textWidth > rect.width() && rect.width() > 0f) {
                val scaleFactor = rect.width() / textWidth
                textSize *= scaleFactor
                paint.textSize = textSize
                textWidth = paint.measureText(region.translation)
            }

            // --- Alignment ---
            val effectiveAlignment = when {
                region.alignment != TextAlignment.AUTO -> region.alignment
                config.alignment != TextAlignment.AUTO -> config.alignment
                else -> TextAlignment.LEFT
            }
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
                paint.strokeWidth = textSize * 0.05f
                paint.color = region.bgColor ?: Color.WHITE
                canvas.drawText(region.translation, x, y, paint)
            }
            // Second pass: fill (foreground)
            paint.style = Paint.Style.FILL
            paint.color = region.fgColor ?: Color.BLACK
            canvas.drawText(region.translation, x, y, paint)
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
                    paint.strokeWidth = fontSize * 0.05f
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
            paint.strokeWidth = paint.textSize * 0.05f
            paint.color = bgColor
            canvas.drawText(text, rect.right - textWidth, y, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = fgColor
        canvas.drawText(text, rect.right - textWidth, y, paint)
    }
}
