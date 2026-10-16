package com.zhongba.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 台面平面上的二维向量，单位：毫米。 */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)

    operator fun unaryMinus() = Vec2(-x, -y)

    fun dot(o: Vec2): Double = x * o.x + y * o.y
    fun cross(o: Vec2): Double = x * o.y - y * o.x

    /** 从本向量转到 [o] 的有符号角度，弧度，范围 (-π, π]。 */
    fun angleTo(o: Vec2): Double = atan2(cross(o), dot(o))

    fun rotated(angle: Double): Vec2 {
        val c = cos(angle)
        val s = sin(angle)
        return Vec2(x * c - y * s, x * s + y * c)
    }

    /** 点到线段 [a]–[b] 的最短距离。 */
    fun distanceToSegment(a: Vec2, b: Vec2): Double {
        val ab = b - a
        val lenSq = ab.dot(ab)
        if (lenSq == 0.0) return distanceTo(a)
        val t = ((this - a).dot(ab) / lenSq).coerceIn(0.0, 1.0)
        return distanceTo(a + ab * t)
    }

    fun length(): Double = sqrt(x * x + y * y)
    fun distanceTo(o: Vec2): Double = (this - o).length()

    fun normalized(): Vec2 {
        val len = length()
        return if (len == 0.0) ZERO else Vec2(x / len, y / len)
    }

    companion object {
        val ZERO = Vec2(0.0, 0.0)
    }
}
