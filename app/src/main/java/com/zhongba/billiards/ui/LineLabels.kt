package com.zhongba.billiards.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.zhongba.engine.AimResult
import com.zhongba.engine.BallLayout
import com.zhongba.engine.PowerLevels
import com.zhongba.engine.Vec2
import java.util.Locale

/**
 * 在台面线条旁边贴文字标签。按优先级逐个放置，和已放的标签、球重叠就换位置，
 * 实在放不下就跳过，保证文字不会叠在一起。
 */
internal fun DrawScope.drawLineLabels(
    t: TableTransform,
    layout: BallLayout,
    aim: AimResult,
    preview: ShotPreview?,
    textMeasurer: TextMeasurer,
) {
    val cloth = Rect(t.toScreen(Vec2.ZERO), t.toScreen(Vec2(layout.table.length, layout.table.width)))
    val r = t.toScreen(t.table.ballRadius)
    val placer = LabelPlacer(cloth, layout.balls.map { Rect(t.toScreen(it.position), r * 1.1f) })

    fun label(text: String, color: Color, points: List<Vec2>, size: TextUnit = 11.sp) {
        val layoutText = textMeasurer.measure(text, TextStyle(color = color, fontSize = size, fontWeight = FontWeight.Medium))
        val box = Size(layoutText.size.width + 10f, layoutText.size.height + 4f)
        val rect = placer.place(points.map { t.toScreen(it) }, box) ?: return
        drawRoundRect(Color.Black.copy(alpha = 0.62f), rect.topLeft, rect.size, CornerRadius(6f))
        drawRoundRect(color, rect.topLeft, Size(3f, rect.height), CornerRadius(2f))
        drawText(layoutText, topLeft = rect.topLeft + Offset(6f, 2f))
    }

    // 优先级从高到低：当前走位线 > 瞄准线 > 目标球路线 > 假想球 > 定杆线 > 其他球 > 其他档位
    val current = preview?.current
    current?.paths?.get(0)?.let { path ->
        // 标签放在碰球之后那段，别和瞄准线挤在一起
        val hit = path.indexOfFirst { it.distanceTo(aim.ghostBall) < t.table.ballRadius }
        val it = if (hit in 0 until path.size - 1) path.drop(hit) else path
        label(
            String.format(Locale.US, "白球走位 · %s · 力度%.1f", hitPointName(preview.hitPoint), preview.power),
            powerColor(preview.power),
            it,
            12.sp,
        )
    }
    label("瞄准线（白球→假想球）", AimLine, listOf(aim.cuePosition, aim.ghostBall))
    val target = aim.ghostBall + aim.objectDirection * (2 * t.table.ballRadius)
    label(
        if (aim.isPottable) "目标球→袋口" else "目标球路线（进不了）",
        if (aim.isPottable) ObjectLineOk else ObjectLineBad,
        listOf(target, aim.objectPathEnd),
    )
    label("假想球", AimLine, listOf(aim.ghostBall), 10.sp)
    aim.stunDirection?.let { dir ->
        label("定杆分离线 90°", StunLine, listOf(aim.ghostBall, aim.ghostBall + dir * STUN_LABEL_MM), 10.sp)
    }
    current?.paths?.forEach { (number, path) ->
        // 目标球已有"目标球→袋口"标签
        if (number != 0 && number != aim.targetNumber) label("${number}号球被碰动", Color.White.copy(alpha = 0.8f), path, 10.sp)
    }
    preview?.references?.forEachIndexed { index, result ->
        result.paths[0]?.lastOrNull()?.let { label("${index + 1}档", powerColor(index + 1), listOf(it), 10.sp) }
    }
}

private const val STUN_LABEL_MM = 350.0

/** 贪心放置：沿线取若干锚点，每个锚点试上下左右四个位置，取第一个不重叠的。 */
private class LabelPlacer(private val bounds: Rect, obstacles: List<Rect>) {
    private val taken = obstacles.toMutableList()

