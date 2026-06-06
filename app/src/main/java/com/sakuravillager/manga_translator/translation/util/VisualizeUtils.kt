package com.sakuravillager.manga_translator.translation.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.sort.PanelLayoutDetector

/**
 * Utility for drawing annotated debug visualizations of text block regions.
 * Matches Python's visualize_textblocks() behavior.
 */
object VisualizeUtils {

    /** Per-panel color palette. Indices 0-3 match the spec; 4+ extend for more panels. */
    private val PANEL_COLORS = intArrayOf(
        Color.rgb(255, 0, 0),      // 0: Red
        Color.rgb(0, 255, 0),      // 1: Green
        Color.rgb(0, 0, 255),      // 2: Blue
        Color.rgb(255, 136, 0),    // 3: Orange
        Color.rgb(255, 0, 255),    // 4: Magenta
        Color.rgb(0, 255, 255),    // 5: Cyan
        Color.rgb(255, 255, 0),    // 6: Yellow
        Color.rgb(128, 0, 128),    // 7: Purple
        Color.rgb(255, 105, 180),  // 8: Hot Pink
        Color.rgb(0, 128, 128),    // 9: Teal
        Color.rgb(128, 128, 0),    // 10: Olive
        Color.rgb(0, 0, 128),      // 11: Navy
    )

    /**
     * Draws colored bounding boxes with visual metadata for each TextBlock.
     * Matches Python's visualize_textblocks() behavior.
     *
     * @param bitmap Base image to draw on (will be copied)
     * @param textBlocks Sorted text regions with panelIndex
     * @param showPanels Whether to draw panel boundaries and use panel-based coloring
     * @param rightToLeft Whether to use RTL reading order indicators
     * @return New bitmap with visual annotations
     */
    fun visualizeTextBlocks(
        bitmap: Bitmap,
        textBlocks: List<TextBlock>,
        showPanels: Boolean = true,
        rightToLeft: Boolean = true,
    ): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        // Step 1: Detect panels and draw boundaries (if showPanels)
        val panels = if (showPanels) {
            val detected = PanelLayoutDetector.detectPanels(bitmap)
            drawPanelBoundaries(canvas, detected)
            detected
        } else {
            emptyList()
        }

        // Step 2: Assign panel index to each text block based on center point
        val blockPanels = if (showPanels && panels.isNotEmpty()) {
            textBlocks.map { block -> assignToPanel(block, panels) }
        } else {
            textBlocks.map { block -> block.panelIndex }
        }

        // Step 3: Draw each text region
        for ((index, block) in textBlocks.withIndex()) {
            val panelIdx = blockPanels[index]
            val color = getPanelColor(panelIdx)

            drawBoundingRect(canvas, block, color)
            drawLabel(canvas, block, index, panelIdx, color, showPanels)
            drawReadingArrow(canvas, block, color, rightToLeft)
        }

        return result
    }

    // ---- Private helpers ----

    /**
     * Draws dashed panel boundary rectangles.
     */
    private fun drawPanelBoundaries(canvas: Canvas, panels: List<RectF>) {
        val paint = Paint().apply {
            color = Color.argb(180, 128, 128, 128) // semi-transparent gray
            style = Paint.Style.STROKE
            strokeWidth = 2f
            pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            isAntiAlias = true
        }
        for (panel in panels) {
            canvas.drawRect(panel, paint)
        }
    }

    /**
     * Determines which panel a text block belongs to based on its center point.
     */
    private fun assignToPanel(block: TextBlock, panels: List<RectF>): Int {
        val center = block.center
        // First try exact containment
        val exactMatch = panels.indexOfFirst { panel ->
            panel.contains(center.x, center.y)
        }
        if (exactMatch >= 0) return exactMatch
        // Fall back to nearest panel
        return panels.indices.minByOrNull { index ->
            distanceToRect(center.x, center.y, panels[index])
        } ?: -1
    }

    private fun distanceToRect(x: Float, y: Float, rect: RectF): Float {
        val dx = when {
            x < rect.left -> rect.left - x
            x > rect.right -> x - rect.right
            else -> 0f
        }
        val dy = when {
            y < rect.top -> rect.top - y
            y > rect.bottom -> y - rect.bottom
            else -> 0f
        }
        return dx * dx + dy * dy
    }

    /**
     * Returns a color from the palette for the given panel index.
     * Cycles through the palette for indices beyond the predefined array size.
     * For unassigned blocks (panelIdx < 0), returns white.
     */
    private fun getPanelColor(panelIdx: Int): Int {
        if (panelIdx < 0) return Color.WHITE
        return PANEL_COLORS[panelIdx % PANEL_COLORS.size]
    }

    /**
     * Draws the bounding rectangle outline for a text block.
     * Uses the polygon lines if available, otherwise falls back to minRect.
     */
    private fun drawBoundingRect(canvas: Canvas, block: TextBlock, color: Int) {
        val paint = Paint().apply {
            this.color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 4f
            isAntiAlias = true
        }

        // Draw the actual polygon outlines if available
        for (line in block.lines) {
            if (line.size >= 3) {
                val path = Path()
                path.moveTo(line[0].x, line[0].y)
                for (i in 1 until line.size) {
                    path.lineTo(line[i].x, line[i].y)
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }

        // Also draw the outer bounding rect with a slightly thinner stroke for reference
        val rectPaint = Paint().apply {
            this.color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        val rect = block.minRect
        canvas.drawRect(rect, rectPaint)
    }

    /**
     * Draws the text label (region index + optional panel index) inside the bounding box.
     */
    private fun drawLabel(
        canvas: Canvas,
        block: TextBlock,
        index: Int,
        panelIdx: Int,
        color: Int,
        showPanels: Boolean,
    ) {
        val labelText = buildLabel(index, panelIdx, showPanels)
        val rect = block.minRect

        val textPaint = Paint().apply {
            this.color = color
            textSize = 22f
            isAntiAlias = true
            isFakeBoldText = true
        }
        val textWidth = textPaint.measureText(labelText)
        val textHeight = textPaint.textSize

        // Background behind the label text for readability
        val bgPaint = Paint().apply {
            this.color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val labelLeft = rect.left + 4f
        val labelTop = rect.top + 4f + textHeight

        canvas.drawRect(
            labelLeft - 2f,
            labelTop - textHeight + 2f,
            labelLeft + textWidth + 4f,
            labelTop + 2f,
            bgPaint,
        )

        canvas.drawText(labelText, labelLeft, labelTop, textPaint)
    }

    /**
     * Builds the display label string for a text block.
     */
    private fun buildLabel(index: Int, panelIdx: Int, showPanels: Boolean): String {
        return if (showPanels && panelIdx >= 0) {
            "${index}[P${panelIdx}]"
        } else {
            index.toString()
        }
    }

    /**
     * Draws a small arrow indicating the reading direction for RTL layout.
     * Arrow is placed near the top-right corner of the bounding box.
     */
    private fun drawReadingArrow(
        canvas: Canvas,
        block: TextBlock,
        color: Int,
        rightToLeft: Boolean,
    ) {
        if (!rightToLeft) return

        val rect = block.minRect
        val cx = rect.right - 14f
        val cy = rect.top + 14f

        val arrowPaint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // Left-pointing arrow (RTL reading direction indicator)
        val size = 8f
        val path = Path()
        path.moveTo(cx - size, cy)
        path.lineTo(cx + size, cy - size * 0.6f)
        path.lineTo(cx + size * 0.4f, cy)
        path.lineTo(cx + size, cy + size * 0.6f)
        path.close()

        canvas.drawPath(path, arrowPaint)
    }
}
