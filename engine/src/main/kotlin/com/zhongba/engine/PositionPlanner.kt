package com.zhongba.engine

import kotlin.math.ceil

/**
 * 走位规划：评估"白球停在哪里，下一颗好打"，并扫描杆法 × 力度的全部组合。
 */
object PositionPlanner {

    /** 切角不超过这个值算舒服的球。 */
    private const val EASY_CUT_DEG = 15.0

    /** 切角达到这个值时评分降为 0（再薄就不算"好打"）。 */
    private const val HARD_CUT_DEG = 70.0

    /** 白球离目标球在这个距离以内不扣分，毫米。 */
    private const val NEAR_MM = 400.0
    private const val FAR_MM = 1800.0
    private const val FAR_FACTOR = 0.45

    /** 目标球离袋口在这个距离内不扣分；越远越难（长台），毫米。 */
    private const val OB_NEAR_MM = 500.0
    private const val OB_FAR_MM = 1800.0
    private const val OB_FAR_FACTOR = 0.8

    /** 袋口容错达到这个角度视为满分，度。 */
    private const val GOOD_MARGIN_DEG = 1.5

    /**
     * 一杆本身的难易度，0（进不了）到 1（很舒服）：切角越小、袋口容错越大、白球离目标球越近越好打；
     * 白球贴库或离库太近时只能架高杆、出杆受限，难度加倍。
     */
    fun shotEase(aim: AimResult, table: TableSpec): Double {
        if (!aim.isPottable) return 0.0
        val distance = aim.cuePosition.distanceTo(aim.ghostBall)
        // 长台：白球离目标球、目标球离袋口都是越远越难，业余球手尽量避免
        val distanceFactor = 1 - (1 - FAR_FACTOR) * ((distance - NEAR_MM) / (FAR_MM - NEAR_MM)).coerceIn(0.0, 1.0)
        val objectBall = aim.ghostBall + aim.objectDirection * (2 * table.ballRadius)
        val obDistance = objectBall.distanceTo(aim.pocket.center)
        val obFactor = 1 - (1 - OB_FAR_FACTOR) * ((obDistance - OB_NEAR_MM) / (OB_FAR_MM - OB_NEAR_MM)).coerceIn(0.0, 1.0)
        val cut = 1 - ((aim.cutAngleDeg - EASY_CUT_DEG) / (HARD_CUT_DEG - EASY_CUT_DEG)).coerceIn(0.0, 1.0)
        // 袋口容错（瞄准允许的偏差）权重加大：容错小的球即使切角不大也算难
        val margin = 0.3 + 0.7 * (aim.marginDeg / GOOD_MARGIN_DEG).coerceAtMost(1.0)
        return cut * margin * distanceFactor * obFactor * railFactor(aim.cuePosition, table)
    }

    /** 白球离库边（球面到库边）的空隙超过这个距离就不受影响，毫米。 */
    private const val RAIL_CLEAR_MM = 150.0

    /** 白球贴库为 0.5，离库 [RAIL_CLEAR_MM] 以上为 1，中间线性。 */
    private fun railFactor(cue: Vec2, table: TableSpec): Double {
        val gap = minOf(cue.x, table.length - cue.x, cue.y, table.width - cue.y) - table.ballRadius
        return 0.5 + 0.5 * (gap / RAIL_CLEAR_MM).coerceIn(0.0, 1.0)
    }

    /**
     * 白球在 [cue] 时打 [nextNumber] 号球的难易度，0（打不进）到 1（很舒服）。
     * 取 6 个袋口里最好的那个。[layout] 里已有的白球位置会被忽略。
     */
    fun positionScore(layout: BallLayout, nextNumber: Int, cue: Vec2): Double {
        val r = layout.table.ballRadius
        val others = layout.balls.filter { !it.isCue }
        if (others.any { it.position.distanceTo(cue) < 2 * r }) return 0.0
        if (others.none { it.number == nextNumber }) return 0.0
        val withCue = BallLayout(layout.table, listOf(Ball(0, cue)) + others)
        return layout.table.pockets.indices.maxOf { pocket ->
            AimAnalyzer.analyze(withCue, nextNumber, pocket)?.let { shotEase(it, layout.table) } ?: 0.0
        }
    }

    /** 台面网格上每个格子中心的走位评分；[scores] 按行存放（row * cols + col）。 */
    class HeatMap(val cellSize: Double, val cols: Int, val rows: Int, val scores: DoubleArray) {
        fun cellCenter(col: Int, row: Int) = Vec2((col + 0.5) * cellSize, (row + 0.5) * cellSize)
        fun score(col: Int, row: Int) = scores[row * cols + col]
    }

    /**
     * 打完当前这杆后（[targetNumber] 已进袋），白球停在各处时打 [nextNumber] 的评分。
     * 其他球按当前位置计算，不考虑这一杆把它们碰动。
     */
    fun heatMap(layout: BallLayout, targetNumber: Int, nextNumber: Int, cellSize: Double = 40.0): HeatMap {
        val table = layout.table
        val after = BallLayout(table, layout.balls.filter { it.number != targetNumber })
        val cols = ceil(table.length / cellSize).toInt()
        val rows = ceil(table.width / cellSize).toInt()
        val scores = DoubleArray(cols * rows)
        for (row in 0 until rows) for (col in 0 until cols) {
            val center = Vec2((col + 0.5) * cellSize, (row + 0.5) * cellSize)
            if (table.clampToPlayArea(center) != center) continue
            scores[row * cols + col] = positionScore(after, nextNumber, center)
        }
        return HeatMap(cellSize, cols, rows, scores)
    }

    /** 某一种杆法 + 力度的结果。 */
    data class Option(
        val stroke: Stroke,
        val power: Int,
        val result: SimulationResult,
        /** 这一杆本身是否成功：先碰目标球、目标球进、白球和下一颗都没进。 */
        val success: Boolean,
        /** 成功时白球停点对下一颗的评分，失败为 0。 */
        val score: Double,
    ) {
        val cueStop: Vec2? get() = result.finalStates.firstOrNull { it.number == 0 && it.motion != Motion.POCKETED }?.position
    }

    /**
     * 扫描全部高低杆预设 × 力度（左右塞固定为 [side]），每一杆都补偿 throw；
     * 评分时用模拟后的真实球位（被碰动的球也算在内）。
     */
    fun scan(simulator: Simulator, layout: BallLayout, aim: AimResult, nextNumber: Int, side: Double = 0.0): List<Option> {
        return Stroke.entries.flatMap { stroke ->
            (1..PowerLevels.count).map { power ->
                val hit = HitPoint(side, stroke.tipOffset)
                val result = simulator.simulateAimed(layout, aim, PowerLevels.speed(power), hit).result
                val success = result.firstContact == aim.targetNumber &&
                    aim.targetNumber in result.pocketed &&
                    0 !in result.pocketed &&
                    nextNumber !in result.pocketed
                val score = if (success) {
                    val after = BallLayout(
                        layout.table,
                        result.finalStates
                            .filter { it.motion != Motion.POCKETED }
                            .map { Ball(it.number, it.position) },
                    )
                    positionScore(after, nextNumber, result.finalPosition(0)!!)
                } else {
                    0.0
                }
                Option(stroke, power, result, success, score)
            }
        }
    }
}
