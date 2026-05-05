package com.sakuravillager.manga_translator.translation.sort

import android.graphics.Bitmap
import android.graphics.RectF
import com.sakuravillager.manga_translator.translation.data.TextBlock

object RegionSorter {
    fun sortRegions(
        regions: List<TextBlock>,
        rightToLeft: Boolean = true,
        image: Bitmap? = null,
        forceSimpleSort: Boolean = false,
    ): List<TextBlock> {
        if (regions.isEmpty()) return emptyList()
        if (forceSimpleSort) return simpleSort(regions, rightToLeft)

        if (regions.size == 1) return regions

        if (image != null) {
            val panels = PanelLayoutDetector.detectPanels(image)
            if (panels.isNotEmpty()) {
                return sortByPanels(regions, panels, rightToLeft)
            }
        }

        val xs = regions.map { it.center.x }
        val ys = regions.map { it.center.y }
        val xStd = standardDeviation(xs)
        val yStd = standardDeviation(ys)
        val isHorizontal = xStd > yStd

        val sortedRegions = mutableListOf<TextBlock>()
        if (isHorizontal) {
            val primary = if (rightToLeft) {
                regions.sortedByDescending { it.center.x }
            } else {
                regions.sortedBy { it.center.x }
            }
            val gapThreshold = 20f
            val group = mutableListOf<TextBlock>()
            var previousX: Float? = null
            for (region in primary) {
                val cx = region.center.x
                if (previousX != null && kotlin.math.abs(cx - previousX) > gapThreshold) {
                    sortedRegions += group.sortedBy { it.center.y }
                    group.clear()
                }
                group.add(region)
                previousX = cx
            }
            if (group.isNotEmpty()) {
                sortedRegions += group.sortedBy { it.center.y }
            }
        } else {
            val primary = regions.sortedBy { it.center.y }
            val gapThreshold = 15f
            val group = mutableListOf<TextBlock>()
            var previousY: Float? = null
            for (region in primary) {
                val cy = region.center.y
                if (previousY != null && kotlin.math.abs(cy - previousY) > gapThreshold) {
                    sortedRegions += sortWithinRow(group, rightToLeft)
                    group.clear()
                }
                group.add(region)
                previousY = cy
            }
            if (group.isNotEmpty()) {
                sortedRegions += sortWithinRow(group, rightToLeft)
            }
        }

        return sortedRegions
    }

    private fun sortByPanels(
        regions: List<TextBlock>,
        panels: List<RectF>,
        rightToLeft: Boolean,
    ): List<TextBlock> {
        val assignments = regions.map { region ->
            val center = region.center
            val matchedIndex = panels.indexOfFirst { panel ->
                panel.contains(center.x, center.y)
            }.takeIf { it >= 0 } ?: panels.indices.minByOrNull { index ->
                distanceToRect(center.x, center.y, panels[index])
            } ?: 0
            matchedIndex to region
        }

        val grouped = assignments.groupBy({ it.first }, { it.second })
        val orderedPanels = sortPanelsFill(panels, rightToLeft)

        val result = mutableListOf<TextBlock>()
        for (panel in orderedPanels) {
            val panelIndex = panels.indexOf(panel)
            val panelRegions = grouped[panelIndex].orEmpty()
            if (panelRegions.isNotEmpty()) {
                result += sortRegions(panelRegions, rightToLeft = rightToLeft, image = null, forceSimpleSort = false)
            }
        }

        val unassigned = grouped.filterKeys { it !in panels.indices }.values.flatten()
        if (unassigned.isNotEmpty()) {
            result += sortRegions(unassigned, rightToLeft = rightToLeft, image = null, forceSimpleSort = false)
        }

        return result
    }

    private fun sortPanelsFill(panels: List<RectF>, rightToLeft: Boolean): List<RectF> {
        if (panels.isEmpty()) return emptyList()

        val remaining = panels.sortedBy { it.top }.toMutableList()
        val ordered = mutableListOf<RectF>()
        val avgHeight = remaining.map { it.height() }.average().toFloat().coerceAtLeast(1f)
        val yThreshold = maxOf(10f, avgHeight * 0.3f)

        while (remaining.isNotEmpty()) {
            val baseY = remaining.first().top
            val row = mutableListOf<RectF>()
            var index = 0
            while (index < remaining.size) {
                if (kotlin.math.abs(remaining[index].top - baseY) <= yThreshold) {
                    row.add(remaining.removeAt(index))
                } else {
                    index++
                }
            }
            row.sortBy { if (rightToLeft) -it.left else it.left }
            ordered += row
        }

        return ordered
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

    private fun simpleSort(regions: List<TextBlock>, rightToLeft: Boolean): List<TextBlock> {
        val sortedRegions = mutableListOf<TextBlock>()
        for (region in regions.sortedBy { it.center.y }) {
            var inserted = false
            for (index in sortedRegions.indices) {
                val sortedRegion = sortedRegions[index]
                if (region.center.y > sortedRegion.minRect.bottom) {
                    continue
                }
                if (region.center.y < sortedRegion.minRect.top) {
                    sortedRegions.add(index, region)
                    inserted = true
                    break
                }
                if (rightToLeft && region.center.x > sortedRegion.center.x) {
                    sortedRegions.add(index, region)
                    inserted = true
                    break
                }
                if (!rightToLeft && region.center.x < sortedRegion.center.x) {
                    sortedRegions.add(index, region)
                    inserted = true
                    break
                }
            }
            if (!inserted) {
                sortedRegions.add(region)
            }
        }
        return sortedRegions
    }

    private fun sortWithinRow(group: List<TextBlock>, rightToLeft: Boolean): List<TextBlock> {
        return if (rightToLeft) {
            group.sortedByDescending { it.center.x }
        } else {
            group.sortedBy { it.center.x }
        }
    }

    private fun standardDeviation(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val mean = values.average().toFloat()
        val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
        return kotlin.math.sqrt(variance)
    }
}
