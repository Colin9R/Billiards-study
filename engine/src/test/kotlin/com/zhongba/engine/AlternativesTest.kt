package com.zhongba.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlternativesTest {
    private val table = TableSpec.ZHONG_BA
    private val sim = Simulator(table)
    private val group = RunOutPlanner.Group.ALL

    @Test
    fun alternativesAreDistinctWaysToPotTheSameBall() {
        val cache = RunOutPlanner.Cache()
        val plan = RunOutPlanner.plan(sim, BallLayout.practice(table), group, beamWidth = 2, cache = cache)
        plan.steps.forEach { step ->
            val options = RunOutPlanner.alternatives(sim, step, group, cache)
            assertTrue(options.isNotEmpty(), "${step.target} 号应至少有一种打法")
            assertTrue(options.all { it.target == step.target && it.before === step.before })
            assertEquals(options.size, options.map { it.aim.pocketIndex to it.hitPoint.y }.toSet().size, "备选应互不相同")
            val scores = options.map(RunOutPlanner::score)
            assertEquals(scores.sortedDescending(), scores)
        }
    }

    @Test
    fun choosingAnAlternativeKeepsPrefixAndReplansTheRest() {
        val cache = RunOutPlanner.Cache()
        val plan = RunOutPlanner.plan(sim, BallLayout.practice(table), group, beamWidth = 2, cache = cache)
        val index = 1
        val choice = RunOutPlanner.alternatives(sim, plan.steps[index], group, cache).last()
        val updated = RunOutPlanner.planWith(sim, plan, index, choice, group, beamWidth = 2, cache = cache)
        assertEquals(plan.steps.take(index), updated.steps.take(index))
        assertTrue(updated.steps[index] === choice)
        // 每一杆都从上一杆打完的球形开始，黑 8 在最后
        updated.steps.zipWithNext().forEach { (a, b) -> assertTrue(a.after === b.before) }
        if (updated.cleared) assertEquals(8, updated.steps.last().target)
    }
}
