package com.zhongba.engine

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.sin

/** 一次瞄准分析的结果：白球打 [targetNumber] 号球进 [pocket]。 */
data class AimResult(
    val targetNumber: Int,
    val pocketIndex: Int,
    val pocket: Pocket,
    val cuePosition: Vec2,
    /** 假想球：碰撞瞬间白球球心所在位置。 */
    val ghostBall: Vec2,
    /** 目标球被击出后的方向（单位向量），对准袋口可进窗口的中线。 */
    val objectDirection: Vec2,
    /** 目标球行进的终点（袋口处），用于画线。 */
    val objectPathEnd: Vec2,
    /** 切角：白球瞄准方向与目标球出球方向的夹角，度。 */
    val cutAngleDeg: Double,
    /** 厚薄：从白球看去两球重叠部分占球径的比例，1 为全球，0.5 为半球。 */
    val fullness: Double,
    /** 袋口容错：目标球方向允许偏差的角度（±），度；袋口完全看不到时为 0。 */
    val marginDeg: Double,
    val problems: List<AimProblem>,
) {
    val isPottable: Boolean get() = problems.isEmpty()

    /** 定杆（stun）时白球碰后沿切线离开的方向；正对目标球或根本打不到时为 null。 */
    val stunDirection: Vec2?
        get() {
            if (cutAngleDeg >= 90.0) return null
            val aim = (ghostBall - cuePosition).normalized()
            val tangent = aim - objectDirection * aim.dot(objectDirection)
            return if (tangent.length() < 1e-6) null else tangent.normalized()
        }
}

sealed interface AimProblem {
    /** 白球到假想球的路线被 [number] 号球挡住。 */
    data class CuePathBlocked(val number: Int) : AimProblem

    /** 目标球到袋口的路线被 [number] 号球挡住。 */
    data class ObjectPathBlocked(val number: Int) : AimProblem

    /** 切角太大，实际打不到。 */
    data object CutTooThin : AimProblem

    /** 角度太斜，目标球从袋角之间穿不过去。 */
    data object PocketNotReachable : AimProblem
}

object AimAnalyzer {
    /** 超过这个切角视为打不到（实战中 80° 以上已极难）。 */
    const val MAX_CUT_DEG = 85.0

    fun analyze(layout: BallLayout, targetNumber: Int, pocketIndex: Int): AimResult? {
        val table = layout.table
        val r = table.ballRadius
        val cue = layout.balls.firstOrNull { it.isCue } ?: return null
        val target = layout.balls.firstOrNull { it.number == targetNumber && !it.isCue } ?: return null
        val pocket = table.pockets.getOrNull(pocketIndex) ?: return null
        val ob = target.position

        val window = pocketWindow(ob, pocket, r)
        val objectDir = window?.direction ?: (pocket.center - ob).normalized()
        val ghost = ob - objectDir * (2 * r)
        val aimDir = (ghost - cue.position).normalized()
        val cutRad = abs(aimDir.angleTo(objectDir))
        val cutDeg = Math.toDegrees(cutRad)
        val pathEnd = ob + objectDir * (pocket.center - ob).length()

        val problems = buildList {
            if (window == null) add(AimProblem.PocketNotReachable)
            if (cutDeg > MAX_CUT_DEG) add(AimProblem.CutTooThin)
            val others = layout.balls.filter { !it.isCue && it.number != targetNumber }
            others.firstBlocking(cue.position, ghost, 2 * r)?.let { add(AimProblem.CuePathBlocked(it)) }
            others.firstBlocking(ob, pathEnd, 2 * r)?.let { add(AimProblem.ObjectPathBlocked(it)) }
        }

        return AimResult(
            targetNumber = targetNumber,
            pocketIndex = pocketIndex,
            pocket = pocket,
            cuePosition = cue.position,
            ghostBall = ghost,
            objectDirection = objectDir,
            objectPathEnd = pathEnd,
            cutAngleDeg = cutDeg,
            // 切角 ≥ 90° 时白球根本碰不到目标球的这一侧
            fullness = if (cutDeg >= 90.0) 0.0 else 1 - sin(cutRad),
            marginDeg = window?.let { Math.toDegrees(it.halfWidth) } ?: 0.0,
            problems = problems,
        )
    }

    /**
     * 自动选袋：优先能进的袋里切角最小的；都进不了就选切角最小的。
     */
    fun bestPocket(layout: BallLayout, targetNumber: Int): Int? =
        layout.table.pockets.indices
            .mapNotNull { analyze(layout, targetNumber, it) }
            .minWithOrNull(compareBy<AimResult> { !it.isPottable }.thenBy { it.cutAngleDeg })
            ?.pocketIndex

    private class PocketWindow(val direction: Vec2, val halfWidth: Double)

    /**
     * 目标球能进袋的方向范围：球心线必须从两个袋角之间穿过，并且离每个袋角至少一个球半径。
     * 返回范围中线方向和半宽（弧度）；范围为空则返回 null。
     */
    private fun pocketWindow(ob: Vec2, pocket: Pocket, r: Double): PocketWindow? {
        val toCenter = pocket.center - ob
        val bounds = listOf(pocket.jaws.first, pocket.jaws.second)
            .map { jaw ->
                val v = jaw - ob
                val clearance = asin((r / v.length()).coerceAtMost(1.0))
                toCenter.angleTo(v) to clearance
            }
            .sortedBy { it.first }
        val lower = bounds[0].first + bounds[0].second
        val upper = bounds[1].first - bounds[1].second
        if (upper <= lower) return null
        val mid = (lower + upper) / 2
        return PocketWindow(toCenter.normalized().rotated(mid), (upper - lower) / 2)
    }

    private fun List<Ball>.firstBlocking(from: Vec2, to: Vec2, clearance: Double): Int? =
        filter { it.position.distanceToSegment(from, to) < clearance - 1e-6 }
            .minByOrNull { it.position.distanceTo(from) }
            ?.number
}

/** 厚薄的通俗叫法，取最接近的档位。 */
fun fullnessLabel(fullness: Double): String =
    listOf(
        1.0 to "全球",
        0.75 to "3/4 球",
        0.5 to "1/2 球",
        0.25 to "1/4 球",
        0.125 to "1/8 球",
        0.0 to "极薄",
    ).minBy { abs(it.first - fullness) }.second
