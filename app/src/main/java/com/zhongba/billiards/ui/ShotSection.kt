package com.zhongba.billiards.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhongba.engine.PowerLevels
import com.zhongba.engine.SimulationResult
import com.zhongba.engine.HitPoint
import java.util.Locale
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * 走位预览：[current] 是当前击点 + 无极力度 [power] 的模拟结果；
 * [references] 是打开"叠加全部力度"时 1–8 参考档的结果（下标 = 档位 - 1），关闭时为空。
 */
class ShotPreview(
    val hitPoint: HitPoint,
    val power: Float,
    val current: SimulationResult,
    val references: List<SimulationResult> = emptyList(),
)

/** 无极力度 1.0–8.0 对应的白球初速度：在 8 个参考档之间线性插值，毫米/秒。 */
fun powerSpeed(power: Float): Double {
    val p = power.coerceIn(1f, PowerLevels.count.toFloat())
    val lo = p.toInt().coerceAtMost(PowerLevels.count - 1)
    val k = (p - lo).toDouble()
    return PowerLevels.speed(lo) * (1 - k) + PowerLevels.speed(lo + 1) * k
}

/** 力度档位的颜色：小力度偏蓝，大力度偏红。 */
fun powerColor(level: Int): Color = powerColor(level.toFloat())

fun powerColor(power: Float): Color =
    lerp(Color(0xFF80D8FF), Color(0xFFFF5252), ((power - 1f) / (PowerLevels.count - 1)).coerceIn(0f, 1f))

/** 面板的击球部分：击打点、无极力度、是否叠加参考档，以及当前这一杆的结果。 */
@Composable
fun ShotSection(
    hitPoint: HitPoint,
    onHitPointChange: (HitPoint) -> Unit,
    power: Float,
    onPowerChange: (Float) -> Unit,
    showAllPowers: Boolean,
    onShowAllPowersChange: (Boolean) -> Unit,
    targetNumber: Int?,
    result: SimulationResult?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("击打点与力度", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)

        // 左：白球击点；右：竖直力度条（上 = 大力）
        Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HitPointPicker(hitPoint, onHitPointChange, Modifier.weight(1f).fillMaxHeight())
            PowerBar(power, onPowerChange, Modifier.width(44.dp).fillMaxHeight())
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("击点：${hitPointName(hitPoint)}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    String.format(Locale.US, "上下 %+.2f  左右 %+.2f", hitPoint.y, hitPoint.x),
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            Text(
                "回中",
                color = Color(0xFF8C9EFF),
                fontSize = 13.sp,
                modifier = Modifier.clickable { onHitPointChange(HitPoint()) },
            )
        }
        Text("在白球上点或拖动蓝色杆头圈选择击点；靠近刻度会吸附到高杆/中杆/低杆等档位；淡红区是极限击点，实战容易滑杆。", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)

        InfoRow("力度", String.format(Locale.US, "%.1f（%.2f m/s）", power, powerSpeed(power) / 1000))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("叠加全部力度", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = showAllPowers, onCheckedChange = onShowAllPowersChange)
        }
        if (showAllPowers) {
            Text("细线是 1–8 参考档，蓝到红 = 小到大；粗线为当前力度", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
        }

        if (result != null && targetNumber != null) {
            ShotOutcome(targetNumber, result)
        }
    }
}

@Composable
private fun ShotOutcome(targetNumber: Int, result: SimulationResult) {
    val good = Color(0xFF69F0AE)
    val bad = Color(0xFFFF8A80)
    val first = result.firstContact
    when {
        first == null -> Text("✗ 白球没碰到球", color = bad, fontSize = 14.sp)
        first != targetNumber -> Text("✗ 白球先碰到 $first 号球", color = bad, fontSize = 14.sp)
        targetNumber in result.pocketed -> Text("✓ 目标球进袋", color = good, fontSize = 14.sp)
        else -> Text("✗ 目标球没进", color = bad, fontSize = 14.sp)
    }
    val others = result.pocketed.filter { it != 0 && it != targetNumber }
    if (others.isNotEmpty()) Text("连带进袋：${others.joinToString("、")} 号", color = Muted, fontSize = 13.sp)
    if (0 in result.pocketed) {
        Text("✗ 白球落袋", color = bad, fontSize = 14.sp)
    } else {
        Text("白球停点见台面上的白圈", color = Muted, fontSize = 13.sp)
    }
}

/** 竖直无极力度条：从下到上 1.0–8.0，颜色同走位线；点或上下拖动连续调节，旁边刻度是 8 个参考档。 */
@Composable
private fun PowerBar(power: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val currentOnChange by rememberUpdatedState(onChange)
    val textMeasurer = rememberTextMeasurer()
    val max = PowerLevels.count.toFloat()

    fun pick(y: Float, height: Float) {
        val p = 1f + (1f - (y / height).coerceIn(0f, 1f)) * (max - 1f)
        currentOnChange((p * 10).roundToInt() / 10f)
    }

    Canvas(
        modifier
            .pointerInput(Unit) { detectTapGestures { pick(it.y, size.height.toFloat()) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    pick(change.position.y, size.height.toFloat())
                }
            },
    ) {
        val barWidth = size.width * 0.5f
        fun yOf(p: Float) = size.height * (1f - (p - 1f) / (max - 1f))
        // 槽
        drawRoundRect(Color(0xFF3A3A3A), Offset.Zero, Size(barWidth, size.height), CornerRadius(8f))
        // 已填充部分：蓝→红渐变
        val fillTop = yOf(power)
        drawRoundRect(
            Brush.verticalGradient(listOf(powerColor(max), powerColor(1f)), startY = 0f, endY = size.height),
            topLeft = Offset(0f, fillTop),
            size = Size(barWidth, size.height - fillTop),
            cornerRadius = CornerRadius(8f),
        )
        // 参考刻度 1–8
        for (level in 1..PowerLevels.count) {
            val y = yOf(level.toFloat())
            drawLine(Muted, Offset(barWidth + 2f, y), Offset(barWidth + 10f, y), strokeWidth = 2f)
            val label = textMeasurer.measure("$level", TextStyle(color = Muted, fontSize = 9.sp))
            val ly = (y - label.size.height / 2f).coerceIn(0f, size.height - label.size.height)
            drawText(label, topLeft = Offset(barWidth + 12f, ly))
        }
        // 当前位置：白色横条
        drawRoundRect(Color.White, Offset(-2f, fillTop - 4f), Size(barWidth + 4f, 8f), CornerRadius(4f))
    }
}
