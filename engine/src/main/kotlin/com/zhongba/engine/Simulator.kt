package com.zhongba.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sign

enum class Motion { STATIONARY, SLIDING, ROLLING, POCKETED }

/**
 * 某一时刻一颗球的完整状态。
 * [spin] 是角速度在台面上的分量 (ωx, ωy)，单位 rad/s；高低杆就体现在这里。
 * [sideSpin] 是绕竖直轴的角速度 ωz（左右塞），在台呢上匀速衰减，只在碰库时改变走向。
 * 暂不模拟加塞造成的出杆偏移（squirt）、弧线（swerve）和带偏（throw）。
 */
data class BallState(
    val number: Int,
    val position: Vec2,
    val velocity: Vec2,
    val spin: Vec2,
    val motion: Motion,
    val sideSpin: Double = 0.0,
)

enum class EventType { BALL_HIT, CUSHION, POCKETED, ROLLING, STOPPED }

data class SimEvent(
    val time: Double,
    val type: EventType,
    val ballNumber: Int,
    /** 碰球时的另一颗球。 */
    val otherNumber: Int? = null,
    val position: Vec2,
)

data class SimulationResult(
    /** 每颗动过的球的轨迹折线（含起点）。 */
    val paths: Map<Int, List<Vec2>>,
    val finalStates: List<BallState>,
    val events: List<SimEvent>,
) {
    val pocketed: List<Int> get() = events.filter { it.type == EventType.POCKETED }.map { it.ballNumber }

    /** 白球第一颗碰到的球号。 */
    val firstContact: Int? get() = events.firstOrNull { it.type == EventType.BALL_HIT && it.ballNumber == 0 }?.otherNumber

    fun finalPosition(number: Int): Vec2? = finalStates.firstOrNull { it.number == number }?.position
}

/**
 * 事件驱动的台球模拟器（与 pooltool 同一思路）。
 *
 * 每颗球在两次事件之间的运动都有解析解：滑动阶段和滚动阶段都是匀减速，轨迹是抛物线/直线。
 * 每一步求出最早发生的事件（滑动→滚动、停止、碰球、碰库、碰袋角、进袋），
 * 把所有球推进到那一刻，处理该事件，再继续，直到全部停止。
 */
