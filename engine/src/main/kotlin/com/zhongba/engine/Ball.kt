package com.zhongba.engine

/** 0 为白球（主球），1–7 全色，8 黑球，9–15 花色。 */
data class Ball(val number: Int, val position: Vec2) {
    val isCue: Boolean get() = number == 0
    val isStripe: Boolean get() = number in 9..15
}
