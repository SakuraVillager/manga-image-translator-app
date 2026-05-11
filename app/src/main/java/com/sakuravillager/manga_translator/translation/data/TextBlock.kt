package com.sakuravillager.manga_translator.translation.data

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs

data class TextBlock(
    val lines: List<List<PointF>> = emptyList(),
    val texts: List<String> = emptyList(),
    val text: String = "",
    val textRaw: String = "",
    val translation: String = "",
    val language: String? = null,
    val sourceLanguage: String? = null,
    val targetLanguage: String? = null,
    val fontSize: Float = 0f,
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
    private var _alignment: TextAlignment = TextAlignment.AUTO
    val alignment: TextAlignment get() {
        if (_alignment != TextAlignment.AUTO) return _alignment
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
        val rad = Math.toRadians(-angle.toDouble())
        val cos = cos(rad).toFloat()
        val sin = sin(rad).toFloat()
        return lines.map { line ->
            line.map { p ->
                val dx = p.x - cx
                val dy = p.y - cy
                PointF(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
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
            val rad = Math.toRadians(angle.toDouble())
            val cos = cos(rad).toFloat(); val sin = sin(rad).toFloat()
            val corners = listOf(
                PointF(minX, minY), PointF(maxX, minY),
                PointF(maxX, maxY), PointF(minX, maxY),
            ).map { p ->
                val dx = p.x - cx; val dy = p.y - cy
                PointF(cx + dx * cos - dy * sin, cy + dx * sin + dy * cos)
            }
            return RectF(
                corners.minOf { it.x }.coerceAtLeast(0f),
                corners.minOf { it.y }.coerceAtLeast(0f),
                corners.maxOf { it.x },
                corners.maxOf { it.y },
            )
        }
        return RectF(minX, minY, maxX, maxY)
    }

    val center: PointF get() {
        val r = minRect
        return PointF(r.centerX(), r.centerY())
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

    val strokeWidth: Float get() {
        val fg = fgColor ?: 0
        val bg = bgColor ?: 0xFFFFFF
        val diff = colorDifference(fg, bg)
        return if (diff > 15f) defaultStrokeWidth else 0f
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
        val r1 = (rgb1 shr 16) and 0xFF
        val g1 = (rgb1 shr 8) and 0xFF
        val b1 = rgb1 and 0xFF
        val r2 = (rgb2 shr 16) and 0xFF
        val g2 = (rgb2 shr 8) and 0xFF
        val b2 = rgb2 and 0xFF
        return Math.sqrt(
            ((r1 - r2) * (r1 - r2) + (g1 - g2) * (g1 - g2) + (b1 - b2) * (b1 - b2)).toDouble()
        ).toFloat()
    }

    companion object {
        const val defaultStrokeWidth: Float = 0.2f

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
