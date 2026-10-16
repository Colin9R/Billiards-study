package com.zhongba.billiards.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zhongba.engine.BallLayout
import com.zhongba.engine.RunOutPlanner

/**
 * 台面上要显示的清台路线，[selected] 为当前选中的那一杆（下标）；
 * [lastShot] 为刚模拟打完的那一杆，用白色虚线画出它的白球走位。
 */
class RunOutView(
    val plan: RunOutPlanner.Plan,
    val selected: Int,
    val lastShot: RunOutPlanner.Step? = null,
    /** 正在预览的备选打法：用白色粗虚线画出它的白球走位和目标球路线。 */
    val alternative: RunOutPlanner.Step? = null,
)

private val StepColors = listOf(
    Color(0xFF40C4FF), Color(0xFFFFD740), Color(0xFFFF6E40), Color(0xFF69F0AE),
    Color(0xFFE040FB), Color(0xFFFFAB40), Color(0xFF18FFFF), Color(0xFFFF4081),
)

fun stepColor(index: Int): Color = StepColors[index % StepColors.size]

/** 画在球下面：每一杆白球的走位线；选中的那一杆加粗，并画出目标球进袋路线。 */
internal fun DrawScope.drawRunOutPaths(t: TableTransform, view: RunOutView) {
    view.lastShot?.result?.paths?.get(0)?.let {
        drawPolyline(t, it, Color.White.copy(alpha = 0.75f), 3f, PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
    }
    view.plan.steps.forEachIndexed { i, step ->
        val color = stepColor(i)
        val selected = i == view.selected
        step.result.paths[0]?.let { drawPolyline(t, it, color.copy(alpha = if (selected) 1f else 0.4f), if (selected) 5f else 2f) }
        if (selected) {
            step.result.paths[step.target]?.let {
                drawPolyline(t, it, Color.White.copy(alpha = 0.8f), 2.5f, PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            }
        }
    }
    view.alternative?.let { alt ->
        alt.result.paths[0]?.let { drawPolyline(t, it, Color.White, 4f, PathEffect.dashPathEffect(floatArrayOf(16f, 8f))) }
        alt.result.paths[alt.target]?.let { drawPolyline(t, it, Color.White.copy(alpha = 0.6f), 2f, PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
        alt.cueStop?.let { drawCircle(Color.White, radius = t.toScreen(t.table.ballRadius), center = t.toScreen(it), style = Stroke(width = 3f)) }
    }
}

/**
 * 画在球上面：目标球旁的击球顺序编号、白球每一杆的停点编号。
 * 编号贴在台面上现在的球旁边（[layout]），即使模拟中这颗球会被前面的杆碰动。
 */
internal fun DrawScope.drawRunOutBadges(t: TableTransform, view: RunOutView, layout: BallLayout, textMeasurer: TextMeasurer) {
    val r = t.toScreen(t.table.ballRadius)
    view.plan.steps.forEachIndexed { i, step ->
        val color = stepColor(i)
        val label = "${i + 1}"
        layout.balls.firstOrNull { it.number == step.target }?.let { ball ->
            val c = t.toScreen(ball.position) + Offset(r * 0.95f, -r * 0.95f)
            drawCircle(color, radius = r * 0.62f, center = c)
            drawCenteredText(textMeasurer, label, c, Color.Black, r * 0.75f)
        }
        step.cueStop?.let { stop ->
            val c = t.toScreen(stop)
            val selected = i == view.selected
            drawCircle(Color.White.copy(alpha = if (selected) 0.45f else 0.2f), radius = r, center = c)
            drawCircle(color, radius = r, center = c, style = Stroke(width = if (selected) 3.5f else 2f))
            drawCenteredText(textMeasurer, label, c, Color.White, r * 0.8f)
        }
    }
}

private fun DrawScope.drawCenteredText(m: TextMeasurer, text: String, center: Offset, color: Color, heightPx: Float) {
    val layout = m.measure(text, TextStyle(color = color, fontSize = (heightPx / density).sp, fontWeight = FontWeight.Bold))
    drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
}
