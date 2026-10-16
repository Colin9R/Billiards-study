package com.zhongba.billiards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhongba.engine.HitPoint
import com.zhongba.engine.PositionPlanner
import com.zhongba.engine.PowerLevels
import com.zhongba.engine.Stroke
import java.util.Locale
import kotlin.math.abs

/** 走位评分的颜色：0 偏黄（能打但难），1 偏绿（很舒服）。 */
fun scoreColor(score: Double): Color =
    lerp(Color(0xFFFFB300), Color(0xFF00C853), score.toFloat().coerceIn(0f, 1f))

private val FailColor = Color(0xFF4A2626)

/** 面板的走位部分：下一颗球、热区开关、杆法 × 力度走位表。 */
@Composable
fun PositionSection(
    nextNumber: Int?,
    onClearNext: () -> Unit,
    showHeat: Boolean,
    onShowHeatChange: (Boolean) -> Unit,
    options: List<PositionPlanner.Option>?,
    hitPoint: HitPoint,
    power: Float,
    onPick: (Stroke, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("走位热区", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (nextNumber == null) {
            Text("长按台面上的一颗彩球，把它设为下一颗要打的球。", color = Muted, fontSize = 13.sp, lineHeight = 17.sp)
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("下一颗：$nextNumber 号", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text("清除", color = Color(0xFF8C9EFF), fontSize = 13.sp, modifier = Modifier.clickable(onClick = onClearNext))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("显示热区", color = Muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Switch(checked = showHeat, onCheckedChange = onShowHeatChange)
        }
        if (!showHeat) return@Column

        if (options == null) {
            Text("计算中…", color = Muted, fontSize = 13.sp)
            return@Column
        }

        val best = options.filter { it.success }.maxByOrNull { it.score }
        if (best == null) {
            Text("✗ 这颗目标球在任何杆法和力度下都打不成", color = Color(0xFFFF8A80), fontSize = 13.sp)
        } else {
            Text(
                String.format(Locale.US, "推荐：%s %d 档（走位评分 %.0f）", best.stroke.label, best.power, best.score * 100),
                color = Color(0xFF69F0AE),
                fontSize = 13.sp,
            )
        }

        PositionMatrix(options, hitPoint, power, onPick)
        Text("行 = 高低杆，列 = 力度；左右塞按当前击打点计算。绿 = 下一颗好打，黄 = 能打但难，暗红 = 这一杆就失败。点格子切换。", color = Muted, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** 行 = 杆法，列 = 力度 1–8 档。 */
@Composable
private fun PositionMatrix(options: List<PositionPlanner.Option>, hitPoint: HitPoint, power: Float, onPick: (Stroke, Int) -> Unit) {
    val byKey = options.associateBy { it.stroke to it.power }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(Modifier.width(LabelWidth))
            (1..PowerLevels.count).forEach { level ->
                Text("$level", color = Muted, fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            }
        }
        Stroke.entries.forEach { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.label, color = Muted, fontSize = 11.sp, modifier = Modifier.width(LabelWidth))
                (1..PowerLevels.count).forEach { level ->
                    val option = byKey[s to level]
                    val color = when {
                        option == null -> Color.DarkGray
                        !option.success -> FailColor
                        else -> scoreColor(option.score).copy(alpha = 0.35f + 0.65f * option.score.toFloat())
                    }
                    val selected = abs(s.tipOffset - hitPoint.y) < 0.01 && abs(level - power) < 0.05f
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .background(color, RoundedCornerShape(3.dp))
                            .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(3.dp))
                            .clickable { onPick(s, level) },
                    )
                }
            }
        }
    }
}

private val LabelWidth = 40.dp
