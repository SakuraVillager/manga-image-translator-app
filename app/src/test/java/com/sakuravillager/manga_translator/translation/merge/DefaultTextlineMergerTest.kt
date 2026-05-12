package com.sakuravillager.manga_translator.translation.merge

import android.graphics.PointF
import com.sakuravillager.manga_translator.translation.data.Quadrilateral
import com.sakuravillager.manga_translator.translation.data.TextDirection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

/**
 * JVM unit tests for [DefaultTextlineMerger], [UnionFind],
 * [quadrilateralCanMergeRegion], and [splitTextRegion].
 *
 * All geometry utilities are pure Kotlin math with no Android dependencies
 * beyond PointF (available via SDK jar).
 */
class DefaultTextlineMergerTest {

    // ── Helper: create a PointF with specified coordinates via reflection ──
    // (Android mockable jar doesn't support PointF(float, float))

    private fun pt(x: Float, y: Float): PointF {
        val p = PointF()
        PointF::class.java.getField("x").setFloat(p, x)
        PointF::class.java.getField("y").setFloat(p, y)
        return p
    }

    // ── Helper: create a Quadrilateral with sorted points ──────────────

    private fun makeQuad(
        x1: Float, y1: Float, x2: Float, y2: Float,
        x3: Float, y3: Float, x4: Float, y4: Float,
        direction: TextDirection = TextDirection.AUTO,
    ): Quadrilateral = Quadrilateral(
        points = Quadrilateral.sortPointsLegacy(
            listOf(pt(x1, y1), pt(x2, y2), pt(x3, y3), pt(x4, y4)),
        ),
        _direction = direction,
        probability = 0.8f,
    )

    // ── UnionFind tests ───────────────────────────────────────────────

    @Test
    fun `unionFind union connects elements`() {
        val uf = UnionFind(5)
        uf.union(0, 1)
        uf.union(2, 3)
        uf.union(1, 3)

        // 0 and 2 should now be in the same set (0-1-3-2)
        assertEquals(uf.find(0), uf.find(2))
        // 4 should remain isolated
        assertNotEquals(uf.find(0), uf.find(4))
    }

    @Test
    fun `unionFind initially each element is its own root`() {
        val uf = UnionFind(4)
        for (i in 0 until 4) {
            assertEquals(i, uf.find(i))
        }
    }

    @Test
    fun `unionFind find is idempotent`() {
        val uf = UnionFind(3)
        assertEquals(uf.find(0), uf.find(0))
    }

    @Test
    fun `unionFind components returns correct groups`() {
        val uf = UnionFind(6)
        uf.union(0, 1)
        uf.union(1, 2)
        uf.union(3, 4)

        val comps = uf.components()
        // Expect at most 3 components: {0,1,2}, {3,4}, {5}
        val sizes = comps.map { it.size }.sorted()
        assertEquals(listOf(1, 2, 3), sizes)
    }

    @Test
    fun `unionFind union of same element is no-op`() {
        val uf = UnionFind(3)
        uf.union(1, 1)
        assertEquals(1, uf.find(1))
        assertEquals(3, uf.components().size)
    }

    @Test
    fun `unionFind path compression`() {
        val uf = UnionFind(5)
        // Chain: 0-1-2-3-4
        uf.union(0, 1)
        uf.union(1, 2)
        uf.union(2, 3)
        uf.union(3, 4)

        // All should have same root
        val root = uf.find(0)
        for (i in 1 until 5) {
            assertEquals(root, uf.find(i))
        }
    }

    // ── quadrilateralCanMergeRegion tests ──────────────────────────────

    @Test
    fun `quadrilateralCanMergeRegion close quads returns true`() {
        // Two 4x4 squares touching at x=4
        val q1 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val q2 = makeQuad(4f, 0f, 8f, 0f, 8f, 4f, 4f, 4f)
        assertTrue(quadrilateralCanMergeRegion(q1, q2))
    }

    @Test
    fun `quadrilateralCanMergeRegion far quads returns false`() {
        // Two 4x4 squares widely separated
        val q1 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val q2 = makeQuad(80f, 0f, 84f, 0f, 84f, 4f, 80f, 4f)
        assertFalse(quadrilateralCanMergeRegion(q1, q2))
    }

