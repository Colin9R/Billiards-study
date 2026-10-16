package com.zhongba.billiards.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.zhongba.engine.HitPoint
import kotlin.math.abs
import kotlin.math.hypot
import com.zhongba.engine.Stroke as StrokePreset

/**
 * 球体原型式击打点选择：画一个白球正面，点击或拖动杆头圆点选择击打位置。
 * 上下 = 高低杆，左右 = 左右塞；球面上标出方向和五档高低杆预设（靠近时自动吸附），
 * 虚线圈是防滑杆的范围。
 */
@Composable
fun HitPointPicker(hitPoint: HitPoint, onChange: (HitPoint) -> Unit, modifier: Modifier = Modifier) {
    val currentOnChange by rememberUpdatedState(onChange)
    val textMeasurer = rememberTextMeasurer()

    fun pick(pos: Offset, width: Float, height: Float) {
        val radius = ballRadius(minOf(width, height))
        val center = Offset(width / 2, height / 2)
        // 屏幕 y 向下，击打点 y 向上为高杆
        currentOnChange(snap(HitPoint(((pos.x - center.x) / radius).toDouble(), ((center.y - pos.y) / radius).toDouble())).clampedTo(PICK_MAX))
    }

    Canvas(
        modifier
            .pointerInput(Unit) {
                // 只接管按在球上的手势（点、拖都在这里），球外的滑动不消费，留给面板滚动
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    if (!onBall(down.position, w, h)) return@awaitEachGesture
                    down.consume()
                    pick(down.position, w, h)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        pick(change.position, w, h)
                    }
                }
            },
    ) {
        val radius = ballRadius(size.minDimension)
        val c = center

        // 球体：径向渐变 + 阴影做出立体感
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = radius, center = c + Offset(radius * 0.05f, radius * 0.07f))
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White, Color(0xFFE8E3D3), Color(0xFFB9B3A2)),
                center = c - Offset(radius * 0.35f, radius * 0.35f),
                radius = radius * 1.6f,
            ),
            radius = radius,
            center = c,
        )
        // 0.6R 以外到 0.85R：能打出极限高低杆/大塞，但容易滑杆，涂成淡红
        drawCircle(
            Color(0xFFE53935).copy(alpha = 0.16f),
            radius = radius * PICK_MAX.toFloat(),
            center = c,
        )
        drawCircle(Color(0xFFF2EEE2).copy(alpha = 0.75f), radius = radius * HitPoint.MAX_RADIUS.toFloat(), center = c)
        drawLabel(textMeasurer, "易滑杆", Offset(c.x - radius * 0.5f, c.y + radius * 0.62f), Color(0xFFC62828), 8, Align.Below)
        val grid = Color(0xFF9E9E9E)
        drawLine(grid, Offset(c.x - radius, c.y), Offset(c.x + radius, c.y), strokeWidth = 1.5f)
        drawLine(grid, Offset(c.x, c.y - radius), Offset(c.x, c.y + radius), strokeWidth = 1.5f)
        drawCircle(
            Color(0xFF757575),
            radius = radius * HitPoint.MAX_RADIUS.toFloat(),
            center = c,
            style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
        )

        // 五档高低杆预设：竖轴上的刻度 + 名字
        StrokePreset.entries.forEach { s ->
            val y = c.y - s.tipOffset.toFloat() * radius
            val active = abs(hitPoint.y - s.tipOffset) < 0.01
            val tick = if (active) Color(0xFF1E88E5) else Color(0xFF616161)
            drawLine(tick, Offset(c.x - radius * 0.07f, y), Offset(c.x + radius * 0.07f, y), strokeWidth = if (active) 3f else 2f)
            drawLabel(textMeasurer, s.label, Offset(c.x + radius * 0.11f, y), tick, 9, Align.Right)
        }

        // 球外四个方向
        val dir = Color(0xFFDDDDDD)
        drawLabel(textMeasurer, "高杆↑", Offset(c.x, c.y - radius - LABEL_GAP), dir, 11, Align.Above)
        drawLabel(textMeasurer, "低杆↓", Offset(c.x, c.y + radius + LABEL_GAP), dir, 11, Align.Below)
        drawLabel(textMeasurer, "←左塞", Offset(c.x - radius - LABEL_GAP, c.y), dir, 11, Align.Left)
        drawLabel(textMeasurer, "右塞→", Offset(c.x + radius + LABEL_GAP, c.y), dir, 11, Align.Right)

        // 杆头：实际皮头约占球半径的 1/4，画成半透明圆，中心红点为击点
        val dot = Offset(c.x + hitPoint.x.toFloat() * radius, c.y - hitPoint.y.toFloat() * radius)
        drawCircle(Color(0xFF1565C0).copy(alpha = 0.28f), radius = radius * TIP_RADIUS, center = dot)
        drawCircle(Color(0xFF1565C0), radius = radius * TIP_RADIUS, center = dot, style = Stroke(width = 2f))
        drawCircle(Color(0xFFE53935), radius = radius * 0.07f, center = dot)
    }
}

private fun onBall(pos: Offset, width: Float, height: Float): Boolean =
    (pos - Offset(width / 2, height / 2)).getDistance() <= ballRadius(minOf(width, height)) * 1.1f

/** 留出四周写方向文字的空间后，球的半径。 */
private fun ballRadius(minDimension: Float) = minDimension / 2 * 0.64f

private const val LABEL_GAP = 6f
private const val TIP_RADIUS = 0.24f
private const val SNAP = 0.05

/** 可选击点的最大半径：超过安全圈 [HitPoint.MAX_RADIUS]，用来体验极限低杆/高杆。 */
private const val PICK_MAX = 0.85

private fun HitPoint.clampedTo(max: Double): HitPoint {
    val len = hypot(x, y)
    return if (len <= max) this else HitPoint(x / len * max, y / len * max)
}

/** 击点叫法：超出安全圈的加"极限"二字，如"极限低杆"。 */
internal fun hitPointName(p: HitPoint): String =
    if (hypot(p.x, p.y) > HitPoint.MAX_RADIUS + 1e-6) "极限" + p.label else p.label

/** 靠近竖轴上的预设档位时吸附过去，方便点准"中杆""高杆"等。 */
private fun snap(p: HitPoint): HitPoint {
    val x = if (abs(p.x) < SNAP) 0.0 else p.x
    val preset = StrokePreset.entries.minByOrNull { abs(it.tipOffset - p.y) }!!
    val y = if (abs(preset.tipOffset - p.y) < SNAP) preset.tipOffset else p.y
    return HitPoint(x, y)
}

/** 文字相对锚点 [at] 的位置：在其上方、下方、左侧或右侧。 */
private enum class Align { Above, Below, Left, Right }

private fun DrawScope.drawLabel(m: TextMeasurer, text: String, at: Offset, color: Color, sizeSp: Int, align: Align) {
    val layout = m.measure(text, TextStyle(color = color, fontSize = sizeSp.sp, fontWeight = FontWeight.Medium))
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    val topLeft = when (align) {
        Align.Above -> Offset(at.x - w / 2, at.y - h)
        Align.Below -> Offset(at.x - w / 2, at.y)
        Align.Left -> Offset(at.x - w, at.y - h / 2)
        Align.Right -> Offset(at.x, at.y - h / 2)
    }
    drawText(layout, topLeft = topLeft)
}
