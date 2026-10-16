package com.zhongba.billiards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhongba.engine.RunOutPlanner
import com.zhongba.engine.fullnessLabel
import java.util.Locale
import kotlin.math.abs

/** 清台规划的计算状态：[plan] 为空表示还在算，[progress] 为 0–1。 */
class RunOutState(val plan: RunOutPlanner.Plan?, val progress: Float)

/** 两个并排的切换按钮，用于"分析/清台"和"全色/花色"。 */
@Composable
fun SegmentedToggle(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .background(if (active) Color(0xFF3D5AFE) else Color(0xFF333333), RoundedCornerShape(6.dp))
                    .border(1.dp, if (active) Color(0xFF8C9EFF) else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Color.White, fontSize = 13.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** 清台模式的面板：我方球组、规划结果（每一杆的顺序与杆法）、执行与撤销。 */
@Composable
fun RunOutSection(
    group: RunOutPlanner.Group,
    onGroupChange: (RunOutPlanner.Group) -> Unit,
    state: RunOutState,
    alternatives: List<RunOutPlanner.Step>,
    previewAlternative: RunOutPlanner.Step?,
    onPreviewAlternative: (RunOutPlanner.Step) -> Unit,
    onChooseAlternative: (RunOutPlanner.Step) -> Unit,
    selected: Int,
    onSelect: (Int) -> Unit,
    onPlayFirst: () -> Unit,
    onShowDetail: (Int) -> Unit,
    onReset: () -> Unit,
    onBreak: () -> Unit,
    lastShot: RunOutPlanner.Step?,
    shotMessage: String?,
    canUndo: Boolean,
    onUndo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SegmentedToggle(
            RunOutPlanner.Group.entries.map { it.label },
            RunOutPlanner.Group.entries.indexOf(group),
            { onGroupChange(RunOutPlanner.Group.entries[it]) },
            Modifier.fillMaxWidth(),
        )

        val plan = state.plan
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionText("开球", onBreak)
            if (plan != null && plan.steps.isNotEmpty()) {
                ActionText("打第 1 杆", onPlayFirst)
                ActionText("详情") { onShowDetail(selected) }
            }
            if (canUndo) ActionText("撤销", onUndo)
            ActionText("重摆", onReset)
        }
        when {
            shotMessage != null -> Text(shotMessage, color = Color(0xFFFFB74D), fontSize = 13.sp)
            lastShot != null -> Text(
                String.format(Locale.US, "刚打 %d 号 → %s · %s %d 档", lastShot.target, lastShot.aim.pocket.name, lastShot.hitPoint.label, lastShot.power),
                color = Muted,
                fontSize = 12.sp,
            )
        }

        when {
            plan == null -> LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            plan.steps.isEmpty() -> Text(
                if (plan.cleared) "已清台" else "✗ 没有能打进的球",
                color = if (plan.cleared) Color(0xFF69F0AE) else Color(0xFFFF8A80),
                fontSize = 13.sp,
            )
            else -> {
                Text(
                    if (plan.cleared) "✓ 可清台 · ${plan.steps.size} 杆" else "✗ 清不完 · 最多 ${plan.steps.size} 杆",
                    color = if (plan.cleared) Color(0xFF69F0AE) else Color(0xFFFF8A80),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                plan.steps.forEachIndexed { i, step ->
                    StepRow(i, step, plan.steps.getOrNull(i + 1)?.target, i == selected) { onSelect(i) }
                    if (i == selected) {
                        AlternativeList(step, alternatives, previewAlternative, onPreviewAlternative, onChooseAlternative)
                    }
                }
            }
        }

    }
}

/**
 * 一杆的完整说明：目标球与袋口；杆法（击打点）、力度；切角、厚薄、带偏修正；打完白球去打哪一颗。
 */
@Composable
private fun StepRow(index: Int, step: RunOutPlanner.Step, nextTarget: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Color(0xFF34374A) else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(20.dp).background(stepColor(index), CircleShape), contentAlignment = Alignment.Center) {
            Text("${index + 1}", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("${step.target} 号 → ${step.aim.pocket.name}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${step.hitPoint.label} · ${step.power} 档", color = Color(0xFF8C9EFF), fontSize = 12.sp)
                val (label, color) = toleranceLabel(step.tolerance)
                Text("容错$label", color = color, fontSize = 11.sp)
            }
            val details = buildString {
                append(String.format(Locale.US, "切角 %.0f° · %s", step.aim.cutAngleDeg, fullnessLabel(step.aim.fullness)))
                if (abs(step.throwCorrectionDeg) >= 0.3) append(String.format(Locale.US, " · 多切 %.1f°", abs(step.throwCorrectionDeg)))
            }
            Text(details, color = Muted, fontSize = 11.sp)
            Text(if (nextTarget == null) "收杆" else "走位 → 打 $nextTarget 号", color = Muted, fontSize = 11.sp)
        }
    }
}

/**
 * 选中那一杆的其他打法：点一下在台面上预览白球走位（白色粗虚线），再点"改用"从这一杆往后重新规划。
 */
@Composable
private fun AlternativeList(
    current: RunOutPlanner.Step,
    options: List<RunOutPlanner.Step>,
    preview: RunOutPlanner.Step?,
    onPreview: (RunOutPlanner.Step) -> Unit,
    onChoose: (RunOutPlanner.Step) -> Unit,
) {
    val others = options.filter { !it.sameShotAs(current) }
    if (others.isEmpty()) return
    Column(Modifier.padding(start = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("其他打法", color = Muted, fontSize = 11.sp)
        others.forEach { option ->
            val previewing = option === preview
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (previewing) Color(0xFF3A3A50) else Color(0xFF2E2E2E), RoundedCornerShape(6.dp))
                    .clickable { onPreview(option) }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${option.aim.pocket.name} · ${option.hitPoint.label} ${option.power} 档", color = Color.White, fontSize = 12.sp)
                    val (tol, tolColor) = toleranceLabel(option.tolerance)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("容错$tol", color = tolColor, fontSize = 11.sp)
                        Text("走位${leaveLabel(option.leave)}", color = Muted, fontSize = 11.sp)
                        Text(String.format(Locale.US, "切角 %.0f°", option.aim.cutAngleDeg), color = Muted, fontSize = 11.sp)
                    }
                }
                if (previewing) ActionText("改用") { onChoose(option) }
            }
        }
    }
}

private fun RunOutPlanner.Step.sameShotAs(other: RunOutPlanner.Step) =
    aim.pocketIndex == other.aim.pocketIndex && hitPoint == other.hitPoint && power == other.power

private fun leaveLabel(leave: Double) = when {
    leave >= 0.6 -> "好"
    leave >= 0.3 -> "一般"
    else -> "差"
}

/** 力度容错等级：大一档小一档力度也能进、走位不坏为"高"。 */
private fun toleranceLabel(tolerance: Double): Pair<String, Color> = when {
    tolerance >= 0.75 -> "高" to Color(0xFF69F0AE)
    tolerance >= 0.4 -> "中" to Color(0xFFFFD54F)
    else -> "低" to Color(0xFFFF8A80)
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Text(label, color = Color(0xFF8C9EFF), fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(onClick = onClick))
}
