package com.zhongba.engine

import kotlin.math.sqrt

/**
 * 球台规格，单位：毫米。坐标原点在台面（库边内沿）左上角，x 沿长边，y 向下。
 *
 * 默认值为中式八球：台面 2540×1270，球径 57.15。
 * 袋口尺寸各球房差别较大，因此做成可配置参数。
 */
data class TableSpec(
    val length: Double = 2540.0,
    val width: Double = 1270.0,
    val ballRadius: Double = 57.15 / 2,
    val cornerPocketMouth: Double = 86.0,
    val sidePocketMouth: Double = 92.0,
    val railWidth: Double = 45.0,
    val frameWidth: Double = 110.0,
) {
    /** 开球线（头线）的 x 坐标：长边的 1/4 处。 */
    val headStringX: Double get() = length / 4

    /** 置球点（脚点）：长边的 3/4 处，短边中点。 */
    val footSpot: Vec2 get() = Vec2(length * 3 / 4, width / 2)

    /**
     * 六个袋口。袋口中心取两条库边内沿的交点（角袋）或库边中点（中袋）；
     * 袋角（jaw）是袋口两侧库边胶条的尖端，袋口宽度即两袋角间距。
     */
    val pockets: List<Pocket>
        get() = listOf(
            corner("左上角袋", 0.0, 0.0),
            side("上中袋", 0.0),
            corner("右上角袋", length, 0.0),
            corner("左下角袋", 0.0, width),
            side("下中袋", width),
            corner("右下角袋", length, width),
        )

    private fun corner(name: String, cx: Double, cy: Double): Pocket {
        val sx = if (cx == 0.0) 1.0 else -1.0
        val sy = if (cy == 0.0) 1.0 else -1.0
        val w = cornerPocketMouth / sqrt(2.0)
        return Pocket(name, Vec2(cx, cy), cornerPocketMouth, isCorner = true,
            jaws = Vec2(cx + sx * w, cy) to Vec2(cx, cy + sy * w))
    }

    private fun side(name: String, cy: Double): Pocket {
        val c = Vec2(length / 2, cy)
        return Pocket(name, c, sidePocketMouth, isCorner = false,
            jaws = Vec2(c.x - sidePocketMouth / 2, cy) to Vec2(c.x + sidePocketMouth / 2, cy))
    }

    /** 球心可以到达的范围：贴库时球心离库边内沿一个半径。 */
    fun clampToPlayArea(p: Vec2): Vec2 = Vec2(
        p.x.coerceIn(ballRadius, length - ballRadius),
        p.y.coerceIn(ballRadius, width - ballRadius),
    )

    companion object {
        val ZHONG_BA = TableSpec()
    }
}

data class Pocket(
    val name: String,
    val center: Vec2,
    val mouth: Double,
    val isCorner: Boolean,
    val jaws: Pair<Vec2, Vec2>,
)