class Simulator(
    private val table: TableSpec = TableSpec.ZHONG_BA,
    private val params: PhysicsParams = PhysicsParams.DEFAULT,
) {
    private val r = table.ballRadius
    private val g = params.gravity
    private val rails = buildRails()
    private val jaws = table.pockets.flatMap { listOf(it.jaws.first, it.jaws.second) }
    private val pocketLines = table.pockets.map { pocketLine(it) }

    fun simulate(layout: BallLayout, shot: Shot): SimulationResult {
        val dir = shot.direction.normalized()
        val states = layout.balls.map { ball ->
            if (ball.isCue) {
                val v = dir * shot.speed
                // 杆头击点比球心高 b·R 时，ω = 5·V·b / (2R)，方向为 ẑ×出杆方向
                val w = zCross(dir) * (5 * shot.speed * shot.tipOffset / (2 * r))
                // 击点偏右 a·R：角动量 r×J 的竖直分量为 −a·R·m·V，所以 ωz = −5·V·a / (2R)
                val wz = -5 * shot.speed * shot.side / (2 * r)
                classify(BallState(ball.number, ball.position, v, w, Motion.SLIDING, wz))
            } else {
                BallState(ball.number, ball.position, Vec2.ZERO, Vec2.ZERO, Motion.STATIONARY)
            }
        }.toMutableList()

        val paths = states.associate { it.number to mutableListOf(it.position) }
        val events = mutableListOf<SimEvent>()
        var time = 0.0

        for (step in 0 until MAX_EVENTS) {
            val moving = states.indices.filter { states[it].motion.isMoving() }
            if (moving.isEmpty() || time > MAX_TIME) break
            val next = findNextEvent(states, moving) ?: break

            advance(states, moving, next.time, paths)
            time += next.time
            resolve(states, next, time, events)
        }

        return SimulationResult(
            paths = paths.filterValues { it.size > 1 },
            finalStates = states.toList(),
            events = events,
        )
    }

    // ---------- 运动方程 ----------

    /** 球与台呢接触点的滑动速度 u = v − R(ω×ẑ)。为 0 时纯滚动。 */
    private fun slip(s: BallState) = Vec2(s.velocity.x - r * s.spin.y, s.velocity.y + r * s.spin.x)

    /** 加速度（常量，直到下一次状态转换）。 */
    private fun acceleration(s: BallState): Vec2 = when (s.motion) {
        Motion.SLIDING -> slip(s).normalized() * (-params.slidingFriction * g)
        Motion.ROLLING -> s.velocity.normalized() * (-params.rollingFriction * g)
        else -> Vec2.ZERO
    }

    private fun transitionTime(s: BallState): Double = when (s.motion) {
        Motion.SLIDING -> 2 * slip(s).length() / (7 * params.slidingFriction * g)
        Motion.ROLLING -> s.velocity.length() / (params.rollingFriction * g)
        else -> Double.POSITIVE_INFINITY
    }

    /** 把一颗球沿当前运动阶段推进 [t] 秒（t 不超过它的状态转换时间）。 */
    private fun evolve(s: BallState, t: Double): BallState = evolveLinear(s, t).copy(sideSpin = decaySideSpin(s.sideSpin, t))

    /** 侧旋在台呢上的衰减：dωz/dt = −5·μsp·g / (2R)，减到 0 为止。不影响球心轨迹。 */
    private fun decaySideSpin(wz: Double, t: Double): Double {
        val drop = 5 * params.spinFriction * g / (2 * r) * t
        return if (abs(wz) <= drop) 0.0 else wz - sign(wz) * drop
    }

    private fun evolveLinear(s: BallState, t: Double): BallState = when (s.motion) {
        Motion.SLIDING -> {
            val uHat = slip(s).normalized()
            val decel = params.slidingFriction * g
            s.copy(
                position = s.position + s.velocity * t - uHat * (0.5 * decel * t * t),
                velocity = s.velocity - uHat * (decel * t),
                // 摩擦力矩：dω/dt = 5μg/(2R) · (ẑ×û)
                spin = s.spin + zCross(uHat) * (5 * decel * t / (2 * r)),
            )
        }
        Motion.ROLLING -> {
            val vHat = s.velocity.normalized()
            val decel = params.rollingFriction * g
            val v = s.velocity - vHat * (decel * t)
            s.copy(
                position = s.position + s.velocity * t - vHat * (0.5 * decel * t * t),
                velocity = v,
                spin = zCross(v) * (1 / r),
            )
        }
        else -> s
    }

    /** 碰撞后根据速度和旋转重新判断运动阶段。 */
    private fun classify(s: BallState): BallState = when {
        s.motion == Motion.POCKETED -> s
        slip(s).length() > EPS_SPEED -> s.copy(motion = Motion.SLIDING)
        s.velocity.length() > EPS_SPEED -> s.copy(spin = zCross(s.velocity) * (1 / r), motion = Motion.ROLLING)
        else -> s.copy(velocity = Vec2.ZERO, spin = Vec2.ZERO, sideSpin = 0.0, motion = Motion.STATIONARY)
    }

    // ---------- 事件检测 ----------

    private sealed interface Kind {
        data class Transition(val i: Int) : Kind
        data class BallBall(val i: Int, val j: Int) : Kind
        data class Rail(val i: Int, val rail: Int) : Kind
        data class Jaw(val i: Int, val jaw: Int) : Kind
        data class Pocket(val i: Int, val pocket: Int) : Kind
    }

    private class Event(val time: Double, val kind: Kind)

    private fun findNextEvent(states: List<BallState>, moving: List<Int>): Event? {
        var best: Event? = null
        fun offer(t: Double?, kind: Kind) {
            if (t != null && t < (best?.time ?: Double.POSITIVE_INFINITY)) best = Event(t, kind)
        }

        for (i in moving) offer(transitionTime(states[i]), Kind.Transition(i))
        // 运动方程只在各自的状态转换之前有效，所以碰撞只需在 [0, horizon] 内找
        for (i in moving) {
            val horizon = best?.time ?: Double.POSITIVE_INFINITY
            for (j in states.indices) {
                if (j == i || states[j].motion == Motion.POCKETED) continue
                if (states[j].motion.isMoving() && j < i) continue // 两球都在动时只算一次
                offer(ballBallTime(states[i], states[j], horizon), Kind.BallBall(i, j))
            }
            val h = best?.time ?: Double.POSITIVE_INFINITY
            rails.forEachIndexed { k, rail -> offer(railTime(states[i], rail, h), Kind.Rail(i, k)) }
            jaws.forEachIndexed { k, jaw -> offer(pointTime(states[i], jaw, h), Kind.Jaw(i, k)) }
            pocketLines.forEachIndexed { k, line -> offer(pocketTime(states[i], line, h), Kind.Pocket(i, k)) }
        }
        return best
    }

    /** 两球球心距离等于 2R 的最早时刻：|Δp + Δv·t + Δa·t²|² = (2R)²，四次方程。 */
    private fun ballBallTime(a: BallState, b: BallState, horizon: Double): Double? {
        val c = a.position - b.position
        val bv = a.velocity - b.velocity
        val acc = (acceleration(a) - acceleration(b)) * 0.5 // r(t) = p + v·t + ½a·t²
        val poly = doubleArrayOf(
            c.dot(c) - 4 * r * r,
            2 * bv.dot(c),
            bv.dot(bv) + 2 * acc.dot(c),
            2 * acc.dot(bv),
            acc.dot(acc),
        )
        return firstRoot(poly, horizon, approaching = true)
    }

    /** 球碰到袋角尖端（视为一个点）：|p − J + v·t + a·t²|² = R²。 */
    private fun pointTime(s: BallState, point: Vec2, horizon: Double): Double? {
        val c = s.position - point
        val v = s.velocity
        val acc = acceleration(s) * 0.5 // r(t) = p + v·t + ½a·t²
        val poly = doubleArrayOf(
            c.dot(c) - r * r,
            2 * v.dot(c),
            v.dot(v) + 2 * acc.dot(c),
            2 * acc.dot(v),
            acc.dot(acc),
        )
        return firstRoot(poly, horizon, approaching = true)
    }

    /** 球碰库：沿库边法线方向的距离等于 R，二次方程；接触点必须落在这段库边上。 */
    private fun railTime(s: BallState, rail: Rail, horizon: Double): Double? {
        val n = rail.normal
        val acc = acceleration(s) * 0.5 // r(t) = p + v·t + ½a·t²
        val poly = doubleArrayOf(n.dot(s.position) - rail.offset - r, n.dot(s.velocity), n.dot(acc))
        val t = firstRoot(poly, horizon, approaching = true) ?: return null
        val contact = evolve(s, t).position - n * r
        return if (rail.contains(contact)) t else null
    }

    /** 球心越过两袋角连线（并且在两袋角之间）即算进袋。 */
    private fun pocketTime(s: BallState, line: PocketLine, horizon: Double): Double? {
        val m = line.outward
        val acc = acceleration(s) * 0.5 // r(t) = p + v·t + ½a·t²
        val poly = doubleArrayOf(m.dot(s.position) - line.offset, m.dot(s.velocity), m.dot(acc))
        val t = firstRoot(poly, horizon, approaching = false) ?: return null
        return if (line.contains(evolve(s, t).position)) t else null
    }

    /**
     * 多项式 f 在 (0, horizon] 内第一个"按指定方向穿过 0"的根。
     * approaching = true 找 f 由正变负（距离在缩小）；false 找由负变正。
     * 已经接触且正在靠近时返回 0。
     */
    private fun firstRoot(poly: DoubleArray, horizon: Double, approaching: Boolean): Double? {
        val sign = if (approaching) -1.0 else 1.0
        if (sign * poly[0] >= -CONTACT_TOL && sign * poly[1] > 0) return 0.0
        if (horizon <= EPS_TIME) return null
        val d = Polynomial.derivative(poly)
        return Polynomial.realRoots(poly, EPS_TIME, horizon).firstOrNull { sign * Polynomial.eval(d, it) > 0 }
    }

    // ---------- 推进与事件处理 ----------

    private fun advance(states: MutableList<BallState>, moving: List<Int>, dt: Double, paths: Map<Int, MutableList<Vec2>>) {
        for (i in moving) {
            val s = states[i]
            val path = paths.getValue(s.number)
            val steps = ceil(s.velocity.length() * dt / PATH_STEP_MM).toInt().coerceIn(1, 400)
            for (k in 1..steps) path += evolve(s, dt * k / steps).position
            states[i] = evolve(s, dt)
        }
    }

    private fun resolve(states: MutableList<BallState>, event: Event, time: Double, events: MutableList<SimEvent>) {
        when (val kind = event.kind) {
            is Kind.Transition -> {
                val s = states[kind.i]
                states[kind.i] = if (s.motion == Motion.SLIDING && s.velocity.length() > EPS_SPEED) {
                    events += SimEvent(time, EventType.ROLLING, s.number, position = s.position)
                    s.copy(spin = zCross(s.velocity) * (1 / r), motion = Motion.ROLLING)
                } else {
                    events += SimEvent(time, EventType.STOPPED, s.number, position = s.position)
                    s.copy(velocity = Vec2.ZERO, spin = Vec2.ZERO, sideSpin = 0.0, motion = Motion.STATIONARY)
                }
            }
            is Kind.BallBall -> {
                val a = states[kind.i]
                val b = states[kind.j]
                val n = (b.position - a.position).normalized()
                val approach = (a.velocity - b.velocity).dot(n)
                if (approach > 0) {
                    // 等质量、无摩擦碰撞：只交换法向速度（含恢复系数），旋转不传递
                    val impulse = n * ((1 + params.ballRestitution) / 2 * approach)
                    states[kind.i] = classify(a.copy(velocity = a.velocity - impulse))
                    states[kind.j] = classify(b.copy(velocity = b.velocity + impulse))
                }
                events += SimEvent(time, EventType.BALL_HIT, a.number, b.number, a.position)
                events += SimEvent(time, EventType.BALL_HIT, b.number, a.number, b.position)
            }
            is Kind.Rail -> bounce(states, kind.i, rails[kind.rail].normal, time, events)
            is Kind.Jaw -> bounce(states, kind.i, (states[kind.i].position - jaws[kind.jaw]).normalized(), time, events)
            is Kind.Pocket -> {
                val s = states[kind.i]
                states[kind.i] = s.copy(velocity = Vec2.ZERO, spin = Vec2.ZERO, motion = Motion.POCKETED)
                events += SimEvent(time, EventType.POCKETED, s.number, position = s.position)
            }
        }
    }

    /**
     * 简化库边模型（接触点按与球心同高处理）：
     * - 法向速度按恢复系数反弹；与法向运动对应的那部分高低杆旋转同样反向衰减；
     * - 左右塞：接触点切向滑动速度 u = v·t̂ − ωz·R，库边摩擦冲量抵消它（不超过库仑摩擦上限），
     *   同时改变切向速度和 ωz。这就是加塞后吃库角度变大/变小的来源。
     * 真实库边的高度效应（Han 2005 / Mathavan 2010）留到后续版本。
     */
    private fun bounce(states: MutableList<BallState>, i: Int, normal: Vec2, time: Double, events: MutableList<SimEvent>) {
        val s = states[i]
        val vn = s.velocity.dot(normal)
        if (vn < 0) {
            val e = params.cushionRestitution
            val tangent = zCross(normal)
            // 单位质量冲量 j：Δv = j·t̂，ΔωZ = −5j/(2R)，接触点切向速度变化 3.5j
            val slipT = s.velocity.dot(tangent) - s.sideSpin * r
            val maxJ = params.cushionFriction * (1 + e) * -vn
            val j = (-slipT / 3.5).coerceIn(-maxJ, maxJ)
            states[i] = classify(
                s.copy(
                    velocity = s.velocity - normal * ((1 + e) * vn) + tangent * j,
                    spin = s.spin - tangent * ((1 + e) * s.spin.dot(tangent)),
                    sideSpin = s.sideSpin - 5 * j / (2 * r),
                ),
            )
        }
        events += SimEvent(time, EventType.CUSHION, s.number, position = s.position)
    }

    // ---------- 台面几何 ----------

    /** 一段库边：[from]–[to] 是库边胶条内沿上的线段，[normal] 指向台内。 */
    private class Rail(val from: Vec2, val to: Vec2, val normal: Vec2) {
        val offset = normal.dot(from)
        fun contains(p: Vec2): Boolean {
            val ab = to - from
            val k = (p - from).dot(ab) / ab.dot(ab)
            return k in 0.0..1.0
        }
    }

    private class PocketLine(val a: Vec2, val b: Vec2, val outward: Vec2) {
        val offset = outward.dot(a)
        fun contains(p: Vec2): Boolean {
            val ab = b - a
            val k = (p - a).dot(ab) / ab.dot(ab)
            return k in 0.0..1.0
        }
    }

    private fun buildRails(): List<Rail> {
        val p = table.pockets // 顺序：左上、上中、右上、左下、下中、右下
        val center = Vec2(table.length / 2, table.width / 2)
        fun rail(a: Vec2, b: Vec2): Rail {
            val d = (b - a).normalized()
            var n = zCross(d)
            if (n.dot(center - a) < 0) n = -n
            return Rail(a, b, n)
        }
        return listOf(
            rail(p[0].jaws.first, p[1].jaws.first),
            rail(p[1].jaws.second, p[2].jaws.first),
            rail(p[3].jaws.first, p[4].jaws.first),
            rail(p[4].jaws.second, p[5].jaws.first),
            rail(p[0].jaws.second, p[3].jaws.second),
            rail(p[2].jaws.second, p[5].jaws.second),
        )
    }

    private fun pocketLine(pocket: Pocket): PocketLine {
        val (a, b) = pocket.jaws
        val outward = if (pocket.isCorner) {
            // 角袋：袋口中心（两库边交点）在两袋角连线外侧
            val m = zCross((b - a).normalized())
            if (m.dot(pocket.center - a) < 0) -m else m
        } else {
            // 中袋：两袋角连线与库边重合，直接取指向台外的方向
            Vec2(0.0, if (pocket.center.y == 0.0) -1.0 else 1.0)
        }
        return PocketLine(a, b, outward)
    }

    private fun Motion.isMoving() = this == Motion.SLIDING || this == Motion.ROLLING

    companion object {
        private const val MAX_EVENTS = 500
        private const val MAX_TIME = 120.0
        private const val EPS_SPEED = 1e-3
        private const val EPS_TIME = 1e-9
        private const val CONTACT_TOL = 1e-6
        private const val PATH_STEP_MM = 12.0
    }
}

/** ẑ × a：把台面向量逆时针转 90°。 */
internal fun zCross(a: Vec2) = Vec2(-a.y, a.x)
