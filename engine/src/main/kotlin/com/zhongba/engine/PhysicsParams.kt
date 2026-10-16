package com.zhongba.engine

/**
 * 物理参数，长度单位毫米、时间单位秒。默认值取自 pooltool 与 Dr. Dave Alciatore 的常用数值，
 * 不同球房的台呢、库边差异很大，后续做"台面校准"时调整这里。
 */
data class PhysicsParams(
    /** 滑动摩擦系数（球在台呢上打滑时）。 */
    val slidingFriction: Double = 0.2,
    /** 滚动阻力系数，决定球能滚多远（台呢"快慢"）。 */
    val rollingFriction: Double = 0.01,
    /** 球与球碰撞恢复系数。 */
    val ballRestitution: Double = 0.95,
    /** 库边恢复系数。 */
    val cushionRestitution: Double = 0.85,
    /** 库边与球之间的摩擦系数，决定左右塞对吃库角度的影响大小。 */
    val cushionFriction: Double = 0.2,
    /** 侧旋（左右塞）在台呢上的衰减系数。 */
    val spinFriction: Double = 0.044,
    val gravity: Double = 9810.0,
) {
    companion object {
        val DEFAULT = PhysicsParams()
    }
}

/**
 * 一杆击球。
 * @param direction 出杆方向（会被归一化）
 * @param speed 白球初速度，毫米/秒
 * @param tipOffset 杆头击点高低，以球半径为单位：+0.4 为自然滚动的高杆，0 为中杆，负数为低杆
 * @param side 杆头击点左右，以球半径为单位：正数为右塞，负数为左塞
 */
data class Shot(val direction: Vec2, val speed: Double, val tipOffset: Double, val side: Double = 0.0)

/**
 * 击打点：在白球正面看到的位置，以球半径为单位。x 向右为右塞，y 向上为高杆。
 * 超过 [MAX_RADIUS] 容易滑杆（miscue），所以限制在这个圆内。
 */
data class HitPoint(val x: Double = 0.0, val y: Double = 0.0) {
    fun clamped(): HitPoint {
        val len = kotlin.math.hypot(x, y)
        return if (len <= MAX_RADIUS) this else HitPoint(x / len * MAX_RADIUS, y / len * MAX_RADIUS)
    }

    /** 通俗叫法，如"中低杆右塞"。 */
    val label: String
        get() {
            val vertical = when {
                y >= 0.3 -> "高杆"
                y >= 0.08 -> "中高杆"
                y > -0.08 -> "中杆"
                y > -0.3 -> "中低杆"
                else -> "低杆"
            }
            val sideLabel = when {
                x >= 0.3 -> "右塞"
                x >= 0.08 -> "小右塞"
                x > -0.08 -> ""
                x > -0.3 -> "小左塞"
                else -> "左塞"
            }
            return vertical + sideLabel
        }

    companion object {
        const val MAX_RADIUS = 0.6
    }
}

/** 高低杆预设档位（走位表的行）。 */
enum class Stroke(val label: String, val tipOffset: Double) {
    FOLLOW("高杆", 0.45),
    HALF_FOLLOW("小高杆", 0.2),
    CENTER("中杆", 0.0),
    HALF_DRAW("小低杆", -0.25),
    DRAW("低杆", -0.5),
}

/** 力度档位 1–8 对应的白球初速度（毫米/秒）。 */
object PowerLevels {
    val speeds = doubleArrayOf(400.0, 600.0, 850.0, 1100.0, 1500.0, 2000.0, 2800.0, 4000.0)
    val count: Int get() = speeds.size

    /** [level] 从 1 开始。 */
    fun speed(level: Int): Double = speeds[(level - 1).coerceIn(0, speeds.size - 1)]
}
