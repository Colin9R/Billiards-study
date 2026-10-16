package com.zhongba.engine

import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 与 pooltool 的"标准答案"对比（实施方案 §3.5，详见 pooltool对齐实施方案.md）。
 *
 * 基准数据由 tools/validate/generate_baseline.py 生成，见 src/test/resources/pooltool_baseline.json。
 * 球形分三类：cut（切球）、cutSide（加塞切球）、bank（白球直接打一库）。
 * 每个球形有三组 pooltool 结果：
 * - realistic：pooltool 默认物理（球-球有摩擦 → 有 throw，库边 Stronge 柔性模型）；
 * - matched：球-球无摩擦，库边同上；
 * - han2005：球-球无摩擦，库边 Han 2005 模型（与引擎的库边模型相同）。
 *
 * 断言阈值 1 cm / 1°：三组都断言不吃库球形；han2005 组还断言吃库球形（碰到袋角、进袋的除外，
 * 因为 pooltool 的袋角是圆弧、引擎是点）。其余只出报告。
 * 完整误差表写到 build/reports/pooltool-comparison.txt。
 */
class PooltoolBaselineTest {
    private val table = TableSpec.ZHONG_BA
    private val shots: List<Map<String, Any?>> by lazy {
        val text = javaClass.getResource("/pooltool_baseline.json")!!.readText()
        @Suppress("UNCHECKED_CAST")
        (Json.parse(text) as Map<String, Any?>)["shots"] as List<Map<String, Any?>>
    }

    /**
     * 一组对比：拿哪组 pooltool 数据、引擎用什么参数、是否断言。
     * [assertCushion] 为 true 时，吃库球形也断言（碰到袋角或进袋的除外：两边袋口几何不同）。
     */
    private class Check(
        val variant: String,
        val params: PhysicsParams,
        val asserted: Boolean,
        val note: String,
        val assertCushion: Boolean = false,
    ) {
        fun covers(c: Comparison) = asserted && (c.clean || (assertCushion && !c.jawOrPocket))
    }

    private val frictionless = PhysicsParams(ballFriction = BallFriction.NONE)

    private val checks = listOf(
        // 吃库球形只出报告：pooltool 默认的 Stronge 库边模型和引擎用的 Han 2005 本来就不同
        Check("realistic", PhysicsParams.DEFAULT, asserted = true, note = "pooltool 默认物理 vs 引擎默认参数"),
        Check("matched", frictionless, asserted = true, note = "球-球都无摩擦，检验其余部分的实现"),
        // 引擎的库边就是 Han 2005，吃库球形也应当一致
        Check("han2005", frictionless, asserted = true, assertCushion = true, note = "球-球无摩擦、库边 Han 2005，检验库边实现"),
    )

    @Test
    fun matchesPooltoolOnShotsWithoutCushionContact() {
        val report = StringBuilder()
        val bad = mutableListOf<String>()
        for (check in checks) {
            val sim = Simulator(table, check.params)
            val comparisons = shots.map { compare(sim, it, check.variant) }
            report.append(render(check, comparisons))
            comparisons.filter { check.covers(it) && !it.withinTolerance }.forEach { bad += "[${check.variant}] " + it.describe() }
        }
        File("build/reports").mkdirs()
        File("build/reports/pooltool-comparison.txt").writeText(report.toString())
        println(report)
        assertTrue(bad.isEmpty(), "超出 1 cm / 1°：\n" + bad.joinToString("\n"))
    }

    // ---------- 对比 ----------

    class Comparison(
        val id: String,
        val category: String,
        /** pooltool 里没有吃库、进袋（引擎若吃库/进袋/碰球结果不同，会记一条"结果分叉"）。 */
        val clean: Boolean,
        /** pooltool 里有球碰到袋角或进袋。 */
        val jawOrPocket: Boolean,
        val errors: Map<String, Double>,
    ) {
        val maxMm get() = errors.filterKeys { !it.endsWith("°") }.values.maxOrNull() ?: 0.0
        val maxDeg get() = errors.filterKeys { it.endsWith("°") }.values.maxOrNull() ?: 0.0
        val withinTolerance get() = maxMm <= TOL_MM && maxDeg <= TOL_DEG
        fun describe() = "%-28s %s".format(id, errors.entries.joinToString("  ") { (k, v) -> "$k=%.2f".format(v) })
    }

