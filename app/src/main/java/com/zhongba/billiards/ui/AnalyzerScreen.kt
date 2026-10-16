package com.zhongba.billiards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhongba.engine.AimAnalyzer
import com.zhongba.engine.BallLayout
import com.zhongba.engine.HitPoint
import com.zhongba.engine.PositionPlanner
import com.zhongba.engine.PowerLevels
import com.zhongba.engine.Shot
import com.zhongba.engine.SimulationResult
import com.zhongba.engine.Simulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 主界面：左边台面，右边分析面板。 */
@Composable
fun AnalyzerScreen() {
    var layout by remember { mutableStateOf(BallLayout.practice()) }
    var targetNumber by remember { mutableIntStateOf(1) }
    // null 表示自动选袋
    var manualPocket by remember { mutableStateOf<Int?>(null) }
    var hitPoint by remember { mutableStateOf(HitPoint()) }
    var power by remember { mutableFloatStateOf(4f) }
    // 默认只画当前力度，叠加 8 档时台面线太多
    var showAllPowers by remember { mutableStateOf(false) }
    var nextNumber by remember { mutableStateOf<Int?>(null) }
    var showHeat by remember { mutableStateOf(true) }

    val aim = remember(layout, targetNumber, manualPocket) {
        val pocket = manualPocket ?: AimAnalyzer.bestPocket(layout, targetNumber)
        pocket?.let { AimAnalyzer.analyze(layout, targetNumber, it) }
    }
    val simulator = remember { Simulator(layout.table) }
    // 球形、目标、击点或力度变化时，在后台模拟当前这一杆
    val currentShot by produceState<SimulationResult?>(null, layout, aim, hitPoint, power) {
        val a = aim ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.Default) {
            simulator.simulate(layout, Shot(a.ghostBall - a.cuePosition, powerSpeed(power), hitPoint.y, hitPoint.x))
        }
    }
    // 只有打开"叠加全部力度"才模拟 1–8 参考档
    val references by produceState<List<SimulationResult>>(emptyList(), layout, aim, hitPoint, showAllPowers) {
        val a = aim
        value = if (!showAllPowers || a == null) emptyList() else withContext(Dispatchers.Default) {
            (1..PowerLevels.count).map { level ->
                simulator.simulate(layout, Shot(a.ghostBall - a.cuePosition, PowerLevels.speed(level), hitPoint.y, hitPoint.x))
            }
        }
    }
    val preview = currentShot?.let { ShotPreview(hitPoint, power, it, if (showAllPowers) references else emptyList()) }

    val heatActive = showHeat && nextNumber != null
    // 走位热区和杆法 × 力度扫描都比较重，同样放到后台算
    val heatMap by produceState<PositionPlanner.HeatMap?>(null, layout, aim?.targetNumber, nextNumber, heatActive) {
        val next = nextNumber
        val target = aim?.targetNumber
        value = if (!heatActive || next == null || target == null) null else withContext(Dispatchers.Default) {
            PositionPlanner.heatMap(layout, target, next)
        }
    }
    val scanOptions by produceState<List<PositionPlanner.Option>?>(null, layout, aim, nextNumber, heatActive, hitPoint.x) {
        val next = nextNumber
        val a = aim
        value = null
        if (heatActive && next != null && a != null) {
            value = withContext(Dispatchers.Default) { PositionPlanner.scan(simulator, layout, a, next, side = hitPoint.x) }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1B1B))
            .safeDrawingPadding()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TableView(
            layout = layout,
            aim = aim,
            preview = preview,
            heatMap = heatMap,
            scanOptions = scanOptions,
            nextNumber = nextNumber,
            onMoveBall = { number, target -> layout = layout.moveBall(number, target) },
            onTapBall = { number ->
                if (number != 0) {
                    targetNumber = number
                    manualPocket = null
                    if (nextNumber == number) nextNumber = null
                }
            },
            onLongPressBall = { number ->
                if (number != 0 && number != targetNumber) {
                    nextNumber = if (nextNumber == number) null else number
                }
            },
            onTapPocket = { index -> manualPocket = if (manualPocket == index) null else index },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        )
        Column(
            Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(Color(0xFF262626), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AimSection(aim = aim, autoPocket = manualPocket == null)
            HorizontalDivider(color = Color(0xFF3A3A3A))
            ShotSection(
                hitPoint = hitPoint,
                onHitPointChange = { hitPoint = it },
                power = power,
                onPowerChange = { power = it },
                showAllPowers = showAllPowers,
                onShowAllPowersChange = { showAllPowers = it },
                targetNumber = aim?.targetNumber,
                result = currentShot,
            )
            HorizontalDivider(color = Color(0xFF3A3A3A))
            PositionSection(
                nextNumber = nextNumber,
                onClearNext = { nextNumber = null },
                showHeat = showHeat,
                onShowHeatChange = { showHeat = it },
                options = scanOptions,
                hitPoint = hitPoint,
                power = power,
                onPick = { s, p ->
                    hitPoint = HitPoint(hitPoint.x, s.tipOffset).clamped()
                    power = p.toFloat()
                },
            )
            HorizontalDivider(color = Color(0xFF3A3A3A))
            Text(
                "点彩球换目标球，长按彩球设为下一颗，点袋口换袋（再点恢复自动）；拖动任意球改变球形。点台面上的任意一条线可查看它的详细说明。",
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}
