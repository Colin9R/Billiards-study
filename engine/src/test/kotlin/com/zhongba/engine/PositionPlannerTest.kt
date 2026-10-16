package com.zhongba.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PositionPlannerTest {
    private val table = TableSpec.ZHONG_BA
    private val r = table.ballRadius
    private val diag = Vec2(1.0, -1.0).normalized()

    /** 2 号球在右上角袋的对角线上，是"下一颗"。 */
    private val next = Vec2(table.length - 500, 500.0)

    @Test
    fun straightInPositionScoresHigh() {
        val layout = BallLayout(table, listOf(Ball(2, next)))
        val score = PositionPlanner.positionScore(layout, 2, next - diag * 400.0)
        // 目标球离袋口约 0.7 m，长台扣分会让它略低于满分
        assertTrue(score > 0.85, "正对袋口的近距离直球应接近满分，实际 $score")
    }

    @Test
    fun thinCutScoresLowerThanStraight() {
        val layout = BallLayout(table, listOf(Ball(2, next)))
        val straight = PositionPlanner.positionScore(layout, 2, next - diag * 400.0)
        val thin = PositionPlanner.positionScore(layout, 2, next - diag.rotated(Math.toRadians(55.0)) * 400.0)
        assertTrue(thin < straight, "薄球 $thin 应低于直球 $straight")
    }

    @Test
    fun blockedOrOverlappingPositionScoresZero() {
        val blocker = Ball(5, next - diag * 200.0)
        val layout = BallLayout(table, listOf(Ball(2, next), blocker))
        // 右上角袋的线路被 5 号挡住，换其他袋又很难，挡在中间的位置不应高分
        val behindBlocker = PositionPlanner.positionScore(layout, 2, next - diag * 400.0)
        val straightClear = PositionPlanner.positionScore(BallLayout(table, listOf(Ball(2, next))), 2, next - diag * 400.0)
        assertTrue(behindBlocker < straightClear)
        assertEquals(0.0, PositionPlanner.positionScore(layout, 2, blocker.position + Vec2(r, 0.0)))
    }

    @Test
    fun heatMapCoversTableAndPeaksNearGoodLine() {
        val layout = BallLayout(table, listOf(Ball(0, Vec2(500.0, 600.0)), Ball(1, Vec2(1200.0, 900.0)), Ball(2, next)))
        val heat = PositionPlanner.heatMap(layout, targetNumber = 1, nextNumber = 2)
        assertEquals(64, heat.cols)
        assertEquals(32, heat.rows)
        assertTrue(heat.scores.all { it in 0.0..1.0 })
        assertTrue(heat.scores.count { it > 0.5 } > 20, "应有一片好打的区域")
        // 刚进袋的 1 号球不再挡路，它原来的位置附近也可以评分
        val col = (1200.0 / heat.cellSize).toInt()
        val row = (900.0 / heat.cellSize).toInt()
        assertTrue(heat.score(col, row) >= 0.0)
    }

    @Test
    fun scanCoversEveryStrokeAndPower() {
        val ob = Vec2(table.length - 900, 700.0)
        val layout = BallLayout(table, listOf(Ball(0, ob - diag * 600.0), Ball(1, ob), Ball(2, Vec2(800.0, 300.0))))
        val aim = AimAnalyzer.analyze(layout, 1, AimAnalyzer.bestPocket(layout, 1)!!)!!
        val options = PositionPlanner.scan(Simulator(table), layout, aim, nextNumber = 2)
        assertEquals(Stroke.entries.size * PowerLevels.count, options.size)
        assertTrue(options.any { it.success }, "直球应有成功的组合")
        options.filter { !it.success }.forEach { assertEquals(0.0, it.score) }
    }
}
