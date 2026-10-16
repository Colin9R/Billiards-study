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
    private const val NEAR_MM = 600.0
    private const val FAR_MM = 2500.0
    private const val FAR_FACTOR = 0.6

    /** 袋口容错达到这个角度视为满分，度。 */
    private const val GOOD_MARGIN_DEG = 1.5

    /**
     * 白球在 [cue] 时打 [nextNumber] 号球的难易度，0（打不进）到 1（很舒服）。
     * 取 6 个袋口里最好的那个。[layout] 里已有的白球位置会被忽略。
     */
    fun positionScore(layout: BallLayout, nextNumber: Int, cue: Vec2): Double {
        val r = layout.table.ballRadius
        val others = layout.balls.filter { !it.isCue }
        if (others.any { it.position.distanceTo(cue) < 2 * r }) return 0.0
        val withCue = BallLayout(layout.table, listOf(Ball(0, cue)) + others)
        val next = others.firstOrNull { it.number == nextNumber } ?: return 0.0
        val distance = cue.distanceTo(next.position)
        val distanceFactor = 1 - (1 - FAR_FACTOR) * ((distance - NEAR_MM) / (FAR_MM - NEAR_MM)).coerceIn(0.0, 1.0)

        return layout.table.pockets.indices.maxOf { pocket ->
            val aim = AimAnalyzer.analyze(withCue, nextNumber, pocket)
            if (aim == null || !aim.isPottable) {
                0.0
            } else {
                val cut = 1 - ((aim.cutAngleDeg - EASY_CUT_DEG) / (HARD_CUT_DEG - EASY_CUT_DEG)).coerceIn(0.0, 1.0)
                val margin = 0.5 + 0.5 * (aim.marginDeg / GOOD_MARGIN_DEG).coerceAtMost(1.0)
                cut * margin * distanceFactor
            }
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
     * 扫描全部高低杆预设 × 力度（左右塞固定为 [side]），
     * 评分时用模拟后的真实球位（被碰动的球也算在内）。
     */
    fun scan(simulator: Simulator, layout: BallLayout, aim: AimResult, nextNumber: Int, side: Double = 0.0): List<Option> {
        val direction = aim.ghostBall - aim.cuePosition
        return Stroke.entries.flatMap { stroke ->
            (1..PowerLevels.count).map { power ->
                val result = simulator.simulate(layout, Shot(direction, PowerLevels.speed(power), stroke.tipOffset, side))
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
