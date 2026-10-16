package com.zhongba.engine

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BreakShotTest {
    private val table = TableSpec.ZHONG_BA
    private val r = table.ballRadius

    @Test
    fun breakScattersBallsIntoAPlayableLayout() {
        repeat(20) { seed ->
            val shot = BreakSimulator.breakShot(Simulator(table), table, Random(seed))
            val balls = shot.after.balls
            // 所有球都停下了，白球和黑 8 一定在台面上
            assertTrue(shot.result.finalStates.none { it.motion == Motion.SLIDING || it.motion == Motion.ROLLING }, "种子 $seed：开球后还有球在动")
            assertTrue(balls.any { it.isCue } && balls.any { it.number == 8 })
            assertEquals(16, balls.size + shot.pocketed.count { it != 8 })
            for (a in balls) {
                assertTrue(a.position.x in r - 1e-6..table.length - r + 1e-6 && a.position.y in r - 1e-6..table.width - r + 1e-6, "种子 $seed：${a.number} 号出界")
                for (b in balls) if (a.number < b.number) {
                    assertTrue(a.position.distanceTo(b.position) > 2 * r - 0.5, "种子 $seed：${a.number} 与 ${b.number} 重叠")
                }
            }
            // 散开：彩球分布范围远大于三角框
            val xs = balls.filter { !it.isCue }.map { it.position.x }
            assertTrue(xs.max() - xs.min() > 600, "种子 $seed：球没有散开")
        }
    }

    @Test
    fun differentBreaksGiveDifferentLayouts() {
        val a = BreakSimulator.breakShot(Simulator(table), table, Random(1)).after.balls.associate { it.number to it.position }
        val b = BreakSimulator.breakShot(Simulator(table), table, Random(2)).after.balls.associate { it.number to it.position }
        assertTrue(a != b)
    }
}
