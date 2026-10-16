package com.zhongba.engine

/**
 * 回放时某一时刻一颗球的姿态。
 * @param heading 当前运动方向（单位向量），静止时沿用最后一次的方向
 * @param rolled 到此刻为止滚过的距离（毫米），用来画球面号码的滚动
 * @param sinking 进袋后的下落进度 0–1；没进袋为 null
 */
data class BallPose(val number: Int, val position: Vec2, val heading: Vec2, val rolled: Double, val sinking: Double?)

/** 两球相撞的瞬间，用于画碰撞闪光。[age] 为距此刻的秒数。 */
data class Impact(val position: Vec2, val age: Double)

/**
 * 按真实时间回放一杆的模拟结果：给出任意时刻每颗球的位置、方向、滚动距离和落袋进度。
 */
class ShotPlayback(private val start: BallLayout, val result: SimulationResult) {

    private class Track(val points: List<Vec2>, val times: List<Double>) {
        /** 每个采样点处累计走过的距离。 */
        val distances: DoubleArray = DoubleArray(points.size).also { d ->
            for (i in 1 until points.size) d[i] = d[i - 1] + points[i].distanceTo(points[i - 1])
        }
    }

    private val tracks: Map<Int, Track> = result.paths.mapNotNull { (number, points) ->
        result.pathTimes[number]?.let { number to Track(points, it) }
    }.toMap()

    private val pocketTimes: Map<Int, Double> =
        result.events.filter { it.type == EventType.POCKETED }.associate { it.ballNumber to it.time }

    /** 回放总时长：最后一颗球停下，再加上落袋动画。 */
    val duration: Double = result.duration + SINK_SECONDS

    fun poses(t: Double): List<BallPose> = start.balls.mapNotNull { ball ->
        val track = tracks[ball.number]
        val sinkStart = pocketTimes[ball.number]
        val sinking = sinkStart?.let { ((t - it) / SINK_SECONDS).coerceAtLeast(0.0) }?.takeIf { t >= sinkStart }
        if (sinking != null && sinking >= 1.0) return@mapNotNull null
        if (track == null) return@mapNotNull BallPose(ball.number, ball.position, Vec2(1.0, 0.0), 0.0, null)
        sample(ball.number, track, t, sinking)
    }

    /** [t] 之前 [window] 秒内发生的碰球，每次碰撞只算一次（取两球中点）。 */
    fun recentImpacts(t: Double, window: Double = 0.18): List<Impact> {
        val hits = result.events.filter { it.type == EventType.BALL_HIT && it.time <= t && t - it.time <= window }
        return hits.groupBy { it.time to setOf(it.ballNumber, it.otherNumber) }.values.map { pair ->
            val center = pair.map { it.position }.reduce { a, b -> a + b } * (1.0 / pair.size)
            Impact(center, t - pair.first().time)
        }
    }

    private fun sample(number: Int, track: Track, t: Double, sinking: Double?): BallPose {
        val times = track.times
        val points = track.points
        if (t <= times.first()) return BallPose(number, points.first(), headingAt(track, 1), 0.0, sinking)
        if (t >= times.last()) return BallPose(number, points.last(), headingAt(track, points.size - 1), track.distances.last(), sinking)
        // 二分找到 t 所在的采样段，再线性插值
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (times[mid] <= t) lo = mid else hi = mid
        }
        val span = times[hi] - times[lo]
        val k = if (span <= 0) 1.0 else (t - times[lo]) / span
        val position = points[lo] + (points[hi] - points[lo]) * k
        val rolled = track.distances[lo] + (track.distances[hi] - track.distances[lo]) * k
        return BallPose(number, position, headingAt(track, hi), rolled, sinking)
    }

    /** 第 [index] 个采样点之前最近一段有长度的线段方向。 */
    private fun headingAt(track: Track, index: Int): Vec2 {
        var i = index.coerceIn(1, track.points.size - 1)
        while (i > 0) {
            val d = track.points[i] - track.points[i - 1]
            if (d.length() > 1e-6) return d.normalized()
            i--
        }
        return Vec2(1.0, 0.0)
    }

    companion object {
        /** 落袋动画时长，秒。 */
        const val SINK_SECONDS = 0.3
    }
}
