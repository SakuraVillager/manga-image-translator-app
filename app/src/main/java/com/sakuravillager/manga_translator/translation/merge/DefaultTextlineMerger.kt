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

        // Direction: majority vote (exclude AUTO)
        val directions = quads.map { it.direction }.filter { it != TextDirection.AUTO }
        val direction = if (directions.isEmpty()) {
            TextDirection.AUTO
        } else {
            directions.groupBy { it }.maxByOrNull { it.value.size }!!.key
        }

        // Sort: horizontal → by centroid.y ascending; vertical → by centroid.x descending
        val sorted = if (direction == TextDirection.AUTO ||
            direction == TextDirection.HORIZONTAL ||
            direction == TextDirection.HORIZONTAL_RTL
        ) {
            quads.sortedBy { it.center.y }
        } else {
            quads.sortedByDescending { it.center.x }
        }

        // Font size = minimum of all quads in the region
        val fontSize = sorted.minOf { it.fontSize }

        // Angle = average quad angle - 90° (converts quad angle to text baseline)
        val angle = (sorted.map { it.angle }.average()).toFloat() - (PI / 2.0).toFloat()
        // Snap near-zero angles to zero
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

        return TextBlock(
            lines = sorted.map { it.points },
            texts = texts,
            text = text,
            fontSize = fontSize,
            angle = finalAngle,
            fgColor = sorted.firstOrNull()?.fgColor,
            bgColor = sorted.firstOrNull()?.bgColor,
            direction = direction,
        )
    }
}