    @Test
    fun `quadrilateralCanMergeRegion different explicit directions returns false`() {
        val q1 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f, direction = TextDirection.HORIZONTAL)
        val q2 = makeQuad(4f, 0f, 8f, 0f, 8f, 4f, 4f, 4f, direction = TextDirection.VERTICAL)
        assertFalse(quadrilateralCanMergeRegion(q1, q2))
    }

    @Test
    fun `quadrilateralCanMergeRegion zero area quads returns false`() {
        val q1 = Quadrilateral(points = emptyList())
        val q2 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        assertFalse(quadrilateralCanMergeRegion(q1, q2))
    }

    @Test
    fun `quadrilateralCanMergeRegion AUTO direction always passes direction check`() {
        val q1 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f, direction = TextDirection.AUTO)
        val q2 = makeQuad(4f, 0f, 8f, 0f, 8f, 4f, 4f, 4f, direction = TextDirection.VERTICAL)
        // With q1 AUTO and q2 VERTICAL, they should still be compatible
        assertTrue(quadrilateralCanMergeRegion(q1, q2))
    }

    // ── splitTextRegion tests ──────────────────────────────────────────

    @Test
    fun `splitTextRegion single node returns itself`() {
        val q = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val result = splitTextRegion(listOf(q), setOf(0), 100, 100)
        assertEquals(1, result.size)
        assertEquals(setOf(0), result[0])
    }

    @Test
    fun `splitTextRegion two close nodes merge`() {
        // Touching squares → distance ≈ 0
        val q1 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val q2 = makeQuad(4f, 0f, 8f, 0f, 8f, 4f, 4f, 4f)
        val result = splitTextRegion(listOf(q1, q2), setOf(0, 1), 100, 100)
        assertEquals(1, result.size)
    }

    @Test
    fun `splitTextRegion two far nodes split`() {
        // Squares with large gap → distance > threshold
        val q1 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        // Place second square far enough that distance exceeds (1+gamma)*maxFs
        // distance ≈ minDist / (maxFs * rho) = 36 / (4 * 0.5) = 18
        // threshold = (1 + 0.5) * 4 = 6
        // 18 > 6 → split
        val q2 = makeQuad(40f, 0f, 44f, 0f, 44f, 4f, 40f, 4f)
        val result = splitTextRegion(listOf(q1, q2), setOf(0, 1), 100, 100)
        assertEquals(2, result.size)
    }

    @Test
    fun `splitTextRegion empty set returns empty`() {
        val result = splitTextRegion(emptyList(), emptySet(), 100, 100)
        assertTrue(result.isEmpty())
    }

    // ── DefaultTextlineMerger.merge tests ──────────────────────────────

    @Test
    fun `merge three quads two close one far returns two blocks`() = runBlocking {
        // q1 and q2 are adjacent (touching), q3 is far away
        val q1 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val q2 = makeQuad(4f, 0f, 8f, 0f, 8f, 4f, 4f, 4f)
        val q3 = makeQuad(80f, 0f, 84f, 0f, 84f, 4f, 80f, 4f)

        val merger = DefaultTextlineMerger()
        val result = merger.merge(listOf(q1, q2, q3), 100, 100)
        assertEquals(2, result.size)
    }

    @Test
    fun `merge empty list returns empty`() = runBlocking {
        val merger = DefaultTextlineMerger()
        val result = merger.merge(emptyList(), 100, 100)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `merge single quad returns one block`() = runBlocking {
        val q = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val merger = DefaultTextlineMerger()
        val result = merger.merge(listOf(q), 100, 100)
        assertEquals(1, result.size)
    }

    @Test
    fun `merge all close quads returns one block`() = runBlocking {
        val q1 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val q2 = makeQuad(4f, 0f, 8f, 0f, 8f, 4f, 4f, 4f)
        val q3 = makeQuad(8f, 0f, 12f, 0f, 12f, 4f, 8f, 4f)

        val merger = DefaultTextlineMerger()
        val result = merger.merge(listOf(q1, q2, q3), 100, 100)
        assertEquals(1, result.size)
    }

    @Test
    fun `merge all far quads returns three blocks`() = runBlocking {
        val q1 = makeQuad(0f, 0f, 4f, 0f, 4f, 4f, 0f, 4f)
        val q2 = makeQuad(80f, 0f, 84f, 0f, 84f, 4f, 80f, 4f)
        val q3 = makeQuad(160f, 0f, 164f, 0f, 164f, 4f, 160f, 4f)

        val merger = DefaultTextlineMerger()
        val result = merger.merge(listOf(q1, q2, q3), 100, 100)
        assertEquals(3, result.size)
    }

    @Test
    fun `merge creates TextBlock with aggregated text`() = runBlocking {
        val q1 = Quadrilateral(
            points = Quadrilateral.sortPointsLegacy(
                listOf(pt(0f, 0f), pt(4f, 0f), pt(4f, 4f), pt(0f, 4f)),
            ),
            text = "Hello",
            probability = 0.8f,
        )
        val q2 = Quadrilateral(
            points = Quadrilateral.sortPointsLegacy(
                listOf(pt(4f, 0f), pt(8f, 0f), pt(8f, 4f), pt(4f, 4f)),
            ),
            text = "World",
            probability = 0.9f,
        )

        val merger = DefaultTextlineMerger()
        val result = merger.merge(listOf(q1, q2), 100, 100)
        assertEquals(1, result.size)
        assertEquals("HelloWorld", result[0].text)
    }

    @Test
    fun `merger name and isReady return expected values`() {
        val merger = DefaultTextlineMerger()
        assertEquals("DefaultTextlineMerger", merger.name)
        assertTrue(merger.isReady)
    }
}
