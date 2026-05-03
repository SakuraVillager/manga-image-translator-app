package com.sakuravillager.manga_translator.translation.merge

/**
 * Union-Find (Disjoint Set Union) data structure with path compression
 * and union by rank.
 */
class UnionFind(private val n: Int) {
    private val parent = IntArray(n) { it }
    private val rank = IntArray(n) { 0 }

    /**
     * Find the root representative of the set containing [x].
     * Applies path compression for amortized near-constant time.
     */
    fun find(x: Int): Int {
        var p = x
        while (parent[p] != p) {
            parent[p] = parent[parent[p]] // path compression
            p = parent[p]
        }
        return p
    }

    /**
     * Union the sets containing [x] and [y].
     * Uses union by rank to keep trees shallow.
     */
    fun union(x: Int, y: Int) {
        val rootX = find(x)
        val rootY = find(y)
        if (rootX == rootY) return
        when {
            rank[rootX] < rank[rootY] -> parent[rootX] = rootY
            rank[rootY] < rank[rootX] -> parent[rootY] = rootX
            else -> {
                parent[rootY] = rootX
                rank[rootX]++
            }
        }
    }

    /**
     * Returns a list of connected components, where each component is a
     * list of element indices belonging to the same set.
     */
    fun components(): List<List<Int>> {
        val map = mutableMapOf<Int, MutableList<Int>>()
        for (i in 0 until n) {
            map.getOrPut(find(i)) { mutableListOf() }.add(i)
        }
        return map.values.toList()
    }
}