    private fun compare(sim: Simulator, shot: Map<String, Any?>, variant: String): Comparison {
        val obStart = shot["object"]?.let(::vec)
        val balls = listOfNotNull(Ball(0, vec(shot["cue"])), obStart?.let { Ball(1, it) })
        val result = sim.simulate(
            BallLayout(table, balls),
            Shot(vec(shot["aim"]), num(shot["speed"]), num(shot["tipOffset"]), num(shot["side"])),
        )
        val engine = summarize(result, obStart)

        @Suppress("UNCHECKED_CAST")
        val ref = (shot["pooltool"] as Map<String, Any?>)[variant] as Map<String, Any?>
        val refBalls = listOfNotNull(obj(ref["cue"]), ref["object"]?.let(::obj))

        val errors = linkedMapOf<String, Double>()
        fun mm(name: String, a: Vec2?, b: Any?) {
            if (a != null && b != null) errors[name] = a.distanceTo(vec(b))
        }
        fun deg(name: String, a: Double?, b: Any?) {
            if (a != null && b != null) errors["$name°"] = abs(wrap(a - num(b)))
        }

        refBalls.zip(engine.balls).forEachIndexed { k, (r, e) ->
            val who = if (k == 0) "白球" else "目标球"
            mm("${who}停点mm", e.final, r["final"])
            mm("${who}首次碰库mm", e.firstCushion, r["firstCushion"])
            mm("${who}二次碰库mm", e.secondCushion, r["secondCushion"])
            deg("${who}出库方向", e.postCushionRollDir, r["postCushionRollDirDeg"])
        }
        deg("目标球方向", engine.obDir, ref["objectDirDeg"])
        deg("白球分离方向", engine.cueRollDir, ref["cueRollDirDeg"])

        val clean = refBalls.all { it["firstCushion"] == null && it["pocketed"] != true }
        // 一边吃库/进袋另一边不吃，说明轨迹已经分叉，同样记为不达标
        if (clean && (engine.cushion || engine.pocketed || engine.hit != (ref["hit"] == true))) {
            errors["结果分叉mm"] = Double.POSITIVE_INFINITY
        }
        val jawOrPocket = refBalls.any { it["jaw"] == true || it["pocketed"] == true }
        return Comparison(shot["id"] as String, shot["category"] as String, clean, jawOrPocket, errors)
    }

    private class BallSummary(
        val final: Vec2?,
        val firstCushion: Vec2?,
        val secondCushion: Vec2?,
        /** 第一次碰库后、下一次碰撞前，第一次进入纯滚动时的方向。 */
        val postCushionRollDir: Double?,
    )

    private class EngineSummary(
        val balls: List<BallSummary>,
        val hit: Boolean,
        val obDir: Double?,
        val cueRollDir: Double?,
        val cushion: Boolean,
        val pocketed: Boolean,
    )

    private fun summarize(result: SimulationResult, obStart: Vec2?): EngineSummary {
        val ev = result.events
        val hit = ev.firstOrNull { it.type == EventType.BALL_HIT && it.ballNumber == 0 }

        /** [after] 之后第一次转为纯滚动的方向；滚动段是直线，取到下一个事件的连线。要求在 [before] 之前。 */
        fun rollDir(ball: Int, after: Double, before: Double): Double? {
            val mine = ev.filter { it.ballNumber == ball && it.time > after }
            val roll = mine.firstOrNull { it.type == EventType.ROLLING } ?: return null
            if (roll.time >= before) return null
            val next = mine.firstOrNull { it.time > roll.time } ?: return null
            return angle(next.position - roll.position)
        }

        fun ball(n: Int): BallSummary {
            val cushions = ev.filter { it.type == EventType.CUSHION && it.ballNumber == n }
            val postDir = cushions.firstOrNull()?.let { c ->
                val next = ev.firstOrNull { it.ballNumber == n && it.time > c.time && it.type in setOf(EventType.CUSHION, EventType.BALL_HIT) }
                rollDir(n, c.time, next?.time ?: Double.POSITIVE_INFINITY)
            }
            return BallSummary(result.finalPosition(n), cushions.getOrNull(0)?.position, cushions.getOrNull(1)?.position, postDir)
        }

        val numbers = if (obStart != null) listOf(0, 1) else listOf(0)
        val firstCueCushion = ev.firstOrNull { it.type == EventType.CUSHION && it.ballNumber == 0 }
        return EngineSummary(
            balls = numbers.map(::ball),
            hit = hit != null,
            // 目标球被碰后先沿直线滑动，轨迹折线的第一段就是出球方向
            obDir = obStart?.let { s -> result.paths[1]?.firstOrNull { it.distanceTo(s) > 1e-6 }?.let { angle(it - s) } },
            // 碰后白球第一次转为纯滚动后的方向（要求在碰库之前）
            cueRollDir = hit?.let { rollDir(0, it.time, firstCueCushion?.time ?: Double.POSITIVE_INFINITY) },
            cushion = ev.any { it.type == EventType.CUSHION },
            pocketed = result.pocketed.isNotEmpty(),
        )
    }

