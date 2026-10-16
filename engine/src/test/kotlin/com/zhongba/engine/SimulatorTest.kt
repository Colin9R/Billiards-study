package com.zhongba.engine

import kotlin.math.acos
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimulatorTest {
    private val table = TableSpec.ZHONG_BA
    private val r = table.ballRadius
    private val params = PhysicsParams.DEFAULT

    @Test
    fun polynomialFindsAllRootsInRange() {
        // (t-1)(t-2)(t-3)(t-4) = t⁴ - 10t³ + 35t² - 50t + 24
        val roots = Polynomial.realRoots(doubleArrayOf(24.0, -50.0, 35.0, -10.0, 1.0), 0.0, 10.0)
        assertEquals(4, roots.size)
        listOf(1.0, 2.0, 3.0, 4.0).zip(roots).forEach { (want, got) -> assertEquals(want, got, 1e-9) }
        assertTrue(Polynomial.realRoots(doubleArrayOf(1.0, 0.0, 1.0), -5.0, 5.0).isEmpty())
    }

    @Test
    fun rollingBallStopsAtKinematicDistance() {
        val start = Vec2(800.0, 635.0)
        val layout = BallLayout(table, listOf(Ball(0, start)))
        val v = 300.0
        // 击点 0.4R 正好是自然滚动
        val result = Simulator(table).simulate(layout, Shot(Vec2(1.0, 0.0), v, 0.4))
        val expected = v * v / (2 * params.rollingFriction * params.gravity)
        assertEquals(start.x + expected, result.finalPosition(0)!!.x, 0.01)
    }

    /**
     * 用实施方案 §3.3 的速查表校验分离角（30° 切角）：
     * 定杆 90°，自然滚动高杆 63.7°，全低杆 139.1°。恢复系数取 1、球-球无摩擦，以对应理论推导。
     */
    @Test
    fun separationAnglesMatchTheoryAtHalfBall() {
        assertEquals(90.0, separationAngle(cut = 30.0, tipOffset = 0.0), 0.3)
        assertEquals(63.7, separationAngle(cut = 30.0, tipOffset = 0.4), 0.3)
        assertEquals(139.1, separationAngle(cut = 30.0, tipOffset = -0.4), 0.3)
    }

    @Test
    fun separationAnglesMatchTheoryAtOtherCuts() {
        assertEquals(51.9, separationAngle(cut = 20.0, tipOffset = 0.4), 0.3)
        assertEquals(76.5, separationAngle(cut = 50.0, tipOffset = 0.4), 0.3)
        assertEquals(119.2, separationAngle(cut = 50.0, tipOffset = -0.4), 0.3)
    }

    /** 白球与目标球的分离角：碰后白球最终（纯滚动）方向与目标球方向的夹角。 */
    private fun separationAngle(cut: Double, tipOffset: Double): Double {
        val ob = Vec2(1600.0, 635.0)
        val objectDir = Vec2(1.0, 0.0)
        val ghost = ob - objectDir * (2 * r)
        val aim = objectDir.rotated(Math.toRadians(cut))
        // 白球紧贴假想球出发，碰撞时的旋转就是出杆时的旋转
        val cue = ghost - aim * 0.01
        val layout = BallLayout(table, listOf(Ball(0, cue), Ball(1, ob)))
        // 理论值按"完全弹性、无摩擦"推导，所以关掉球-球摩擦（否则有 throw）
        val elastic = PhysicsParams(ballRestitution = 1.0, ballFriction = BallFriction.NONE)
        val result = Simulator(table, elastic).simulate(layout, Shot(aim, 400.0, tipOffset))

        val afterHit = result.events.dropWhile { !(it.type == EventType.BALL_HIT && it.ballNumber == 0) }
        val rolling = afterHit.first { it.ballNumber == 0 && it.type == EventType.ROLLING }
        val stopped = afterHit.first { it.ballNumber == 0 && it.type == EventType.STOPPED }
        assertTrue(afterHit.none { it.ballNumber == 0 && it.type == EventType.CUSHION && it.time < stopped.time }, "测试球形不应碰库")
        val finalDir = (stopped.position - rolling.position).normalized()
        return Math.toDegrees(acos(finalDir.dot(objectDir).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun straightShotPotsTheObjectBall() {
        val ob = Vec2(table.length - 500, 500.0)
        val diag = Vec2(1.0, -1.0).normalized()
        val layout = BallLayout(table, listOf(Ball(0, ob - diag * 700.0), Ball(1, ob)))
        val aim = AimAnalyzer.analyze(layout, 1, 2)!!
        // 中低杆：白球到达时接近定杆，正面直球会停在原地附近，不会跟进袋
        val result = Simulator(table).simulate(layout, Shot(aim.ghostBall - aim.cuePosition, 2000.0, -0.3))
        assertEquals(1, result.firstContact)
        assertContains(result.pocketed, 1)
        assertTrue(0 !in result.pocketed)
    }

    @Test
    fun centerBallFromDistanceFollowsIntoPocket() {
        // 中杆从远处打直球，到球时已大部分转为滚动，白球会跟进袋——这正是要让用户看到的
        val ob = Vec2(table.length - 500, 500.0)
        val diag = Vec2(1.0, -1.0).normalized()
        val layout = BallLayout(table, listOf(Ball(0, ob - diag * 700.0), Ball(1, ob)))
        val aim = AimAnalyzer.analyze(layout, 1, 2)!!
        val result = Simulator(table).simulate(layout, Shot(aim.ghostBall - aim.cuePosition, 2000.0, 0.0))
        assertEquals(listOf(1, 0), result.pocketed)
    }

    @Test
    fun ballBouncesOffRailAndStaysOnTable() {
        val layout = BallLayout(table, listOf(Ball(0, Vec2(1800.0, 635.0))))
        val result = Simulator(table).simulate(layout, Shot(Vec2(1.0, 0.0), 1200.0, 0.0))
        assertTrue(result.events.any { it.type == EventType.CUSHION })
        val end = result.finalPosition(0)!!
        assertTrue(end.x < table.length - r && end.x > r)
    }

    @Test
    fun hardBreakTerminatesWithBallsOnTableAndApart() {
        val layout = BallLayout.rack(table)
        val result = Simulator(table).simulate(layout, Shot(Vec2(1.0, 0.0), 8000.0, 0.0))
        val onTable = result.finalStates.filter { it.motion != Motion.POCKETED }
        onTable.forEach {
            assertEquals(Motion.STATIONARY, it.motion)
            assertTrue(it.position.x in 0.0..table.length && it.position.y in 0.0..table.width, "${it.number} 号球跑出台面 ${it.position}")
        }
        for (a in onTable) for (b in onTable) {
            if (a.number < b.number) {
                assertTrue(a.position.distanceTo(b.position) > 2 * r - 0.5, "${a.number} 与 ${b.number} 重叠")
            }
        }
    }
}
