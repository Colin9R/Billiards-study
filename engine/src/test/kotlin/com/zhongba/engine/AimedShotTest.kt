package com.zhongba.engine

import kotlin.test.Test
import kotlin.test.assertTrue

class AimedShotTest {
    private val table = TableSpec.ZHONG_BA
    private val diag = Vec2(1.0, -1.0).normalized()
    private val ob = Vec2(table.length - 700, 700.0)

    private fun layoutForCut(cut: Double): Pair<BallLayout, AimResult> {
        val ghost = ob - diag * (2 * table.ballRadius)
        val cue = ghost - diag.rotated(Math.toRadians(cut)) * 600.0
        val layout = BallLayout(table, listOf(Ball(0, cue), Ball(1, ob)))
        return layout to AimAnalyzer.analyze(layout, 1, 2)!!
    }

    @Test
    fun compensatedCutShotsArePottedAtMediumPower() {
        val sim = Simulator(table)
        for (cut in listOf(10.0, 30.0, 50.0)) {
            val (layout, aim) = layoutForCut(cut)
            for (power in 5..7) {
                val shot = sim.simulateAimed(layout, aim, PowerLevels.speed(power), HitPoint())
                assertTrue(1 in shot.result.pocketed, "切角 $cut° 力度 $power 档补偿后应能进")
            }
        }
    }

    @Test
    fun correctionIsZeroWithoutBallFrictionAndPositiveWithIt() {
        val (layout, aim) = layoutForCut(30.0)
        val frictionless = Simulator(table, PhysicsParams(ballFriction = BallFriction.NONE))
            .simulateAimed(layout, aim, PowerLevels.speed(5), HitPoint())
        assertTrue(kotlin.math.abs(frictionless.throwCorrectionDeg) < 0.05, "无摩擦时不应修正，实际 ${frictionless.throwCorrectionDeg}")
        val real = Simulator(table).simulateAimed(layout, aim, PowerLevels.speed(5), HitPoint())
        assertTrue(kotlin.math.abs(real.throwCorrectionDeg) in 0.5..8.0, "有摩擦时应多切几度，实际 ${real.throwCorrectionDeg}")
    }
}
