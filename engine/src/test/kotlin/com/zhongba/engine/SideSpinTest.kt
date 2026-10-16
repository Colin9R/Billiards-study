package com.zhongba.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SideSpinTest {
    private val table = TableSpec.ZHONG_BA
    private val start = Vec2(1800.0, 635.0)

    /** 正对右库直打，返回白球停下时的 y（向下为正）。 */
    private fun stopYAfterHeadOnRail(side: Double): Double {
        val layout = BallLayout(table, listOf(Ball(0, start)))
        val result = Simulator(table).simulate(layout, Shot(Vec2(1.0, 0.0), 1500.0, 0.0, side))
        assertTrue(result.events.any { it.type == EventType.CUSHION })
        return result.finalPosition(0)!!.y
    }

    @Test
    fun noEnglishComesStraightBack() {
        assertEquals(start.y, stopYAfterHeadOnRail(0.0), 1e-6)
    }

    @Test
    fun rightEnglishReboundsToShootersRight() {
        // 出杆方向 +x 时，击球者的右手边是屏幕上的 +y
        assertTrue(stopYAfterHeadOnRail(0.5) > start.y + 50, "右塞应向右偏")
    }

    @Test
    fun leftEnglishMirrorsRightEnglish() {
        val right = stopYAfterHeadOnRail(0.5) - start.y
        val left = stopYAfterHeadOnRail(-0.5) - start.y
        assertEquals(-right, left, 1e-6)
    }

    @Test
    fun englishDoesNotBendPathBeforeCushion() {
        val layout = BallLayout(table, listOf(Ball(0, Vec2(500.0, 635.0))))
        val result = Simulator(table).simulate(layout, Shot(Vec2(1.0, 0.0), 800.0, 0.2, 0.5))
        val beforeCushion = result.paths.getValue(0)
        beforeCushion.forEach { assertEquals(635.0, it.y, 1e-9) }
    }

    @Test
    fun hitPointIsClampedAndNamed() {
        val p = HitPoint(1.0, 1.0).clamped()
        assertEquals(HitPoint.MAX_RADIUS, kotlin.math.hypot(p.x, p.y), 1e-12)
        assertEquals("中杆", HitPoint().label)
        assertEquals("低杆右塞", HitPoint(0.4, -0.4).label)
        assertEquals("中高杆小左塞", HitPoint(-0.2, 0.15).label)
    }
}
