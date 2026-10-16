package com.zhongba.engine

import java.util.stream.Collectors

/**
 * 清台规划（中八规则）：彩球不分先后，任意顺序打进，黑 8 必须最后打。
 * 给定球形和要清的球组，找出一条把这些彩球全部打进、最后打进黑 8 的路线，
 * 以及每一杆的袋口、击打点和力度。击球顺序由规划器按走位挑选，不是规则要求。
 *
 * 做法是束搜索（beam search）：每一步枚举"目标球 × 袋口 × 高低杆 × 力度"，
 * 用物理引擎（含 throw 补偿）真实模拟每一杆，只保留合法的（先碰目标球、目标球进、白球不落袋、黑 8 不提前进）；
 * 按"这一杆的难度 × 停球后打下一颗的难度"打分，每层只保留最好的几条路线继续往下搜。
 */
object RunOutPlanner {

    /** 要清的球组：练习时清全部彩球；实战里球组已定时只清全色或花色。 */
    enum class Group(val label: String, private val range: IntRange) {
        ALL("全部彩球", 1..15),
        SOLIDS("全色", 1..7),
        STRIPES("花色", 9..15),
        ;

        /** 这颗球是否属于本组（黑 8 不属于任何组）。 */
        fun owns(number: Int) = number != 8 && number in range
    }

    /** 路线中的一杆。[before] 是打这一杆之前的球形，[after] 是模拟出的打完之后的球形。 */
    data class Step(
        val target: Int,
        val aim: AimResult,
        val hitPoint: HitPoint,
        val power: Int,
        val throwCorrectionDeg: Double,
        val result: SimulationResult,
        val before: BallLayout,
        val after: BallLayout,
        /** 这一杆本身的难易度 0–1。 */
        val ease: Double,
        /** 停球后打下一颗的难易度 0–1；清台的最后一杆为 1。 */
        val leave: Double,
        /**
         * 力度容错 0–1：大一档、小一档力度也能进球、走位也差不多好时为 1；
         * 力度稍有偏差就进不了或走位变坏时接近 0。
         */
        val tolerance: Double = 1.0,
    ) {
        val cueStop: Vec2? get() = after.balls.firstOrNull { it.isCue }?.position
    }

    data class Plan(val steps: List<Step>, val cleared: Boolean, val score: Double)

