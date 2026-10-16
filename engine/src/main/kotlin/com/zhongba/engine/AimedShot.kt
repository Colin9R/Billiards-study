package com.zhongba.engine

import kotlin.math.abs

/**
 * 补偿 throw（碰撞摩擦把目标球带偏）后的一杆。
 * @param throwCorrectionDeg 为了让目标球沿 [AimResult.objectDirection] 出去，接触点相对理想假想球要转的角度（度）
 */
data class AimedShot(val result: SimulationResult, val throwCorrectionDeg: Double)

/**
 * 按 [aim] 的进袋线打这一杆，并像真实球员那样补偿 throw：
 * 先按理想假想球出杆，量出目标球实际出球方向的偏差，把假想球绕目标球反向转过这个角度，迭代几次。
 * 没碰到目标球（例如力度太小或被挡）时不做补偿。
 */
fun Simulator.simulateAimed(layout: BallLayout, aim: AimResult, speed: Double, hitPoint: HitPoint): AimedShot {
    val contactDistance = 2 * layout.table.ballRadius
    val ob = aim.ghostBall + aim.objectDirection * contactDistance
    fun shootAt(ghost: Vec2) = simulate(layout, Shot(ghost - aim.cuePosition, speed, hitPoint.y, hitPoint.x))

    var ghost = aim.ghostBall
    var result = shootAt(ghost)
    for (i in 0 until MAX_ITERATIONS) {
        val actual = departureDirection(result, aim.targetNumber, ob) ?: break
        val error = actual.angleTo(aim.objectDirection)
        if (abs(error) < TOLERANCE_RAD) break
        ghost = ob - (ob - ghost).normalized().rotated(error) * contactDistance
        result = shootAt(ghost)
    }
    val correction = Math.toDegrees((aim.ghostBall - ob).angleTo(ghost - ob))
    return AimedShot(result, correction)
}

/** 目标球被白球碰到后最初的运动方向，取轨迹上离起点超过几毫米的第一个点。 */
private fun departureDirection(result: SimulationResult, target: Int, start: Vec2): Vec2? {
    if (result.firstContact != target) return null
    val point = result.paths[target]?.firstOrNull { it.distanceTo(start) > DEPARTURE_PROBE_MM } ?: return null
    return (point - start).normalized()
}

private const val MAX_ITERATIONS = 4
private const val TOLERANCE_RAD = 0.0003
private const val DEPARTURE_PROBE_MM = 5.0
