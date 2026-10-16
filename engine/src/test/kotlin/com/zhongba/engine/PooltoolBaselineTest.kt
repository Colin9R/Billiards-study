package com.zhongba.engine

import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 与 pooltool 的"标准答案"对比（实施方案 §3.5）。
 *
 * 基准数据由 tools/validate/generate_baseline.py 生成，见 src/test/resources/pooltool_baseline.json。
 * 每个球形有两组 pooltool 结果：
 * - realistic：pooltool 默认物理（球-球有摩擦 → 有 throw，库边 Stronge 柔性模型）；
 * - matched：球-球无摩擦，与本引擎的碰撞假设一致。
 *
 * 只对"不吃库、不进袋"的球形做 1 cm / 1° 断言；吃库的球形只出报告。
 * 完整误差表写到 build/reports/pooltool-comparison.txt。
 */
class PooltoolBaselineTest {
    private val table = TableSpec.ZHONG_BA
    private val sim = Simulator(table)
    private val shots: List<Map<String, Any?>> by lazy {
        val text = javaClass.getResource("/pooltool_baseline.json")!!.readText()
        @Suppress("UNCHECKED_CAST")
        (Json.parse(text) as Map<String, Any?>)["shots"] as List<Map<String, Any?>>
    }

    @Test
    fun matchesPooltoolOnShotsWithoutCushionContact() {
        val report = StringBuilder()
        val failures = mutableMapOf<String, List<Comparison>>()
        for (variant in listOf("matched", "realistic")) {
            val comparisons = shots.map { compare(it, variant) }
            report.append(render(variant, comparisons))
            failures[variant] = comparisons.filter { it.clean && !it.withinTolerance }
        }
        File("build/reports").mkdirs()
        File("build/reports/pooltool-comparison.txt").writeText(report.toString())
        println(report)

        // 断言对象是 matched：它与引擎的模型假设相同，误差只来自实现。
        // realistic 的差距（主要是 throw）在报告里列出，不在这里断言。
        val bad = failures.getValue("matched")
        assertTrue(bad.isEmpty(), "不吃库球形超出 1 cm / 1°：\n" + bad.joinToString("\n") { it.describe() })
    }

    // ---------- 对比 ----------

    class Comparison(
        val id: String,
        /** 双方都没有吃库、进袋，且都碰到了目标球（或都没碰到）。 */
        val clean: Boolean,
        val cushion: Boolean,
        val errors: Map<String, Double>,
    ) {
        val maxMm get() = errors.filterKeys { !it.endsWith("°") }.values.maxOrNull() ?: 0.0
        val maxDeg get() = errors.filterKeys { it.endsWith("°") }.values.maxOrNull() ?: 0.0
        val withinTolerance get() = maxMm <= TOL_MM && maxDeg <= TOL_DEG
        fun describe() = "%-22s %s".format(id, errors.entries.joinToString("  ") { (k, v) -> "$k=%.2f".format(v) })
    }

    private fun compare(shot: Map<String, Any?>, variant: String): Comparison {
        val id = shot["id"] as String
        val cue = vec(shot["cue"])
        val ob = vec(shot["object"])
        val layout = BallLayout(table, listOf(Ball(0, cue), Ball(1, ob)))
        val result = sim.simulate(layout, Shot(vec(shot["aim"]), num(shot["speed"]), num(shot["tipOffset"])))
        val engine = summarize(result, ob)

        @Suppress("UNCHECKED_CAST")
        val ref = (shot["pooltool"] as Map<String, Any?>)[variant] as Map<String, Any?>
        val refCue = obj(ref["cue"])
        val refOb = obj(ref["object"])

        val errors = linkedMapOf<String, Double>()
        fun mm(name: String, a: Vec2?, b: Vec2?) {
            if (a != null && b != null) errors[name] = a.distanceTo(b)
        }
        fun deg(name: String, a: Double?, b: Double?) {
            if (a != null && b != null) errors["$name°"] = abs(wrap(a - b))
        }

        mm("白球停点mm", engine.cueFinal, vec(refCue["final"]))
        mm("目标球停点mm", engine.obFinal, vec(refOb["final"]))
        mm("白球首次碰库mm", engine.cueCushion, refCue["firstCushion"]?.let(::vec))
        mm("目标球首次碰库mm", engine.obCushion, refOb["firstCushion"]?.let(::vec))
        deg("目标球方向", engine.obDir, ref["objectDirDeg"] as Double?)
        deg("白球分离方向", engine.cueRollDir, ref["cueRollDirDeg"] as Double?)

        val refCushion = refCue["firstCushion"] != null || refOb["firstCushion"] != null
        val refPocket = refCue["pocketed"] == true || refOb["pocketed"] == true
        val hitAgrees = engine.hit == (ref["hit"] == true)
        // 一边吃库/进袋另一边不吃，说明轨迹已经分叉，同样记为不达标
        val clean = !refCushion && !refPocket
        if (clean && (engine.cushion || engine.pocketed || !hitAgrees)) {
            errors["结果分叉mm"] = Double.POSITIVE_INFINITY
        }
        return Comparison(id, clean, refCushion || engine.cushion, errors)
    }

