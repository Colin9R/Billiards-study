package com.zhongba.engine

import kotlin.random.Random

/**
 * 一次模拟开球。
 * @param start 摆好三角框、白球放在开球区的球形（动画从这里开始）
 * @param result 物理引擎模拟的开球过程
 * @param after 开球后的球形：进袋的球已拿走；白球落袋则放回开球区，黑 8 进袋则摆回置球点
 */
data class BreakShot(
    val start: BallLayout,
    val result: SimulationResult,
    val after: BallLayout,
    val pocketed: List<Int>,
    val scratched: Boolean,
)

/** 中八开球：每次白球位置、出杆角度和力度都略有不同，散出来的球形也不一样。 */
object BreakSimulator {
    /** 大力开球的白球初速度范围，毫米/秒。 */
    private const val MIN_SPEED = 7000.0
    private const val MAX_SPEED = 8500.0

    /** 白球在开球线后、偏离中线的最大距离，毫米。 */
    private const val CUE_OFFSET_MM = 250.0

    /** 对准顶球时的随机偏差，度。 */
    private const val AIM_JITTER_DEG = 0.6

    /** 摆球不完美的程度：每颗球最多偏离标准位置的距离，毫米。 */
    private const val RACK_JITTER_MM = 2.0

    fun breakShot(
        simulator: Simulator,
        table: TableSpec = TableSpec.ZHONG_BA,
        random: Random = Random.Default,
        rackJitterMm: Double = RACK_JITTER_MM,
    ): BreakShot {
        val rack = looseRack(table, random, rackJitterMm)
        val cue = Vec2(table.headStringX * 0.75, table.width / 2 + random.nextDouble(-CUE_OFFSET_MM, CUE_OFFSET_MM))
        val start = BallLayout(table, rack.balls.filter { !it.isCue } + Ball(0, cue))
        val apex = table.footSpot
        val direction = (apex - cue).normalized().rotated(Math.toRadians(random.nextDouble(-AIM_JITTER_DEG, AIM_JITTER_DEG)))
        // 略低于中心的定杆开球，白球碰后不容易乱跑
        val shot = Shot(direction, random.nextDouble(MIN_SPEED, MAX_SPEED), tipOffset = -0.1)
        val result = simulator.simulate(start, shot)

        val pocketed = result.pocketed
        var after = BallLayout(
            table,
            result.finalStates.filter { it.motion != Motion.POCKETED }.map { Ball(it.number, it.position) },
        )
        val scratched = 0 in pocketed
        if (scratched) after = placeBall(after, 0, Vec2(table.headStringX * 0.75, table.width / 2))
        if (8 in pocketed) after = placeBall(after, 8, table.footSpot)
        return BreakShot(start, result, after, pocketed.filter { it != 0 }, scratched)
    }

    /** 真实摆球的间隙大小不一：每颗球（顶球除外）在完美位置附近随机挪动一点，且互不重叠。 */
    internal fun looseRack(table: TableSpec, random: Random, jitterMm: Double): BallLayout {
        val perfect = BallLayout.rack(table)
        val minDist = 2 * table.ballRadius + 0.05
        val placed = mutableListOf<Ball>()
        for (ball in perfect.balls) {
            if (ball.isCue || ball.number == 1) {
                placed += ball
                continue
            }
            val position = generateSequence { ball.position + Vec2(jitter(random, jitterMm), jitter(random, jitterMm)) }
                .take(MAX_TRIES)
                .firstOrNull { candidate -> placed.none { !it.isCue && it.position.distanceTo(candidate) < minDist } }
                ?: ball.position
            placed += ball.copy(position = position)
        }
        return BallLayout(table, placed)
    }

    private fun jitter(random: Random, max: Double) = if (max <= 0) 0.0 else random.nextDouble(-max, max)

    private const val MAX_TRIES = 20

    /** 把一颗进袋的球放回台面 [spot] 附近（与别的球重叠时自动挪开）。 */
    private fun placeBall(layout: BallLayout, number: Int, spot: Vec2): BallLayout {
        val withBall = BallLayout(layout.table, layout.balls + Ball(number, layout.table.clampToPlayArea(spot)))
        return withBall.moveBall(number, spot)
    }
}
