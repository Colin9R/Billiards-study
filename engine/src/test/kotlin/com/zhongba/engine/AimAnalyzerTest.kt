package com.zhongba.engine

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AimAnalyzerTest {
    private val table = TableSpec.ZHONG_BA
    private val r = table.ballRadius
    private val topRight = 2

    /** 右上角袋的对角线方向（指向袋口）。 */
    private val diag = Vec2(1.0, -1.0) * (1 / sqrt(2.0))
    private val ob = Vec2(table.length - 500, 500.0)

    private fun layout(cue: Vec2, vararg others: Ball) =
        BallLayout(table, listOf(Ball(0, cue), Ball(1, ob)) + others)

    @Test
    fun straightShotIntoCornerIsFullBall() {
        val result = AimAnalyzer.analyze(layout(ob - diag * 1000.0), 1, topRight)!!
        assertEquals(0.0, result.cutAngleDeg, 0.01)
        assertEquals(1.0, result.fullness, 1e-4)
        assertEquals("全球", fullnessLabel(result.fullness))
        assertTrue(result.isPottable)
        assertTrue(result.marginDeg > 0.5, "对角线直球应有容错，实际 ${result.marginDeg}")
        assertEquals(ob - diag * (2 * r), result.ghostBall)
    }

    @Test
    fun thirtyDegreeCutIsHalfBall() {
        val ghost = ob - diag * (2 * r)
        val cue = ghost - diag.rotated(Math.toRadians(30.0)) * 800.0
        val result = AimAnalyzer.analyze(layout(cue), 1, topRight)!!
        assertEquals(30.0, result.cutAngleDeg, 0.01)
        assertEquals(0.5, result.fullness, 1e-3)
        assertEquals("1/2 球", fullnessLabel(result.fullness))
        // 定杆分离角 90°：切线方向与目标球方向垂直
        assertEquals(0.0, result.stunDirection!!.dot(result.objectDirection), 1e-9)
    }

    @Test
    fun backwardShotHasNoFullnessOrStunLine() {
        val result = AimAnalyzer.analyze(layout(ob + diag * 400.0 + Vec2(0.0, 200.0)), 1, topRight)!!
        assertTrue(result.cutAngleDeg > 90)
        assertEquals(0.0, result.fullness)
        assertEquals(null, result.stunDirection)
        assertTrue(AimProblem.CutTooThin in result.problems)
    }

    @Test
    fun ballOnCuePathBlocks() {
        val cue = ob - diag * 1000.0
        val blocker = Ball(3, ob - diag * 500.0)
        val result = AimAnalyzer.analyze(layout(cue, blocker), 1, topRight)!!
        assertEquals(listOf<AimProblem>(AimProblem.CuePathBlocked(3)), result.problems)
    }

    @Test
    fun ballOnObjectPathBlocks() {
        val blocker = Ball(5, ob + diag * 300.0)
        val result = AimAnalyzer.analyze(layout(ob - diag * 1000.0, blocker), 1, topRight)!!
        assertEquals(listOf<AimProblem>(AimProblem.ObjectPathBlocked(5)), result.problems)
    }

    @Test
    fun sidePocketTooSteepIsNotReachable() {
        val sideOb = Vec2(table.length / 2 - 600, r + 2)
        val layout = BallLayout(table, listOf(Ball(0, Vec2(300.0, 600.0)), Ball(1, sideOb)))
        val result = AimAnalyzer.analyze(layout, 1, 1)!!
        assertTrue(AimProblem.PocketNotReachable in result.problems)
        assertFalse(result.isPottable)
    }

    @Test
    fun sidePocketStraightOnIsReachable() {
        val sideOb = Vec2(table.length / 2, 400.0)
        val layout = BallLayout(table, listOf(Ball(0, Vec2(table.length / 2, 900.0)), Ball(1, sideOb)))
        val result = AimAnalyzer.analyze(layout, 1, 1)!!
        assertTrue(result.isPottable)
        assertEquals(0.0, result.cutAngleDeg, 0.01)
    }

    @Test
    fun bestPocketPrefersSmallestPottableCut() {
        assertEquals(topRight, AimAnalyzer.bestPocket(layout(ob - diag * 1000.0), 1))
    }
}
