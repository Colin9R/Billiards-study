package com.zhongba.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BallLayoutTest {
    private val table = TableSpec.ZHONG_BA
    private val r = table.ballRadius

    private fun assertNoOverlap(layout: BallLayout) {
        for (a in layout.balls) for (b in layout.balls) {
            if (a.number < b.number) {
                assertTrue(a.position.distanceTo(b.position) >= 2 * r - 1e-6, "${a.number} 与 ${b.number} 重叠")
            }
        }
    }

    @Test
    fun rackHas16BallsWithoutOverlap() {
        val layout = BallLayout.rack(table)
        assertEquals(16, layout.balls.size)
        assertNoOverlap(layout)
        assertEquals(table.footSpot, layout.balls.first { it.number == 1 }.position)
    }

    @Test
    fun dragOutsideIsClampedToCushion() {
        val layout = BallLayout(table, listOf(Ball(0, Vec2(500.0, 500.0))))
        val moved = layout.moveBall(0, Vec2(-300.0, 5000.0)).balls.single()
        assertEquals(Vec2(r, table.width - r), moved.position)
    }

    @Test
    fun dragOntoAnotherBallPushesItAside() {
        val layout = BallLayout(table, listOf(Ball(0, Vec2(500.0, 600.0)), Ball(1, Vec2(1000.0, 600.0))))
        val moved = layout.moveBall(0, Vec2(1010.0, 600.0))
        assertNoOverlap(moved)
        assertEquals(1000.0 + 2 * r, moved.balls.first { it.isCue }.position.x, 1e-6)
    }

    @Test
    fun dragIntoRackStopsTouchingTheCluster() {
        val layout = BallLayout.rack(table)
        val target = layout.balls.first { it.number == 8 }.position
        val moved = layout.moveBall(0, target)
        assertNoOverlap(moved)
        val cue = moved.balls.first { it.isCue }.position
        val nearest = moved.balls.filter { !it.isCue }.minOf { it.position.distanceTo(cue) }
        assertTrue(nearest < 2 * r + 1.0, "白球应贴着球堆停下，实际最近距离 $nearest")
    }

    @Test
    fun ballAtFindsNearestWithinSlop() {
        val layout = BallLayout.rack(table)
        assertEquals(0, assertNotNull(layout.ballAt(layout.balls.first().position + Vec2(r + 5, 0.0), slop = 10.0)).number)
        assertNull(layout.ballAt(Vec2(100.0, 100.0)))
    }
}
