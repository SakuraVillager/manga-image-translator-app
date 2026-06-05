package com.sakuravillager.manga_translator.translation.merge

import com.sakuravillager.manga_translator.translation.api.TextlineMerger
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextBlock
import com.sakuravillager.manga_translator.translation.data.TextDirection
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.PI

/**
 * Default implementation of [TextlineMerger] that groups detected textlines
 * into coherent [TextBlock] regions.
 *
 * Algorithm:
 * 1. Build an adjacency graph using [quadrilateralCanMergeRegion]
 * 2. Extract connected components via [UnionFind]
 * 3. Split each component with MST-based clustering ([splitTextRegion])
 * 4. Build [TextBlock] from each final region with aggregated properties
 */
class DefaultTextlineMerger : TextlineMerger {

    override val name: String = "DefaultTextlineMerger"

    override val isReady: Boolean get() = true

    override suspend fun prepare() {
        // Pure algorithm — no resources to prepare
    }

    override suspend fun release() {
        // Pure algorithm — no resources to release
    }

    override suspend fun merge(
        textlines: List<Quadrilateral>,
        imageWidth: Int,
        imageHeight: Int,
    ): List<TextBlock> {
        if (textlines.isEmpty()) return emptyList()
        if (textlines.size == 1) return listOf(buildTextBlock(textlines, listOf(0)))

        // Step 1: Build adjacency graph using merge predicates
        val n = textlines.size
        val uf = UnionFind(n)
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (quadrilateralCanMergeRegion(textlines[i], textlines[j])) {
                    uf.union(i, j)
                }
            }
        }

        // Step 2: Get connected components from the graph
        val components = uf.components()

        // Step 3: Refine each component with MST-based splitting
        val textRegions = mutableListOf<Set<Int>>()
        for (comp in components) {
            val split = splitTextRegion(textlines, comp.toSet(), imageWidth, imageHeight)
            textRegions.addAll(split)
        }

        // Step 4: Build TextBlock from each final region
        return textRegions.map { indices ->
            buildTextBlock(textlines, indices.toList())
        }
    }

    /**
     * Builds a single [TextBlock] from a group of quadrilaterals.
     *
     * Aggregates properties:
     * - **Direction**: majority vote among non-AUTO directions
     * - **Font size**: minimum of all quads in the region
     * - **Angle**: average angle minus 90° (text baseline convention)
     * - **Probability**: area-weighted geometric mean
     * - **Text**: concatenation of sorted quads' text
     */
    private fun buildTextBlock(allLines: List<Quadrilateral>, indices: List<Int>): TextBlock {
        val quads = indices.map { allLines[it] }

        val direction = resolveDirection(quads)

        val sorted = sortForTextBlock(quads, direction)

        // Font size = minimum of all quads in the region
        val fontSize = sorted.minOf { it.fontSize }

        // Angle = average quad angle - PI/2 radians (converts quad orientation angle to text baseline angle).
        // Quad angle 0 = horizontal → baseline angle 0 - PI/2 = -PI/2 (vertical text baseline).
        // Subtraction aligns the convention so that horizontal text has angle ≈ 0.
        val angle = (sorted.map { it.angle }.average()).toFloat() - (PI / 2.0).toFloat()
        // Snap near-zero angles to zero (within 3 degrees)
        val finalAngle = if (abs(angle) < (3f * PI.toFloat() / 180f)) 0f else angle

        // Probability = area-weighted geometric mean of quad probabilities
        val totalArea = sorted.sumOf { it.area.toDouble() }
        val weightedLogProb = sorted.sumOf { quad ->
            if (quad.probability > 0f) {
                quad.area.toDouble() * ln(quad.probability.toDouble())
            } else {
                0.0
            }
        }
        val prob = if (totalArea > 0f) {
            exp(weightedLogProb / totalArea).toFloat()
        } else {
            0f
        }

        // Text: concatenation of sorted quad texts
        val texts = sorted.map { it.text }
        val text = texts.joinToString("")

        // fg/bg color: mean of all quads (matching Python np.mean)
        val quadsWithFg = sorted.filter { it.fgColor != null }
        val avgFg = if (quadsWithFg.isNotEmpty()) {
            val avgR = quadsWithFg.map { (it.fgColor!! shr 16) and 0xFF }.average().toInt()
            val avgG = quadsWithFg.map { (it.fgColor!! shr 8) and 0xFF }.average().toInt()
            val avgB = quadsWithFg.map { it.fgColor!! and 0xFF }.average().toInt()
            (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
        } else sorted.firstOrNull()?.fgColor
        val quadsWithBg = sorted.filter { it.bgColor != null }
        val avgBg = if (quadsWithBg.isNotEmpty()) {
            val avgR = quadsWithBg.map { (it.bgColor!! shr 16) and 0xFF }.average().toInt()
            val avgG = quadsWithBg.map { (it.bgColor!! shr 8) and 0xFF }.average().toInt()
            val avgB = quadsWithBg.map { it.bgColor!! and 0xFF }.average().toInt()
            (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
        } else sorted.firstOrNull()?.bgColor

        return TextBlock(
            lines = sorted.map { it.points },
            texts = texts,
            text = text,
            // textRaw preserves original OCR text for renderer expansion calculations.
            // text may be modified by pre-dict/brackets later; textRaw stays original.
            textRaw = text,
            fontSize = fontSize,
            angle = finalAngle,
            probability = prob,
            fgColor = avgFg,
            bgColor = avgBg,
            _direction = direction,
        )
    }

    private fun sortForTextBlock(quads: List<Quadrilateral>, direction: TextDirection): List<Quadrilateral> {
        val hasReadingOrder = quads.any { it.readingOrderIndex != null }
        if (hasReadingOrder) {
            return quads.sortedWith(
                compareBy<Quadrilateral> { it.readingOrderIndex ?: Int.MAX_VALUE }
                    .thenBy { it.sourceIndex ?: Int.MAX_VALUE }
                    .thenBy {
                        if (direction == TextDirection.VERTICAL) {
                            -it.center.x
                        } else {
                            it.center.y
                        }
                    }
            )
        }

        return if (direction == TextDirection.VERTICAL) {
            quads.sortedWith(
                compareByDescending<Quadrilateral> { it.center.x }
                    .thenBy { it.center.y }
                    .thenBy { it.sourceIndex ?: Int.MAX_VALUE }
            )
        } else {
            quads.sortedWith(
                compareBy<Quadrilateral> { it.center.y }
                    .thenBy { it.center.x }
                    .thenBy { it.sourceIndex ?: Int.MAX_VALUE }
            )
        }
    }

    private fun resolveDirection(quads: List<Quadrilateral>): TextDirection {
        val explicit = quads.map { it.direction }.filter { it != TextDirection.AUTO }
        if (explicit.isEmpty()) {
            return if (quads.isNotEmpty() && quads.maxOf { it.aspectRatio } < 1f) {
                TextDirection.VERTICAL
            } else {
                TextDirection.HORIZONTAL
            }
        }

        val counts = explicit.groupingBy { it }.eachCount()
        val topTwo = counts.entries.sortedByDescending { it.value }.take(2)
        if (topTwo.size == 1 || topTwo[0].value != topTwo[1].value) {
            return topTwo.first().key
        }

        val best = quads.maxByOrNull {
            maxOf(it.aspectRatio, if (it.aspectRatio == 0f) 0f else 1f / it.aspectRatio)
        }
        return best?.direction?.takeIf { it != TextDirection.AUTO } ?: topTwo.first().key
    }
}
