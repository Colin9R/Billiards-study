package com.zhongba.billiards.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhongba.engine.AimAnalyzer
import com.zhongba.engine.AimProblem
import com.zhongba.engine.AimResult
import com.zhongba.engine.fullnessLabel
import java.util.Locale

/** 面板的瞄准部分：目标球、袋口、切角、厚薄、容错和能否进球。 */
@Composable
fun AimSection(aim: AimResult?, autoPocket: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("瞄准分析", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (aim == null) {
            Text("点一颗彩球作为目标球", color = Muted, fontSize = 13.sp)
            return@Column
        }

        InfoRow("目标", "${aim.targetNumber} 号球")
        InfoRow("袋口", aim.pocket.name + if (autoPocket) "（自动）" else "")
        InfoRow("切角", String.format(Locale.US, "%.1f°", aim.cutAngleDeg))
        InfoRow(
            "厚薄",
            if (aim.cutAngleDeg > AimAnalyzer.MAX_CUT_DEG) "—"
            else "${fullnessLabel(aim.fullness)}（${(aim.fullness * 100).toInt()}%）",
        )
        InfoRow("容错", if (aim.marginDeg > 0) String.format(Locale.US, "±%.2f°", aim.marginDeg) else "—")
        if (aim.isPottable) {
            Text("✓ 可以进", color = Color(0xFF69F0AE), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        } else {
            aim.problems.forEach {
                Text("✗ ${it.describe()}", color = Color(0xFFFF8A80), fontSize = 14.sp)
            }
        }
    }
}

internal val Muted = Color(0xFFAAAAAA)

@Composable
internal fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 14.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun AimProblem.describe(): String = when (this) {
    is AimProblem.CuePathBlocked -> "白球路线被 $number 号球挡住"
    is AimProblem.ObjectPathBlocked -> "目标球路线被 $number 号球挡住"
    AimProblem.CutTooThin -> "切角太大，打不到"
    AimProblem.PocketNotReachable -> "角度太斜，进不了这个袋"
}
