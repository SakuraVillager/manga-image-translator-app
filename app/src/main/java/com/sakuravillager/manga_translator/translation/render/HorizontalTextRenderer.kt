package com.sakuravillager.manga_translator.translation.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import com.sakuravillager.manga_translator.translation.api.TextRenderer
import com.sakuravillager.manga_translator.translation.data.TextAlignment
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.data.config.RendererConfig

/**
 * Horizontal text renderer that draws translated text onto a bitmap copy
 * using Android [Canvas] and [Paint] APIs.
 *
 * Supports font border (stroke) via a two-pass draw for API 28+ compatibility,
 * font scaling when text exceeds the bounding rect, and configurable alignment.
 */
class HorizontalTextRenderer(
    private val context: Context,
) : TextRenderer {

    override val name: String = "HorizontalTextRenderer"
    private var _isReady = false
    override val isReady: Boolean get() = _isReady

    private var typeface: Typeface = Typeface.DEFAULT

    override suspend fun prepare() {
        typeface = try {
            Typeface.createFromAsset(context.assets, "fonts/NotoSansCJK-Regular.ttc")
        } catch (_: Exception) {
            try {
                Typeface.createFromAsset(context.assets, "fonts/NotoSansCJK-Regular.ttf")
            } catch (_: Exception) {
                Log.w(name, "CJK font not found, falling back to system default")
                Typeface.DEFAULT
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
}