    /**
     * 候选打法缓存，按球形（同一个 [BallLayout] 对象）记住"这个球形下所有合法的下一杆"。
     * 打完一杆后的新球形就是上一轮搜索里展开过的节点，点球击打也和规划第一层算的是同一批打法，
     * 共用一个缓存就不必重复模拟。每条打法带着完整轨迹，占内存，所以只保留最近的若干个球形。
     */
    class Cache(private val maxEntries: Int = 48) {
        private val entries = object : LinkedHashMap<BallLayout, List<Step>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<BallLayout, List<Step>>) = size > maxEntries
        }

        @Synchronized
        internal fun get(layout: BallLayout): List<Step>? = entries[layout]

        @Synchronized
        internal fun put(layout: BallLayout, steps: List<Step>) {
            entries[layout] = steps
        }
    }

    /** 现在可以打的球：本组任意一颗彩球；本组全部打完后才轮到黑 8。 */
    fun remainingTargets(layout: BallLayout, group: Group): List<Int> {
        val own = layout.balls.map { it.number }.filter(group::owns)
        return when {
            own.isNotEmpty() -> own
            layout.balls.any { it.number == 8 } -> listOf(8)
            else -> emptyList()
        }
    }

    /**
     * @param beamWidth 每层保留的路线数，越大越可能找到清台路线，但更慢
     * @param isCancelled 计算期间会定期检查，返回 true 时尽快停止并返回目前最好的路线
     * @param onProgress 0–1 的进度
     */
    fun plan(
        simulator: Simulator,
        layout: BallLayout,
        group: Group,
        beamWidth: Int = 3,
        isCancelled: () -> Boolean = { false },
        onProgress: (Float) -> Unit = {},
        cache: Cache = Cache(),
    ): Plan {
        // 剩下的本方球 + 最后的黑 8
        val depth = layout.balls.count { group.owns(it.number) } + if (layout.balls.any { it.number == 8 }) 1 else 0
        if (depth == 0 || layout.balls.none { it.isCue }) return Plan(emptyList(), cleared = depth == 0, score = 1.0)

        var beam = listOf(Partial(layout, emptyList(), 1.0))
        val finished = mutableListOf<Partial>()
        var deadEnds = mutableListOf<Partial>()

        for (level in 0 until depth) {
            if (isCancelled()) break
            val expanded = mutableListOf<Partial>()
            beam.forEachIndexed { i, partial ->
                if (isCancelled()) return@forEachIndexed
                val steps = cachedCandidates(simulator, partial.layout, group, cache)
                if (steps.isEmpty()) deadEnds += partial
                steps.forEach { step ->
                    // 路线总分也计入打法偏好，最终选出的整条路线才会真的偏向中杆、小力
                    val next = Partial(step.after, partial.steps + step, partial.cumulative * step.ease * preference(step))
                    if (remainingTargets(step.after, group).isEmpty()) finished += next else expanded += next
                }
                onProgress((level + (i + 1f) / beam.size) / depth)
            }
            // 同一组已进球、白球停点相近的路线只留一条，避免束里全是同一条路线的变体
            beam = expanded
                .sortedByDescending { it.rank }
                .distinctBy { p -> p.steps.map { it.target }.toSet() to p.steps.last().cueStop?.let { (it.x / 100).toInt() to (it.y / 100).toInt() } }
                .take(beamWidth)
            if (beam.isEmpty()) break
        }

        finished.maxByOrNull { it.cumulative }?.let { return Plan(it.steps, cleared = true, score = it.cumulative) }
        // 清不完：返回打得最远的路线
        val best = (beam + deadEnds).filter { it.steps.isNotEmpty() }
            .maxWithOrNull(compareBy<Partial> { it.steps.size }.thenBy { it.rank })
        return Plan(best?.steps.orEmpty(), cleared = false, score = best?.cumulative ?: 0.0)
    }

    private class Partial(val layout: BallLayout, val steps: List<Step>, val cumulative: Double) {
        /** 排序用：已走过各杆的（难度 × 打法偏好）乘积 × 最后一杆留下的走位。 */
        val rank: Double get() = cumulative * (steps.lastOrNull()?.leave ?: 1.0)
    }

    /**
     * 现在把 [target] 号球打进的最好一杆（袋口、杆法、力度都挑走位最好的）；打不进或按规则还不能打时返回 null。
     * 用于用户在台面上点球"打掉它"：模拟真实击打，白球停在打完后的位置。
     */
    fun bestShotOn(simulator: Simulator, layout: BallLayout, group: Group, target: Int, cache: Cache? = null): Step? {
        if (target !in remainingTargets(layout, group)) return null
        // 规划已经算过这个球形时直接从缓存里挑，否则只算这一颗球
        val steps = cache?.get(layout)?.filter { it.target == target } ?: candidates(simulator, layout, group, onlyTarget = target)
        return steps.maxByOrNull(::score)
    }

    private fun cachedCandidates(simulator: Simulator, layout: BallLayout, group: Group, cache: Cache): List<Step> =
        cache.get(layout) ?: candidates(simulator, layout, group).also { cache.put(layout, it) }

    /** 某个球形下所有合法的下一杆，每个"目标球 + 袋口"只保留最好的几种打法。 */
    internal fun candidates(simulator: Simulator, layout: BallLayout, group: Group, onlyTarget: Int? = null): List<Step> {
        val targets = remainingTargets(layout, group).filter { onlyTarget == null || it == onlyTarget }
        val aims = targets.flatMap { target ->
            layout.table.pockets.indices
                .mapNotNull { AimAnalyzer.analyze(layout, target, it) }
                .filter { it.isPottable }
                .sortedBy { it.cutAngleDeg }
                .take(POCKETS_PER_TARGET)
        }
        // 每个（瞄准, 高低杆）组合互相独立，可以并行模拟
        val jobs = aims.flatMap { aim -> Stroke.entries.map { aim to it } }
        return jobs.parallelStream()
            .map { (aim, stroke) -> tryPowers(simulator, layout, group, aim, HitPoint(0.0, stroke.tipOffset)) }
            .collect(Collectors.toList())
            .flatten()
            .groupBy { it.target to it.aim.pocketIndex }
            .values
            .flatMap { sameShot -> sameShot.sortedByDescending(::score).take(VARIANTS_PER_SHOT) }
    }

    private fun tryPowers(simulator: Simulator, layout: BallLayout, group: Group, aim: AimResult, hit: HitPoint): List<Step> {
        val ease = PositionPlanner.shotEase(aim, layout.table)
        // 从大力往小力试：相邻力度的 throw 补偿很接近，用上一档补偿后的假想球作为迭代起点；
        // 一旦目标球干净地停在袋口前（没碰别的球），更小的力度也一定送不到，直接停止
        var ghost = aim.ghostBall
        val byPower = mutableMapOf<Int, Step>()
        for (power in PowerLevels.count downTo 1) {
            val shot = simulator.simulateAimed(layout, aim, PowerLevels.speed(power), hit, startGhost = ghost)
            val result = shot.result
            if (result.firstContact == aim.targetNumber) ghost = shot.correctedGhost
            if (stoppedShort(result, aim.targetNumber)) break
            stepFor(layout, group, aim, hit, power, ease, shot)?.let { byPower[power] = it }
        }
        // 容错：相邻力度（各档已经模拟过，不用额外计算）也能进、走位也不比这一档差多少，就是容错高的打法
        return byPower.values.map { step ->
            val neighbors = listOf(step.power - 1, step.power + 1).filter { it in 1..PowerLevels.count }
            val tolerance = neighbors.map { p ->
                byPower[p]?.let { (it.leave / step.leave.coerceAtLeast(0.05)).coerceAtMost(1.0) } ?: 0.0
            }.average()
            step.copy(tolerance = tolerance)
        }
    }

    /** 先碰到目标球、目标球没进，也没再碰到其他球：说明力度不够送到袋口。 */
    private fun stoppedShort(result: SimulationResult, target: Int): Boolean =
        result.firstContact == target &&
            target !in result.pocketed &&
            result.events.none { it.type == EventType.BALL_HIT && it.ballNumber == target && it.otherNumber != 0 }

    /** 这一杆合法（先碰目标球、目标球进、白球不落袋、黑 8 不提前进）时，算出打完的球形和走位评分。 */
    private fun stepFor(layout: BallLayout, group: Group, aim: AimResult, hit: HitPoint, power: Int, ease: Double, shot: AimedShot): Step? {
        val result = shot.result
        val legal = result.firstContact == aim.targetNumber &&
            aim.targetNumber in result.pocketed &&
            0 !in result.pocketed &&
            (aim.targetNumber == 8 || 8 !in result.pocketed)
        if (!legal) return null
        val after = BallLayout(
            layout.table,
            result.finalStates.filter { it.motion != Motion.POCKETED }.map { Ball(it.number, it.position) },
        )
        val cue = after.balls.first { it.isCue }.position
        val next = remainingTargets(after, group)
        val leave = if (next.isEmpty()) 1.0 else leaveZoneScore(after, next, cue, result.paths[0])
        return Step(aim.targetNumber, aim, hit, power, shot.throwCorrectionDeg, result, layout, after, ease, leave)
    }

    /**
     * 走位按落点区域评估（人不是机器，白球停不到精确的点）：先找出停点处最好打的下一颗，
     * 再看停点沿白球前进方向前后 [ZONE_ALONG_MM]、左右 [ZONE_ACROSS_MM] 几处打这颗球的平均难易度。
     */
    private fun leaveZoneScore(after: BallLayout, next: List<Int>, cue: Vec2, cuePath: List<Vec2>?): Double {
        val nextBall = next.maxBy { PositionPlanner.positionScore(after, it, cue) }
        val heading = cuePath?.zipWithNext()?.lastOrNull { (a, b) -> a.distanceTo(b) > 1e-6 }?.let { (a, b) -> (b - a).normalized() } ?: Vec2(1.0, 0.0)
        val side = zCross(heading)
        val zone = listOf(
            cue,
            cue + heading * ZONE_ALONG_MM,
            cue - heading * ZONE_ALONG_MM,
            cue + side * ZONE_ACROSS_MM,
            cue - side * ZONE_ACROSS_MM,
        ).map { after.table.clampToPlayArea(it) }
        return zone.map { PositionPlanner.positionScore(after, nextBall, it) }.average()
    }

    /** 一杆的综合得分：难度 × 走位 × 打法偏好（规划和备选排序都用它）。 */
    fun score(step: Step): Double = step.ease * step.leave * preference(step)

    /**
     * 路线中某一杆的几种打法（同一颗目标球，换袋口或换杆法），按综合得分从好到差；
     * 同一袋口、同一杆法只保留最好的力度。规划时这些打法已经算过并缓存，通常不用重新模拟。
     */
    fun alternatives(simulator: Simulator, step: Step, group: Group, cache: Cache, count: Int = 3): List<Step> =
        cachedCandidates(simulator, step.before, group, cache)
            .filter { it.target == step.target }
            .sortedByDescending(::score)
            .distinctBy { it.aim.pocketIndex to it.hitPoint.y }
            .take(count)

    /** 把路线第 [index] 杆换成 [choice]，前面的杆不变，后面从 [choice] 打完的球形重新规划。 */
    fun planWith(
        simulator: Simulator,
        plan: Plan,
        index: Int,
        choice: Step,
        group: Group,
        beamWidth: Int = 3,
        isCancelled: () -> Boolean = { false },
        cache: Cache = Cache(),
    ): Plan {
        val prefix = plan.steps.take(index) + choice
        val rest = plan(simulator, choice.after, group, beamWidth, isCancelled, cache = cache)
        val prefixScore = prefix.fold(1.0) { acc, s -> acc * s.ease * preference(s) }
        return Plan(prefix + rest.steps, cleared = rest.cleared, score = prefixScore * rest.score)
    }

    /**
     * 打法偏好（面向业余球手，越简单越好）：优先靠自然分离角走位（中杆、小高小低杆），少用大杆法；
     * 力度偏小更好控制；低杆随距离越远越难（长台低杆对出杆要求很高），额外扣分；
     * 白球或目标球再碰到别的球（K 球）走位不可控，每碰一次大幅扣分。
     * 例：近台低杆 −0.5 约扣 18%；1.5 m 长台低杆约扣 38%；8 档力度约扣 21%。
     */
    private fun preference(step: Step): Double {
        val y = step.hitPoint.y
        val distance = step.aim.cuePosition.distanceTo(step.aim.ghostBall)
        val longDraw = if (y < 0) LONG_DRAW_PENALTY * -y * ((distance - 500) / 1000).coerceIn(0.0, 1.0) else 0.0
        val base = (1 - 0.03 * (step.power - 1) - 0.35 * kotlin.math.abs(y) - longDraw).coerceAtLeast(0.05)
        // K 球：除了白球碰目标球，每多一次碰球（白球撞到别的球、目标球路上碰到别的球、被撞的球再连环相碰）都很难控制
        val kiss = Math.pow(KISS_FACTOR, kissCount(step.result).toDouble())
        // 容错：力度稍有偏差就失败的打法，对业余球手风险大
        return base * kiss * (TOLERANCE_FLOOR + (1 - TOLERANCE_FLOOR) * step.tolerance)
    }

    /** 一杆中除第一次"白球碰目标球"以外的碰球次数（同一时刻同一对球只算一次）。 */
    private fun kissCount(result: SimulationResult): Int =
        (result.events.filter { it.type == EventType.BALL_HIT }.map { it.time to setOf(it.ballNumber, it.otherNumber) }.distinct().size - 1)
            .coerceAtLeast(0)

    private const val LONG_DRAW_PENALTY = 0.4

    /** 白球落点区域：沿前进方向的误差（力度控制）比左右误差（方向控制）大，毫米。 */
    private const val ZONE_ALONG_MM = 120.0
    private const val ZONE_ACROSS_MM = 60.0

    /** 每多一次 K 球，打法偏好乘以这个系数：不到万不得已不选。 */
    private const val KISS_FACTOR = 0.45

    /** 力度完全没有容错（大一档小一档都不行）时打法偏好的系数。 */
    private const val TOLERANCE_FLOOR = 0.4

    private const val POCKETS_PER_TARGET = 2
    private const val VARIANTS_PER_SHOT = 2
}
