package com.zhongba.engine

import kotlin.math.sqrt

/** 台面上一组球的摆放，以及拖动时的约束（不出界、不重叠）。 */
class BallLayout(val table: TableSpec, val balls: List<Ball>) {

    /** 返回触点下的球，没有则为 null。[slop] 为触控容差，毫米。 */
    fun ballAt(point: Vec2, slop: Double = 0.0): Ball? =
        balls
            .filter { it.position.distanceTo(point) <= table.ballRadius + slop }
            .minByOrNull { it.position.distanceTo(point) }

    /**
     * 把 [number] 号球移到 [target] 附近的合法位置并返回新布局。
     * 与其他球重叠时沿球心连线推开；推不开（被球夹住）时，
     * 沿原位置→目标的连线二分查找最远的合法位置，让球贴着挡路的球停下。
     */
    fun moveBall(number: Int, target: Vec2): BallLayout {
        val moving = balls.firstOrNull { it.number == number } ?: return this
        val others = balls.filter { it.number != number }
        val resolved = resolvePosition(target, others)
            ?: farthestValidToward(moving.position, target, others)
            ?: return this
        return BallLayout(table, balls.map { if (it === moving) it.copy(position = resolved) else it })
    }

    private fun farthestValidToward(from: Vec2, to: Vec2, others: List<Ball>): Vec2? {
        var lo = 0.0
        var hi = 1.0
        var best: Vec2? = null
        repeat(BISECT_STEPS) {
            val mid = (lo + hi) / 2
            val p = resolvePosition(from + (to - from) * mid, others)
            if (p != null) {
                best = p
                lo = mid
            } else {
                hi = mid
            }
        }
        return best
    }

    private fun resolvePosition(target: Vec2, others: List<Ball>): Vec2? {
        val minDist = table.ballRadius * 2
        var p = table.clampToPlayArea(target)
        repeat(MAX_ITERATIONS) {
            var moved = false
            for (other in others) {
                val delta = p - other.position
                val dist = delta.length()
                if (dist < minDist - EPS) {
                    val dir = if (dist == 0.0) Vec2(1.0, 0.0) else delta.normalized()
                    p = table.clampToPlayArea(other.position + dir * minDist)
                    moved = true
                }
            }
            if (!moved) return p
        }
        return if (others.all { it.position.distanceTo(p) >= minDist - EPS }) p else null
    }

    companion object {
        private const val MAX_ITERATIONS = 8
        private const val EPS = 1e-6
        private const val BISECT_STEPS = 20

        /** 散开的练习球形，打开 App 时就能直接看瞄准分析。 */
        fun practice(table: TableSpec = TableSpec.ZHONG_BA): BallLayout = BallLayout(
            table,
            listOf(
                Ball(0, Vec2(900.0, 700.0)),
                Ball(1, Vec2(1900.0, 380.0)),
                Ball(2, Vec2(700.0, 300.0)),
                Ball(3, Vec2(1500.0, 950.0)),
                Ball(8, Vec2(2200.0, 900.0)),
                Ball(9, Vec2(1250.0, 200.0)),
                Ball(12, Vec2(400.0, 1000.0)),
            ),
        )

        /**
         * 中八开球摆法：三角框顶点在置球点，黑 8 在第三排中间，
         * 两个底角一全色一花色。白球放在开球线内。
         */
        fun rack(table: TableSpec = TableSpec.ZHONG_BA): BallLayout {
            val r = table.ballRadius
            val gap = 0.2
            val rowDx = (2 * r + gap) * sqrt(3.0) / 2
            val rows = listOf(
                listOf(1),
                listOf(9, 2),
                listOf(3, 8, 10),
                listOf(11, 4, 12, 5),
                listOf(6, 13, 7, 14, 15),
            )
            val racked = rows.flatMapIndexed { i, row ->
                row.mapIndexed { j, number ->
                    val y = table.footSpot.y + (j - (row.size - 1) / 2.0) * (2 * r + gap)
                    Ball(number, Vec2(table.footSpot.x + i * rowDx, y))
                }
            }
            val cue = Ball(0, Vec2(table.headStringX * 0.7, table.width / 2))
            return BallLayout(table, listOf(cue) + racked)
        }
    }
}