    private fun render(check: Check, all: List<Comparison>): String = buildString {
        val scope = when {
            !check.asserted -> "只报告"
            check.assertCushion -> "断言：除碰袋角/进袋外的全部球形"
            else -> "断言：不吃库球形"
        }
        appendLine("==== pooltool [${check.variant}] 对比：${check.note}（$scope） ====")
        for ((category, group) in all.groupBy { it.category }) {
            val clean = group.filter { it.clean }
            val cushion = group.filter { !it.clean }
            appendLine("-- $category：不吃库 ${clean.size} 个，超出 1 cm / 1° 的 ${clean.count { !it.withinTolerance }} 个；吃库/进袋 ${cushion.size} 个")
            if (clean.isNotEmpty()) {
                appendLine("  [不吃库] 最大位置误差 %.2f mm，最大角度误差 %.2f°".format(clean.maxOf { it.maxMm }, clean.maxOf { it.maxDeg }))
                appendStats(clean)
                clean.filter { !it.withinTolerance }.forEach { appendLine("    ✗ " + it.describe()) }
            }
            if (cushion.isNotEmpty()) {
                val plain = cushion.filter { !it.jawOrPocket }
                appendLine("  [吃库/进袋] 其中没碰袋角、没进袋的 ${plain.size} 个，超出 1 cm / 1° 的 ${plain.count { !it.withinTolerance }} 个")
                appendStats(cushion)
                if (check.assertCushion) plain.filter { !it.withinTolerance }.forEach { appendLine("    ✗ " + it.describe()) }
            }
        }
        appendLine("-- 吃库/进袋球形明细")
        all.filter { !it.clean }.forEach { appendLine("    " + it.describe() + if (it.jawOrPocket) "  [袋角/进袋]" else "") }
        appendLine()
    }

    private fun StringBuilder.appendStats(cs: List<Comparison>) {
        val keys = cs.flatMap { it.errors.keys }.distinct()
        for (k in keys) {
            val v = cs.mapNotNull { it.errors[k] }.filter { it.isFinite() }.sorted()
            if (v.isEmpty()) continue
            appendLine("    %-14s n=%3d  中位 %8.2f  P90 %8.2f  最大 %8.2f".format(k, v.size, v[v.size / 2], v[(v.size * 9) / 10], v.last()))
        }
    }

    private fun angle(v: Vec2) = Math.toDegrees(atan2(v.y, v.x))
    private fun wrap(d: Double): Double = ((d + 540.0) % 360.0) - 180.0

    @Suppress("UNCHECKED_CAST")
    private fun vec(a: Any?): Vec2 = (a as List<Number>).let { Vec2(it[0].toDouble(), it[1].toDouble()) }
    private fun num(a: Any?): Double = (a as Number).toDouble()

    @Suppress("UNCHECKED_CAST")
    private fun obj(a: Any?): Map<String, Any?> = a as Map<String, Any?>

    companion object {
        const val TOL_MM = 10.0
        const val TOL_DEG = 1.0
    }
}


/** 测试用的极简 JSON 解析器（engine 模块不引入第三方依赖）。数字一律解析为 Double。 */
private object Json {
    fun parse(s: String): Any? = Parser(s).run { value().also { ws(); check(i == s.length) { "多余字符 @$i" } } }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) number() else error("意外字符 '$c' @$i")
            }
        }
        fun obj(): Map<String, Any?> {
            val m = linkedMapOf<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); check(s[i++] == ':')
                m[k] = value(); ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val l = mutableListOf<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                if (s[i++] == ']') return l
            }
        }
        fun str(): String {
            check(s[i++] == '"')
            val b = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (val e = s[i++]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r')
                        'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> b.append(e)
                    }
                } else b.append(s[i++])
            }
            i++
            return b.toString()
        }
        fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }
        fun lit(word: String, v: Any?): Any? { check(s.startsWith(word, i)); i += word.length; return v }
    }
}
