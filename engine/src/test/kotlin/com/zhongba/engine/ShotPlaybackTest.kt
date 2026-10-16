package com.zhongba.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShotPlaybackTest {
    private val table = TableSpec.ZHONG_BA
    private val diag = Vec2(1.0, -1.0).normalized()
    private val ob = Vec2(table.length - 700, 700.0)
    private val layout = BallLayout(table, listOf(Ball(0, ob - diag * 600.0), Ball(1, ob), Ball(3, Vec2(500.0, 1000.0))))
    private val aim = AimAnalyzer.analyze(layout, 1, 2)!!
    private val shot = Simulator(table).simulateAimed(layout, aim, PowerLevels.speed(5), HitPoint()).result
    private val playback = ShotPlayback(layout, shot)

    @Test
    fun timesMatchPathsAndIncrease() {
        shot.paths.forEach { (number, points) ->
            val times = shot.pathTimes.getValue(number)
            assertEquals(points.size, times.size)
            times.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        }
    }

    @Test
    fun startsAtLayoutAndEndsAtFinalPositions() {
        val start = playback.poses(0.0).associateBy { it.number }
        layout.balls.forEach { assertEquals(it.position, start.getValue(it.number).position) }
        val end = playback.poses(playback.duration).associateBy { it.number }
        assertNull(end[1], "进袋的球回放结束后应消失")
        assertTrue(shot.finalPosition(0)!!.distanceTo(end.getValue(0).position) < 1e-6)
        assertEquals(layout.balls.first { it.number == 3 }.position, end.getValue(3).position)
    }

    @Test
    fun objectBallStaysStillUntilHit() {
        val hitTime = shot.events.first { it.type == EventType.BALL_HIT }.time
        val before = playback.poses(hitTime * 0.9).first { it.number == 1 }
        assertEquals(ob, before.position)
        val after = playback.poses(hitTime + 0.1).first { it.number == 1 }
        assertTrue(after.position.distanceTo(ob) > 1.0)
        assertTrue(playback.recentImpacts(hitTime + 0.05).size == 1)
    }

    @Test
    fun rolledDistanceGrowsWhileMoving() {
        val a = playback.poses(0.1).first { it.isCue() }.rolled
        val b = playback.poses(0.3).first { it.isCue() }.rolled
        assertTrue(b > a && a > 0)
    }

    private fun BallPose.isCue() = number == 0
}
