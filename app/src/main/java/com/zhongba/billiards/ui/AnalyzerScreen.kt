package com.zhongba.billiards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhongba.engine.AimAnalyzer
import com.zhongba.engine.AimedShot
import com.zhongba.engine.BallLayout
import com.zhongba.engine.BreakShot
import com.zhongba.engine.BreakSimulator
import com.zhongba.engine.HitPoint
import com.zhongba.engine.PositionPlanner
import com.zhongba.engine.PowerLevels
import com.zhongba.engine.RunOutPlanner
import com.zhongba.engine.SimulationResult
import com.zhongba.engine.ShotPlayback
import com.zhongba.engine.Simulator
import com.zhongba.engine.simulateAimed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 主界面：左边台面，右边分析面板。 */
@Composable
fun AnalyzerScreen() {
    // 两种模式各用一套球形，来回切换互不影响；清台模式默认摆满 15 颗球
    var analysisLayout by remember { mutableStateOf(BallLayout.practice()) }
    var runOutLayout by remember { mutableStateOf(BallLayout.fullTable()) }
    var targetNumber by remember { mutableIntStateOf(1) }
    // null 表示自动选袋
    var manualPocket by remember { mutableStateOf<Int?>(null) }
    var hitPoint by remember { mutableStateOf(HitPoint()) }
    var power by remember { mutableFloatStateOf(4f) }
    // 默认只画当前力度，叠加 8 档时台面线太多
    var showAllPowers by remember { mutableStateOf(false) }
    var nextNumber by remember { mutableStateOf<Int?>(null) }
    var showHeat by remember { mutableStateOf(true) }
    // 清台模式：自动规划整台的击球顺序和杆法
    var runOutMode by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf(RunOutPlanner.Group.ALL) }
    var selectedStep by remember { mutableIntStateOf(0) }
    // 清台模式下消除球、执行一杆之前的球形，用于撤销
    var history by remember { mutableStateOf(listOf<BallLayout>()) }
    val layout = if (runOutMode) runOutLayout else analysisLayout
    fun setLayout(next: BallLayout) {
        if (runOutMode) runOutLayout = next else analysisLayout = next
    }
    // 清台模式里刚打的那一杆（点球或"打第 1 杆"），以及打不进时的提示
    var lastShot by remember { mutableStateOf<RunOutPlanner.Step?>(null) }
    var shotMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun changeRunOutLayout(next: BallLayout) {
        history = (history + runOutLayout).takeLast(MAX_UNDO)
        runOutLayout = next
    }
    // 执行一杆时先按物理引擎的轨迹播放击球动画，播完才更新球形并重新规划
    val animationFrame = remember { mutableStateOf<AnimationFrame?>(null) }
    var playing by remember { mutableStateOf<PlayingShot?>(null) }
    ShotAnimator(playing, animationFrame)
    fun playRunOutStep(step: RunOutPlanner.Step) {
        if (playing != null) return
        shotMessage = null
        playing = PlayingShot(ShotPlayback(step.before, step.result)) {
            changeRunOutLayout(step.after)
            lastShot = step
            playing = null
        }
    }

    // 清台模式不需要单杆瞄准分析，置空后单杆模拟、热区和扫描都不会在后台白算
    val aim = if (runOutMode) null else remember(layout, targetNumber, manualPocket) {
        val pocket = manualPocket ?: AimAnalyzer.bestPocket(layout, targetNumber)
        pocket?.let { AimAnalyzer.analyze(layout, targetNumber, it) }
    }
    val simulator = remember { Simulator(layout.table) }
    // 球形、目标、击点或力度变化时，在后台模拟当前这一杆（补偿 throw 后的瞄准）
    val currentAimed by produceState<AimedShot?>(null, layout, aim, hitPoint, power) {
        val a = aim ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.Default) {
            simulator.simulateAimed(layout, a, powerSpeed(power), hitPoint)
        }
    }
    val currentShot = currentAimed?.result
    // 只有打开"叠加全部力度"才模拟 1–8 参考档
    val references by produceState<List<SimulationResult>>(emptyList(), layout, aim, hitPoint, showAllPowers) {
        val a = aim
        value = if (!showAllPowers || a == null) emptyList() else withContext(Dispatchers.Default) {
            (1..PowerLevels.count).map { level ->
                simulator.simulateAimed(layout, a, PowerLevels.speed(level), hitPoint).result
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

    // 清台规划：球形或球组一变就在后台重算，旧的计算会被取消
    // 规划和点球击打共用的候选打法缓存；换球组后作废
    val runOutCache = remember(group) { RunOutPlanner.Cache() }
    val runOutState by produceState(RunOutState(null, 0f), layout, group, runOutMode) {
        if (!runOutMode) return@produceState
        value = RunOutState(null, 0f)
        val plan = withContext(Dispatchers.Default) {
            RunOutPlanner.plan(
                simulator,
                layout,
                group,
                // 球多时束宽减小，手机上也能几秒内算完
                beamWidth = if (layout.balls.size > LARGE_LAYOUT_BALLS) 2 else 4,
                isCancelled = { !isActive },
                onProgress = { value = RunOutState(null, it) },
                cache = runOutCache,
            )
        }
        selectedStep = 0
        value = RunOutState(plan, 1f)
    }
    // 用户在某一杆改用了备选打法后的路线；球形或球组一变就作废
    var customPlan by remember(runOutLayout, group) { mutableStateOf<RunOutPlanner.Plan?>(null) }
    var customPlanning by remember { mutableStateOf(false) }
    val runOutPlan = (customPlan ?: runOutState.plan).takeIf { runOutMode }
    val selectedRunOutStep = runOutPlan?.steps?.getOrNull(selectedStep)
    // 选中那一杆的几种打法（同一颗球换袋口/杆法），从规划缓存里取，几乎不用重新计算
    val alternatives by produceState(emptyList<RunOutPlanner.Step>(), selectedRunOutStep) {
        val step = selectedRunOutStep ?: run { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.Default) { RunOutPlanner.alternatives(simulator, step, group, runOutCache) }
    }
    var previewAlternative by remember(selectedRunOutStep) { mutableStateOf<RunOutPlanner.Step?>(null) }

    // 顶部一行瞄准分析，下面左台面、右窄面板；台面尽量大
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF1B1B1B))
            .safeDrawingPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 顶部一行：瞄准分析 + 分析/清台模式切换
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AimBar(
                aim = if (runOutMode) selectedRunOutStep?.aim else aim,
                autoPocket = !runOutMode && manualPocket == null,
                modifier = Modifier.weight(1f),
            )
            SegmentedToggle(listOf("分析", "清台"), if (runOutMode) 1 else 0, { runOutMode = it == 1 }, Modifier.width(150.dp))
        }
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TableView(
                layout = layout,
                aim = aim.takeUnless { runOutMode },
                preview = preview.takeUnless { runOutMode },
                heatMap = heatMap.takeUnless { runOutMode },
                scanOptions = scanOptions.takeUnless { runOutMode },
                nextNumber = nextNumber.takeUnless { runOutMode },
                runOut = runOutPlan?.let { RunOutView(it, selectedStep, lastShot, previewAlternative) },
                animation = animationFrame.value,
                onSkipAnimation = { playing?.skip = true },
                onMoveBall = { number, target -> setLayout(layout.moveBall(number, target)) },
                onTapBall = { number ->
                    if (runOutMode) {
                        // 清台模式：点球 = 让白球模拟把这颗球打进（自动挑袋口和杆法），白球停到打完后的走位点
                        if (number != 0 && playing == null) {
                            val before = runOutLayout
                            shotMessage = "正在模拟打 $number 号球…"
                            scope.launch {
                                val step = withContext(Dispatchers.Default) {
                                    RunOutPlanner.bestShotOn(simulator, before, group, number, runOutCache)
                                }
                                if (runOutLayout !== before) return@launch // 计算期间球形又变了，丢弃结果
                                when {
                                    step != null -> playRunOutStep(step)
                                    number == 8 && RunOutPlanner.remainingTargets(before, group) != listOf(8) ->
                                        shotMessage = "黑 8 要等彩球全部打完才能打"
                                    !group.owns(number) && number != 8 -> shotMessage = "$number 号不在要清的球组里"
                                    else -> shotMessage = "$number 号球从现在的白球位置打不进"
                                }
                            }
                        }
                    } else if (number != 0) {
                        targetNumber = number
                        manualPocket = null
                        if (nextNumber == number) nextNumber = null
                    }
                },
                onLongPressBall = { number ->
                    if (!runOutMode && number != 0 && number != targetNumber) {
                        nextNumber = if (nextNumber == number) null else number
                    }
                },
                onTapPocket = { index -> if (!runOutMode) manualPocket = if (manualPocket == index) null else index },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
            Column(
                Modifier
                    .width(220.dp)
                    .fillMaxHeight()
                    .background(Color(0xFF262626), RoundedCornerShape(12.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (runOutMode) {
                    RunOutSection(
                        group = group,
                        onGroupChange = { group = it },
                        state = if (customPlanning) RunOutState(null, 0f) else RunOutState(runOutPlan, runOutState.progress),
                        alternatives = alternatives,
                        previewAlternative = previewAlternative,
                        onPreviewAlternative = { previewAlternative = if (previewAlternative === it) null else it },
                        onChooseAlternative = { choice ->
                            val plan = runOutPlan
                            if (plan != null && !customPlanning) {
                                val index = selectedStep
                                customPlanning = true
                                scope.launch {
                                    val updated = withContext(Dispatchers.Default) {
                                        RunOutPlanner.planWith(
                                            simulator, plan, index, choice, group,
                                            beamWidth = if (layout.balls.size > LARGE_LAYOUT_BALLS) 2 else 4,
                                            cache = runOutCache,
                                        )
                                    }
                                    customPlan = updated
                                    selectedStep = index
                                    previewAlternative = null
                                    customPlanning = false
                                }
                            }
                        },
                        selected = selectedStep,
                        onSelect = { selectedStep = it },
                        onPlayFirst = { runOutPlan?.steps?.firstOrNull()?.let(::playRunOutStep) },
                        onShowDetail = { i ->
                            // 切回分析模式，把这一杆的球形、目标、袋口、击点和力度都带过去
                            runOutPlan?.steps?.getOrNull(i)?.let { step ->
                                analysisLayout = step.before
                                targetNumber = step.target
                                manualPocket = step.aim.pocketIndex
                                hitPoint = step.hitPoint
                                power = step.power.toFloat()
                                runOutMode = false
                            }
                        },
                        onReset = {
                            changeRunOutLayout(BallLayout.fullTable(layout.table))
                            lastShot = null
                            shotMessage = null
                        },
                        onBreak = {
                            // 开球模式：摆好三角框，模拟一杆随机的大力开球，播完动画后用散开的球形练清台
                            if (playing == null) {
                                shotMessage = "开球中…"
                                scope.launch {
                                    val shot = withContext(Dispatchers.Default) { BreakSimulator.breakShot(simulator, layout.table) }
                                    playing = PlayingShot(ShotPlayback(shot.start, shot.result)) {
                                        changeRunOutLayout(shot.after)
                                        lastShot = null
                                        shotMessage = breakSummary(shot)
                                        playing = null
                                    }
                                }
                            }
                        },
                        lastShot = lastShot,
                        shotMessage = shotMessage,
                        canUndo = history.isNotEmpty(),
                        onUndo = {
                            runOutLayout = history.last()
                            history = history.dropLast(1)
                            lastShot = null
                            shotMessage = null
                        },
                    )
                } else {
                    ShotSection(
                        hitPoint = hitPoint,
                        onHitPointChange = { hitPoint = it },
                        power = power,
                        onPowerChange = { power = it },
                        showAllPowers = showAllPowers,
                        onShowAllPowersChange = { showAllPowers = it },
                        targetNumber = aim?.targetNumber,
                        result = currentShot,
                        throwCorrectionDeg = currentAimed?.throwCorrectionDeg,
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
                }
                if (!runOutMode) {
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
    }
}

private const val MAX_UNDO = 30
private const val LARGE_LAYOUT_BALLS = 10

/** 开球结果的一句话说明。 */
private fun breakSummary(shot: BreakShot): String = buildString {
    val colored = shot.pocketed.filter { it != 8 }
    append(if (colored.isEmpty()) "开球：没有进球" else "开球：进了 ${colored.joinToString("、")} 号")
    if (8 in shot.pocketed) append("；黑 8 进袋，已摆回置球点")
    if (shot.scratched) append("；白球落袋，已放回开球区")
}
