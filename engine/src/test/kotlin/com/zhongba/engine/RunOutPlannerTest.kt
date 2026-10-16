package com.zhongba.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunOutPlannerTest {
    private val table = TableSpec.ZHONG_BA
    private val solids = RunOutPlanner.Group.SOLIDS

    @Test
    fun blackEightComesLast() {
        val layout = BallLayout(table, listOf(Ball(0, Vec2(500.0, 600.0)), Ball(3, Vec2(900.0, 600.0)), Ball(8, Vec2(1500.0, 600.0)), Ball(12, Vec2(1000.0, 1000.0))))
        assertEquals(listOf(3), RunOutPlanner.remainingTargets(layout, solids))
        val withoutThree = BallLayout(table, layout.balls.filter { it.number != 3 })
        assertEquals(listOf(8), RunOutPlanner.remainingTargets(withoutThree, solids))
        assertEquals(listOf(12), RunOutPlanner.remainingTargets(layout, RunOutPlanner.Group.STRIPES))
        // 练习模式：全部彩球都可以打，黑 8 仍然最后
        assertEquals(listOf(3, 12), RunOutPlanner.remainingTargets(layout, RunOutPlanner.Group.ALL))
        val onlyEight = BallLayout(table, layout.balls.filter { it.number == 0 || it.number == 8 })
        assertEquals(listOf(8), RunOutPlanner.remainingTargets(onlyEight, RunOutPlanner.Group.ALL))
    }

    @Test
    fun allGroupClearsEveryColoredBallBeforeTheEight() {
        val layout = BallLayout(
            table,
            listOf(
                Ball(0, Vec2(1000.0, 700.0)),
                Ball(2, Vec2(1700.0, 420.0)),
                Ball(11, Vec2(600.0, 400.0)),
                Ball(8, Vec2(900.0, 950.0)),
            ),
        )
        val plan = RunOutPlanner.plan(Simulator(table), layout, RunOutPlanner.Group.ALL)
        assertTrue(plan.steps.isNotEmpty())
        val targets = plan.steps.map { it.target }
        // 黑 8 只能出现在最后一杆，并且之前所有彩球都已经进袋
        targets.dropLast(1).forEach { assertTrue(it != 8, "黑 8 不能提前打：$targets") }
        if (8 in targets) assertEquals(setOf(2, 11), targets.dropLast(1).toSet())
        plan.steps.forEach { assertTrue(8 !in it.result.pocketed || it.target == 8, "黑 8 不能被连带打进") }
    }

    @Test
    fun bestShotOnPotsTheChosenBallAndMovesTheCue() {
        val layout = BallLayout.fullTable(table)
        val step = RunOutPlanner.bestShotOn(Simulator(table), layout, RunOutPlanner.Group.ALL, 5)!!
        assertEquals(5, step.target)
        assertTrue(5 in step.result.pocketed && 0 !in step.result.pocketed)
        assertTrue(step.after.balls.none { it.number == 5 })
        assertTrue(step.cueStop != layout.balls.first { it.isCue }.position, "白球应停在打完后的走位点")
        // 彩球没打完时不能打黑 8
        assertEquals(null, RunOutPlanner.bestShotOn(Simulator(table), layout, RunOutPlanner.Group.ALL, 8))
    }

    @Test
    fun simpleLayoutIsClearedInOrder() {
        val layout = BallLayout(
            table,
            listOf(
                Ball(0, Vec2(1000.0, 700.0)),
                Ball(2, Vec2(1700.0, 420.0)),
                Ball(8, Vec2(900.0, 950.0)),
            ),
        )
        val plan = RunOutPlanner.plan(Simulator(table), layout, solids)
        assertTrue(plan.cleared, "两颗球的简单球形应能清台")
        assertEquals(listOf(2, 8), plan.steps.map { it.target })
        assertStepsChain(plan)
    }

    @Test
    fun practiceLayoutPlanIsLegalAndFast() {
        val layout = BallLayout.practice(table)
        val start = System.nanoTime()
        val plan = RunOutPlanner.plan(Simulator(table), layout, solids)
        val seconds = (System.nanoTime() - start) / 1e9
        println("practice: cleared=${plan.cleared} steps=${plan.steps.map { "${it.target}@${it.aim.pocket.name}/${it.hitPoint.label}/${it.power}" }} score=${plan.score} time=${"%.1f".format(seconds)}s")
        assertTrue(plan.steps.isNotEmpty())
        assertStepsChain(plan)
        assertTrue(seconds < 30, "规划耗时 $seconds 秒，太慢")
    }

    /** 每一杆都合法，且下一杆从上一杆打完的球形开始。 */
    private fun assertStepsChain(plan: RunOutPlanner.Plan) {
        plan.steps.forEachIndexed { i, step ->
            assertEquals(step.target, step.result.firstContact)
            assertTrue(step.target in step.result.pocketed)
            assertTrue(0 !in step.result.pocketed)
            if (i > 0) assertTrue(plan.steps[i - 1].after === step.before)
        }
    }
}
