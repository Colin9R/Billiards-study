package com.zhongba.engine

import kotlin.math.abs

/**
 * 实系数多项式工具。系数按升幂排列：c[i] 是 t^i 的系数。
 * 用于求碰撞时间（球–球、球–袋角是四次方程）。
 */
internal object Polynomial {

    fun eval(c: DoubleArray, t: Double): Double {
        var acc = 0.0
        for (i in c.indices.reversed()) acc = acc * t + c[i]
        return acc
    }

    fun derivative(c: DoubleArray): DoubleArray =
        if (c.size <= 1) doubleArrayOf(0.0) else DoubleArray(c.size - 1) { (it + 1) * c[it + 1] }

    /**
     * [lo, hi] 内的全部实根（升序）。做法：先递归求导数的根，把区间切成单调段，
     * 每段内有变号就二分。比闭式解稳健，四次方程足够快。
     */
    fun realRoots(coeffs: DoubleArray, lo: Double, hi: Double): List<Double> {
        val c = trim(coeffs)
        return when (c.size) {
            0, 1 -> emptyList()
            2 -> {
                val root = -c[0] / c[1]
                if (root in lo..hi) listOf(root) else emptyList()
            }
            else -> {
                val breaks = buildList {
                    add(lo)
                    addAll(realRoots(derivative(c), lo, hi))
                    add(hi)
                }
                val roots = mutableListOf<Double>()
                for (i in 0 until breaks.size - 1) {
                    val a = breaks[i]
                    val b = breaks[i + 1]
                    val fa = eval(c, a)
                    val fb = eval(c, b)
                    when {
                        fa == 0.0 -> roots += a
                        fa * fb < 0 -> roots += bisect(c, a, b, fa)
                    }
                }
                if (eval(c, hi) == 0.0) roots += hi
                roots.sorted().fold(mutableListOf()) { acc, r ->
                    if (acc.isEmpty() || r - acc.last() > 1e-12) acc += r
                    acc
                }
            }
        }
    }

    private fun bisect(c: DoubleArray, a0: Double, b0: Double, fa0: Double): Double {
        var a = a0
        var b = b0
        var fa = fa0
        repeat(80) {
            val m = (a + b) / 2
            val fm = eval(c, m)
            if (fm == 0.0) return m
            if (fa * fm < 0) b = m else { a = m; fa = fm }
        }
        return (a + b) / 2
    }

    /** 去掉相对可忽略的最高次项（例如静止球让四次项恰好为 0）。 */
    private fun trim(c: DoubleArray): DoubleArray {
        val scale = c.maxOf { abs(it) }
        if (scale == 0.0) return DoubleArray(0)
        var n = c.size
        while (n > 0 && abs(c[n - 1]) <= scale * 1e-14) n--
        return c.copyOf(n)
    }
}