    fun place(points: List<Offset>, box: Size): Rect? {
        for (anchor in anchors(points)) {
            for (candidate in candidates(anchor, box)) {
                if (candidate.left < bounds.left || candidate.top < bounds.top ||
                    candidate.right > bounds.right || candidate.bottom > bounds.bottom
                ) continue
                if (taken.any { it.overlaps(candidate) }) continue
                taken += candidate
                return candidate
            }
        }
        return null
    }

    private fun candidates(a: Offset, box: Size): List<Rect> {
        val gap = 6f
        return listOf(
            Offset(a.x - box.width / 2, a.y - box.height - gap),
            Offset(a.x - box.width / 2, a.y + gap),
            Offset(a.x + gap, a.y - box.height / 2),
            Offset(a.x - box.width - gap, a.y - box.height / 2),
        ).map { Rect(it, box) }
    }

    /** 沿折线按长度比例取点，中间优先；单点就是它本身。 */
    private fun anchors(points: List<Offset>): List<Offset> {
        if (points.size < 2) return points
        val lengths = points.zipWithNext { a, b -> (b - a).getDistance() }
        val total = lengths.sum()
        if (total < 1f) return listOf(points.first())
        return FRACTIONS.map { f ->
            var remaining = f * total
            var i = 0
            while (i < lengths.size - 1 && remaining > lengths[i]) {
                remaining -= lengths[i]
                i++
            }
            val k = if (lengths[i] > 0f) (remaining / lengths[i]).coerceIn(0f, 1f) else 0f
            points[i] + (points[i + 1] - points[i]) * k
        }
    }

    companion object {
        val FRACTIONS = listOf(0.5f, 0.35f, 0.65f, 0.2f, 0.8f, 0.1f, 0.9f, 0.03f, 0.97f)
    }
}

/** 台面上一条可点击的线：[points] 为台面毫米坐标（单点表示一个圆圈标记），[title] 同时作为选中时的键。 */
internal class LineInfo(val title: String, val detail: String, val color: Color, val points: List<Vec2>)

/** 当前台面上所有可点击的线和它们的详细说明，顺序即优先级。 */
internal fun describeLines(aim: AimResult, preview: ShotPreview?, ballRadius: Double): List<LineInfo> {
    val lines = mutableListOf<LineInfo>()
    preview?.let { p ->
        p.current.paths[0]?.let {
            lines += LineInfo(
                "白球走位",
                String.format(
                    Locale.US,
                    "击点 %s、力度 %.1f（%.2f m/s）时白球的模拟路线。碰球后的折线是吃库反弹，末端白圈是白球停点。改击点或力度，这条线会跟着变。",
                    hitPointName(p.hitPoint), p.power, powerSpeed(p.power) / 1000,
                ),
                powerColor(p.power),
                it,
            )
        }
    }
    lines += LineInfo(
        "瞄准线",
        "白球 → 假想球。沿这条线出杆，白球会在白圈（假想球）的位置撞上目标球。",
        AimLine,
        listOf(aim.cuePosition, aim.ghostBall),
    )
    val target = aim.ghostBall + aim.objectDirection * (2 * ballRadius)
    lines += LineInfo(
        if (aim.isPottable) "目标球路线" else "目标球路线（进不了）",
        String.format(
            Locale.US,
            "目标球被撞后沿这条线滚向袋口。切角 %.1f°，容错 ±%.2f°。%s",
            aim.cutAngleDeg, aim.marginDeg,
            if (aim.isPottable) "黄色表示能进。" else "红色虚线表示被挡住或角度不够，具体原因见右侧瞄准分析。",
        ),
        if (aim.isPottable) ObjectLineOk else ObjectLineBad,
        listOf(target, aim.objectPathEnd),
    )
    lines += LineInfo(
        "假想球",
        "碰撞瞬间白球球心所在的位置。瞄准时想象把白球打到这个白圈上。",
        AimLine,
        listOf(aim.ghostBall),
    )
    aim.stunDirection?.let { dir ->
        lines += LineInfo(
            "定杆分离线 90°",
            "90° 法则：用定杆（碰球瞬间白球不转）击球时，白球碰后沿这条与目标球路线垂直的方向离开。加高杆会往目标球方向偏，加低杆往反方向偏。",
            StunLine,
            listOf(aim.ghostBall, aim.ghostBall + dir * STUN_LINE_LENGTH_MM),
        )
    }
    preview?.current?.paths?.forEach { (number, path) ->
        if (number != 0 && number != aim.targetNumber) {
            lines += LineInfo("${number}号球被碰动", "${number} 号球被白球或目标球碰到后的模拟路线（白色细虚线）。", Color.White, path)
        }
    }
    preview?.references?.forEachIndexed { index, result ->
        val level = index + 1
        result.paths[0]?.let {
            lines += LineInfo(
                "${level}档参考",
                String.format(Locale.US, "同一击点下第 %d 档参考力度（%.2f m/s）的白球路线，线尾小圆点是停点，用来对比力度大小的影响。", level, PowerLevels.speed(level) / 1000),
                powerColor(level),
                it,
            )
        }
    }
    return lines
}

