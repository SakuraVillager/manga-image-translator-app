package com.sakuravillager.manga_translator.translation.merge

import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Represents a weighted edge between two nodes in a graph used for MST construction.
 */
internal data class Edge(val from: Int, val to: Int, val weight: Float)

/**
 * Recursively splits a set of quadrilateral indices into text regions using
 * MST (Minimum Spanning Tree) cut clustering.
 *
 * Based on the Python split_text_region algorithm from textline_merge/__init__.py.
 *
 * @param bboxes All detected quadrilaterals
 * @param indices Indices within [bboxes] that belong to the current region
 * @param width Image width (unused in current implementation, reserved)
 * @param height Image height (unused in current implementation, reserved)
 * @param gamma Distance scaling factor for 2-node case
 * @param sigma Edge weight threshold factor (multiplied by average font size)
 * @return List of connected component index sets
 */
fun splitTextRegion(
    bboxes: List<Quadrilateral>,
    indices: Set<Int>,
    width: Int,
    height: Int,
    gamma: Float = 0.5f,
    sigma: Float = 2f,
): List<Set<Int>> {
    val nodes = indices.toList()

    return when (nodes.size) {
        0 -> emptyList()
        1 -> listOf(indices)
        2 -> {
            val first = bboxes[nodes[0]]
            val second = bboxes[nodes[1]]
            val fs = maxOf(first.fontSize, second.fontSize)
            if (first.distance(second) < (1f + gamma) * fs && abs(first.angle - second.angle) < 0.2f * kotlin.math.PI.toFloat()) {
                listOf(indices)
            } else {
                listOf(setOf(nodes[0]), setOf(nodes[1]))
            }
        }
        else -> {
            val allEdges = buildEdges(bboxes, nodes).sortedByDescending { it.weight }
            val mstEdges = kruskalMst(nodes, allEdges)
            if (mstEdges.isEmpty()) return listOf(indices)

            val distancesSorted = mstEdges.map { it.weight }.sortedDescending()
            val fontsize = nodes.map { bboxes[it].fontSize.toDouble() }.average().toFloat()
            val distancesStd = standardDeviation(distancesSorted)
            val distancesMean = distancesSorted.average().toFloat()
            val stdThreshold = max(0.3f * fontsize + 5f, 5f)

            val heaviest = mstEdges.maxByOrNull { it.weight } ?: return listOf(indices)
            val firstBox = bboxes[heaviest.from]
            val secondBox = bboxes[heaviest.to]
            val maxPolyDistance = firstBox.polyDistance(secondBox)
            val maxCentroidAlignment = min(
                abs(firstBox.center.x - secondBox.center.x),
                abs(firstBox.center.y - secondBox.center.y),
            )

            val keepTogether = (
                distancesSorted.first() <= distancesMean + distancesStd * sigma ||
                    distancesSorted.first() <= fontsize * (1f + gamma)
                ) && (
                    distancesStd < stdThreshold ||
                        (maxPolyDistance == 0f && maxCentroidAlignment < 5f)
                )

            if (keepTogether) {
                return listOf(indices)
            }

            val reduced = UnionFind(nodes.size)
            val nodeIndexMap = nodes.withIndex().associate { it.value to it.index }
            for (edge in mstEdges) {
                if (edge == heaviest) continue
                reduced.union(nodeIndexMap.getValue(edge.from), nodeIndexMap.getValue(edge.to))
            }

            val components = mutableMapOf<Int, MutableSet<Int>>()
            for (node in nodes) {
                val root = reduced.find(nodeIndexMap.getValue(node))
                components.getOrPut(root) { mutableSetOf() }.add(node)
            }

            val result = mutableListOf<Set<Int>>()
            for (component in components.values) {
                result += splitTextRegion(bboxes, component, width, height, gamma, sigma)
            }
            result
        }
    }
}

/**
 * Builds all pairwise edges (complete graph) for the given node indices.
 */
private fun buildEdges(bboxes: List<Quadrilateral>, nodes: List<Int>): MutableList<Edge> {
    val edges = mutableListOf<Edge>()
    for (i in nodes.indices) {
        for (j in i + 1 until nodes.size) {
            val dist = bboxes[nodes[i]].distance(bboxes[nodes[j]])
            edges.add(Edge(nodes[i], nodes[j], dist))
        }
    }
    return edges
}

private fun kruskalMst(nodes: List<Int>, edges: List<Edge>): List<Edge> {
    if (nodes.size < 2) return emptyList()

    val uf = UnionFind(nodes.maxOrNull()?.plus(1) ?: 0)
    val result = mutableListOf<Edge>()
    for (edge in edges.sortedBy { it.weight }) {
        if (uf.find(edge.from) != uf.find(edge.to)) {
            uf.union(edge.from, edge.to)
            result.add(edge)
            if (result.size == nodes.size - 1) break
        }
    }
    return result
}

private fun standardDeviation(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val mean = values.average().toFloat()
    val variance = values.map { (it - mean).pow(2) }.average().toFloat()
    return kotlin.math.sqrt(variance)
}