    private class EngineSummary(
        val hit: Boolean,
        val cueFinal: Vec2?,
        val obFinal: Vec2?,
        val cueCushion: Vec2?,
        val obCushion: Vec2?,
        val obDir: Double?,
        val cueRollDir: Double?,
        val cushion: Boolean,
        val pocketed: Boolean,
    )

    private fun summarize(result: SimulationResult, obStart: Vec2): EngineSummary {
        val ev = result.events
        val hit = ev.firstOrNull { it.type == EventType.BALL_HIT && it.ballNumber == 0 }
        val cueCushion = ev.firstOrNull { it.type == EventType.CUSHION && it.ballNumber == 0 }
        val obCushion = ev.firstOrNull { it.type == EventType.CUSHION && it.ballNumber == 1 }

        // 目标球被碰后先沿直线滑动，轨迹折线的第一段就是出球方向
        val obDir = result.paths[1]?.let { p -> p.firstOrNull { it.distanceTo(obStart) > 1e-6 }?.let { angle(it - obStart) } }

        // 碰后白球第一次转为纯滚动后的方向（要求在碰库之前）
        val cueRollDir = hit?.let { h ->
            val after = ev.filter { it.ballNumber == 0 && it.time > h.time }
            val roll = after.firstOrNull { it.type == EventType.ROLLING } ?: return@let null
            if (cueCushion != null && cueCushion.time < roll.time) return@let null
            val next = after.firstOrNull { it.time > roll.time } ?: return@let null
            angle(next.position - roll.position)
        }

        return EngineSummary(
            hit = hit != null,
            cueFinal = result.finalPosition(0),
            obFinal = result.finalPosition(1),
            cueCushion = cueCushion?.position,
            obCushion = obCushion?.position,
            obDir = obDir,
            cueRollDir = cueRollDir,
            cushion = cueCushion != null || obCushion != null,
            pocketed = result.pocketed.isNotEmpty(),
        )
    }

    private fun render(variant: String, all: List<Comparison>): String = buildString {
        val clean = all.filter { it.clean }
        val cushion = all.filter { !it.clean }
        appendLine("==== pooltool [$variant] 对比 ====")
        appendLine("不吃库球形 ${clean.size} 个，超出 1 cm / 1° 的 ${clean.count { !it.withinTolerance }} 个")
        appendLine("  最大位置误差 %.2f mm，最大角度误差 %.2f°".format(clean.maxOf { it.maxMm }, clean.maxOf { it.maxDeg }))
        appendStats(clean)
        clean.filter { !it.withinTolerance }.forEach { appendLine("  ✗ " + it.describe()) }
        appendLine("吃库/进袋球形 ${cushion.size} 个（仅报告，不断言）")
        appendStats(cushion)
        cushion.forEach { appendLine("    " + it.describe()) }
        appendLine()
    }

    private fun StringBuilder.appendStats(cs: List<Comparison>) {
        val keys = cs.flatMap { it.errors.keys }.distinct()
        for (k in keys) {
            val v = cs.mapNotNull { it.errors[k] }.filter { it.isFinite() }.sorted()
            if (v.isEmpty()) continue
            appendLine("  %-14s n=%3d  中位 %7.2f  P90 %7.2f  最大 %7.2f".format(k, v.size, v[v.size / 2], v[(v.size * 9) / 10], v.last()))
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
