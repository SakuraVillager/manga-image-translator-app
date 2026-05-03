package com.sakuravillager.manga_translator.translation.merge

import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import kotlin.math.PI
import kotlin.math.abs

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
            // Two-node case: simple distance + angle check
            val a = bboxes[nodes[0]]
            val b = bboxes[nodes[1]]
            val d = a.distance(b)
            val maxFs = maxOf(a.fontSize, b.fontSize, 1f)
            val angleDiff = abs(a.angle - b.angle)
            if (d < (1f + gamma) * maxFs && angleDiff < 0.2f * PI.toFloat()) {
                listOf(indices) // merge
            } else {
                listOf(setOf(nodes[0]), setOf(nodes[1])) // split
            }
        }
        else -> {
            // 3+ nodes: build MST via Kruskal, find heaviest edge to split
            val edges = buildEdges(bboxes, nodes)
            edges.sortBy { it.weight }

            // Build MST
            val uf = UnionFind(bboxes.size)
            val mstEdges = mutableListOf<Edge>()
            for (e in edges) {
                if (uf.find(e.from) != uf.find(e.to)) {
                    uf.union(e.from, e.to)
                    mstEdges.add(e)
                }
                if (mstEdges.size == nodes.size - 1) break
            }

            // If no MST edges (shouldn't happen for 3+ nodes), return as one
            val heaviest = mstEdges.maxByOrNull { it.weight } ?: return listOf(indices)

            // Compute threshold: sigma * average font size of nodes
            val avgFs = nodes.map { bboxes[it].fontSize }.average().toFloat()
            val threshold = sigma * avgFs

            if (heaviest.weight > threshold) {
                // Split by removing the heaviest edge
                val uf2 = UnionFind(bboxes.size)
                for (e in mstEdges) {
                    if (e != heaviest) uf2.union(e.from, e.to)
                }

                val compA = nodes.filter { uf2.find(it) == uf2.find(heaviest.from) }.toSet()
                val compB = nodes.filter { uf2.find(it) == uf2.find(heaviest.to) }.toSet()

                // Recurse on both components
                return splitTextRegion(bboxes, compA, width, height, gamma, sigma) +
                        splitTextRegion(bboxes, compB, width, height, gamma, sigma)
            }

            // All nodes stay together if heaviest edge is within threshold
            listOf(indices)
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