/** 与 TableView 里定杆切线的长度一致。 */
private const val STUN_LINE_LENGTH_MM = 500.0

/** 离 [tap] 最近、且在 [slopPx] 像素以内的线。 */
internal fun nearestLine(lines: List<LineInfo>, tap: Offset, t: TableTransform, slopPx: Float): LineInfo? =
    lines
        .map { it to distanceToPolyline(it.points.map { p -> t.toScreen(p) }, tap) }
        .filter { it.second <= slopPx }
        .minByOrNull { it.second }
        ?.first

private fun distanceToPolyline(points: List<Offset>, p: Offset): Float {
    if (points.size == 1) return (points[0] - p).getDistance()
    return points.zipWithNext { a, b ->
        val ab = b - a
        val len2 = ab.x * ab.x + ab.y * ab.y
        val k = if (len2 == 0f) 0f else (((p - a).x * ab.x + (p - a).y * ab.y) / len2).coerceIn(0f, 1f)
        (a + ab * k - p).getDistance()
    }.min()
}

/** 选中的线：加一层白色光晕高亮。 */
internal fun DrawScope.drawLineHighlight(t: TableTransform, line: LineInfo) {
    val pts = line.points.map { t.toScreen(it) }
    if (pts.size == 1) {
        drawCircle(Color.White.copy(alpha = 0.5f), radius = t.toScreen(t.table.ballRadius) * 1.4f, center = pts[0], style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
        return
    }
    pts.zipWithNext { a, b ->
        drawLine(Color.White.copy(alpha = 0.35f), a, b, strokeWidth = 12f, cap = StrokeCap.Round)
        drawLine(line.color, a, b, strokeWidth = 5f, cap = StrokeCap.Round)
    }
}

/** 说明卡片：放在点击位置旁边，超出画布时往回挪。 */
internal fun DrawScope.drawLineCard(line: LineInfo, at: Offset, m: TextMeasurer) {
    val maxWidth = (size.width * 0.38f).toInt()
    val title = m.measure(line.title, TextStyle(color = line.color, fontSize = 14.sp, fontWeight = FontWeight.Bold), constraints = Constraints(maxWidth = maxWidth))
    val body = m.measure(line.detail, TextStyle(color = Color.White, fontSize = 12.sp, lineHeight = 16.sp), constraints = Constraints(maxWidth = maxWidth))
    val hint = m.measure("点空白处关闭", TextStyle(color = Muted, fontSize = 10.sp))
    val pad = 14f
    val w = maxOf(title.size.width, body.size.width, hint.size.width) + pad * 2
    val h = title.size.height + body.size.height + hint.size.height + pad * 2 + 12f
    var x = at.x + 16f
    var y = at.y + 16f
    if (x + w > size.width) x = at.x - 16f - w
    if (y + h > size.height) y = size.height - h - 4f
    x = x.coerceAtLeast(4f)
    y = y.coerceAtLeast(4f)
    drawRoundRect(Color(0xF0202020), Offset(x, y), Size(w, h), CornerRadius(14f))
    drawRoundRect(line.color, Offset(x, y), Size(w, h), CornerRadius(14f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
    drawText(title, topLeft = Offset(x + pad, y + pad))
    drawText(body, topLeft = Offset(x + pad, y + pad + title.size.height + 6f))
    drawText(hint, topLeft = Offset(x + pad, y + pad + title.size.height + body.size.height + 12f))
}
