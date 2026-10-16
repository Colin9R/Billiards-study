package com.zhongba.billiards.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.zhongba.engine.AimResult
import com.zhongba.engine.Ball
import com.zhongba.engine.BallLayout
import com.zhongba.engine.Pocket
import com.zhongba.engine.PositionPlanner
import com.zhongba.engine.TableSpec
import com.zhongba.engine.Vec2

/**
 * 俯视台面：画中八球台和球，并支持手指拖动任意球；点球选目标球，点袋口选袋。
 * [aim] 不为空时叠加瞄准线、假想球、目标球路线和定杆切线；
 * [preview] 不为空时叠加当前力度下白球的走位线和停点；
 * [runOut] 不为空时叠加清台路线（击球顺序、每一杆白球走位和停点）。
 * 台面坐标（毫米）与屏幕像素之间的换算见 [TableTransform]。
 */
@Composable
fun TableView(
    layout: BallLayout,
    aim: AimResult?,
    preview: ShotPreview?,
    heatMap: PositionPlanner.HeatMap?,
    scanOptions: List<PositionPlanner.Option>?,
    nextNumber: Int?,
    runOut: RunOutView?,
    animation: AnimationFrame?,
    onSkipAnimation: () -> Unit,
    onMoveBall: (number: Int, target: Vec2) -> Unit,
    onTapBall: (number: Int) -> Unit,
    onTapPocket: (index: Int) -> Unit,
    onLongPressBall: (number: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val currentLayout by rememberUpdatedState(layout)
    val currentOnMove by rememberUpdatedState(onMoveBall)
    val currentOnTapBall by rememberUpdatedState(onTapBall)
    val currentOnTapPocket by rememberUpdatedState(onTapPocket)
    val currentOnLongPress by rememberUpdatedState(onLongPressBall)
    val currentAnimating by rememberUpdatedState(animation != null)
    val currentOnSkip by rememberUpdatedState(onSkipAnimation)
    var dragging by remember { mutableStateOf<Int?>(null) }
    val currentAim by rememberUpdatedState(aim)
    val currentPreview by rememberUpdatedState(preview)
    // 点中的线（按标题记）和点击位置，用来高亮并弹出说明卡片
    var selectedLine by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    // 最近一次绘制用的坐标换算，手势回调里读取；不是 Compose 状态，写入不会触发重组。
    val transformRef = remember { arrayOfNulls<TableTransform>(1) }

    Canvas(
        modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = { press ->
                        val t = transformRef[0] ?: return@detectTapGestures
                        currentLayout.ballAt(t.toTable(press), slop = t.toTableLength(TOUCH_SLOP_PX))
                            ?.let { currentOnLongPress(it.number) }
                    },
                ) { tap ->
                    // 动画播放中点台面任意处 = 跳过动画
                    if (currentAnimating) {
                        currentOnSkip()
                        return@detectTapGestures
                    }
                    val t = transformRef[0] ?: return@detectTapGestures
                    val p = t.toTable(tap)
                    val ball = currentLayout.ballAt(p, slop = t.toTableLength(TOUCH_SLOP_PX))
                    if (ball != null) {
                        selectedLine = null
                        currentOnTapBall(ball.number)
                        return@detectTapGestures
                    }
                    val line = currentAim?.let { a ->
                        nearestLine(describeLines(a, currentPreview, currentLayout.table.ballRadius), tap, t, LINE_SLOP_PX)
                    }
                    if (line != null) {
                        selectedLine = line.title to tap
                        return@detectTapGestures
                    }
                    selectedLine = null
                    currentLayout.table.pockets
                        .withIndex()
                        .filter { it.value.center.distanceTo(p) < it.value.mouth * 1.2 }
                        .minByOrNull { it.value.center.distanceTo(p) }
                        ?.let { currentOnTapPocket(it.index) }
                }
            }
            .pointerInput(Unit) {
            // 手指按在球上偏离球心的量，拖动时保持不变，球不会"跳"到指尖下。
            var grabOffset = Vec2.ZERO
            detectDragGestures(
                onDragStart = { start ->
                    if (currentAnimating) return@detectDragGestures
                    val t = transformRef[0] ?: return@detectDragGestures
                    val p = t.toTable(start)
                    val ball = currentLayout.ballAt(p, slop = t.toTableLength(TOUCH_SLOP_PX))
                    dragging = ball?.number
                    if (ball != null) selectedLine = null
                    grabOffset = ball?.let { p - it.position } ?: Vec2.ZERO
                },
                onDragEnd = { dragging = null },
                onDragCancel = { dragging = null },
                onDrag = { change, _ ->
                    val number = dragging ?: return@detectDragGestures
                    val t = transformRef[0] ?: return@detectDragGestures
                    change.consume()
                    currentOnMove(number, t.toTable(change.position) - grabOffset)
                },
            )
        },
    ) {
        val t = TableTransform(layout.table, size).also { transformRef[0] = it }
        drawTable(t)
        if (animation != null) {
            // 播放中只画动画：球按物理引擎的轨迹滚动，不画分析线和路线
            drawAnimationFrame(t, animation, textMeasurer)
            return@Canvas
        }
        aim?.let {
            // 只在台呢范围内画线，定杆切线等不会穿出库边
            val topLeft = t.toScreen(Vec2.ZERO)
            val bottomRight = t.toScreen(Vec2(layout.table.length, layout.table.width))
            clipRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y) {
                heatMap?.let { h -> drawHeatMap(t, h) }
                drawAimLines(t, it)
                preview?.let { p -> drawPreviewPaths(t, p) }
            }
        }
        runOut?.let {
            val topLeft = t.toScreen(Vec2.ZERO)
            val bottomRight = t.toScreen(Vec2(layout.table.length, layout.table.width))
            clipRect(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y) { drawRunOutPaths(t, it) }
        }
        layout.balls.forEach { drawBall(t, it, textMeasurer, highlighted = it.number == dragging) }
        runOut?.let { drawRunOutBadges(t, it, layout, textMeasurer) }
        aim?.let { drawAimMarkers(t, it) }
        preview?.let { drawStopMarkers(t, it) }
        scanOptions?.let { drawScanStops(t, it) }
        nextNumber?.let { n -> layout.balls.firstOrNull { it.number == n }?.let { drawNextBallRing(t, it) } }
        // 文字标签最后画，盖在线和球上面；点中的线高亮，说明卡片在最上层
        aim?.let { a ->
            val selected = selectedLine?.let { (title, at) ->
                describeLines(a, preview, layout.table.ballRadius).firstOrNull { it.title == title }?.let { it to at }
            }
            selected?.let { drawLineHighlight(t, it.first) }
            drawLineLabels(t, layout, a, preview, textMeasurer)
            selected?.let { drawLineCard(it.first, it.second, textMeasurer) }
        }
    }
}

private const val TOUCH_SLOP_PX = 24f
private const val LINE_SLOP_PX = 28f

/** 台面毫米坐标 ↔ 画布像素。整张球台（含库边和台框）等比缩放后居中。 */
class TableTransform(val table: TableSpec, canvas: Size) {
    private val border = table.railWidth + table.frameWidth
    val scale: Float = minOf(
        canvas.width / (table.length + 2 * border).toFloat(),
        canvas.height / (table.width + 2 * border).toFloat(),
    )
    private val origin = Offset(
        (canvas.width - (table.length.toFloat() + 2 * border.toFloat()) * scale) / 2 + border.toFloat() * scale,
        (canvas.height - (table.width.toFloat() + 2 * border.toFloat()) * scale) / 2 + border.toFloat() * scale,
    )

    fun toScreen(p: Vec2) = Offset(origin.x + p.x.toFloat() * scale, origin.y + p.y.toFloat() * scale)
    fun toScreen(mm: Double) = mm.toFloat() * scale
    fun toTable(o: Offset) = Vec2(((o.x - origin.x) / scale).toDouble(), ((o.y - origin.y) / scale).toDouble())
    fun toTableLength(px: Float) = (px / scale).toDouble()
}

private val Wood = Color(0xFF5A3420)
private val WoodLight = Color(0xFF7A4A2E)
private val Cushion = Color(0xFF0B5E3A)
private val Cloth = Color(0xFF117A4B)
private val Diamond = Color(0xFFF2E8D0)

private fun DrawScope.drawTable(t: TableTransform) {
    val table = t.table
    val rail = t.toScreen(table.railWidth)
    val frame = t.toScreen(table.frameWidth)
    val topLeft = t.toScreen(Vec2.ZERO)
    val cloth = Size(t.toScreen(table.length), t.toScreen(table.width))

    // 台框（木头）
    drawRoundRect(
        brush = Brush.verticalGradient(listOf(WoodLight, Wood)),
        topLeft = topLeft - Offset(rail + frame, rail + frame),
        size = Size(cloth.width + 2 * (rail + frame), cloth.height + 2 * (rail + frame)),
        cornerRadius = CornerRadius(frame * 0.6f),
    )
    // 库边胶条
    drawRect(Cushion, topLeft - Offset(rail, rail), Size(cloth.width + 2 * rail, cloth.height + 2 * rail))
    // 台呢
    drawRect(Cloth, topLeft, cloth)

    // 开球线与置球点
    val headTop = t.toScreen(Vec2(table.headStringX, 0.0))
    drawLine(
        Color.White.copy(alpha = 0.25f),
        headTop,
        headTop + Offset(0f, cloth.height),
        strokeWidth = 1.5f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
    )
    drawCircle(Color.White.copy(alpha = 0.5f), radius = t.toScreen(6.0), center = t.toScreen(table.footSpot))
    drawCircle(Color.White.copy(alpha = 0.5f), radius = t.toScreen(6.0), center = t.toScreen(Vec2(table.headStringX, table.width / 2)))

    // 库边上的菱形点：长边每侧 6 个（中袋两边各 3 个），短边 3 个
    val sightOffset = table.railWidth + table.frameWidth / 2
    for (k in 1..7) {
        if (k == 4) continue
        val x = table.length * k / 8
        drawSight(t, Vec2(x, -sightOffset))
        drawSight(t, Vec2(x, table.width + sightOffset))
    }
    for (k in 1..3) {
        val y = table.width * k / 4
        drawSight(t, Vec2(-sightOffset, y))
        drawSight(t, Vec2(table.length + sightOffset, y))
    }

    // 袋口：圆心往台外偏一点，让袋口刚好咬住库边
    table.pockets.forEach { pocket ->
        val c = t.toScreen(pocketHoleCenter(pocket))
        val radius = t.toScreen(pocket.mouth * 0.62)
        drawCircle(Color(0xFF2A2A2A), radius = radius * 1.12f, center = c)
        drawCircle(Color.Black, radius = radius, center = c)
    }
}

/** 画袋洞的圆心：从袋口中心往台外偏一点，让袋洞刚好咬住库边。 */
private fun pocketHoleCenter(pocket: Pocket): Vec2 {
    val outward = if (pocket.isCorner) {
        Vec2(
            if (pocket.center.x == 0.0) -1.0 else 1.0,
            if (pocket.center.y == 0.0) -1.0 else 1.0,
        ).normalized() * (pocket.mouth * 0.35)
    } else {
        Vec2(0.0, if (pocket.center.y == 0.0) -1.0 else 1.0) * (pocket.mouth * 0.45)
    }
    return pocket.center + outward
}

internal val AimLine = Color.White.copy(alpha = 0.85f)
internal val ObjectLineOk = Color(0xFFFFD54F)
internal val ObjectLineBad = Color(0xFFFF5252)
// 紫色，和 1 档走位线的浅蓝区分开（以前两者同色，容易看混）
internal val StunLine = Color(0xFFE040FB)

/** 画在球下面的线：白球瞄准线、目标球进袋路线、定杆切线（90° 法则）。 */
private fun DrawScope.drawAimLines(t: TableTransform, aim: AimResult) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
    drawLine(AimLine, t.toScreen(aim.cuePosition), t.toScreen(aim.ghostBall), strokeWidth = 2.5f)
    val target = aim.ghostBall + aim.objectDirection * (2 * t.table.ballRadius)
    drawLine(
        if (aim.isPottable) ObjectLineOk else ObjectLineBad,
        t.toScreen(target),
        t.toScreen(aim.objectPathEnd),
        strokeWidth = 2.5f,
        pathEffect = if (aim.isPottable) null else dash,
    )
    aim.stunDirection?.let { dir ->
        drawLine(
            StunLine,
            t.toScreen(aim.ghostBall),
            t.toScreen(aim.ghostBall + dir * STUN_LINE_MM),
            strokeWidth = 2f,
            pathEffect = dash,
        )
    }
}

/** 画在球上面的标记：假想球轮廓、目标球与目标袋高亮。 */
private fun DrawScope.drawAimMarkers(t: TableTransform, aim: AimResult) {
    val r = t.toScreen(t.table.ballRadius)
    drawCircle(AimLine, radius = r, center = t.toScreen(aim.ghostBall), style = Stroke(width = 2f))
    val target = aim.ghostBall + aim.objectDirection * (2 * t.table.ballRadius)
    val ring = if (aim.isPottable) ObjectLineOk else ObjectLineBad
    drawCircle(ring, radius = r * 1.3f, center = t.toScreen(target), style = Stroke(width = r * 0.12f))
    drawCircle(ring, radius = t.toScreen(aim.pocket.mouth * 0.75), center = t.toScreen(pocketHoleCenter(aim.pocket)), style = Stroke(width = 3f))
}

private const val STUN_LINE_MM = 500.0
private const val HEAT_MIN_SCORE = 0.5

/** 热力图：评分越高越绿越不透明，低于阈值的格子不画。 */
private fun DrawScope.drawHeatMap(t: TableTransform, heat: PositionPlanner.HeatMap) {
    val cell = t.toScreen(heat.cellSize)
    for (row in 0 until heat.rows) for (col in 0 until heat.cols) {
        val score = heat.score(col, row)
        // 只突出真正好打的区域：低于 0.5 不画，越高越不透明
        if (score < HEAT_MIN_SCORE) continue
        val strength = ((score - HEAT_MIN_SCORE) / (1 - HEAT_MIN_SCORE)).toFloat()
        val center = t.toScreen(heat.cellCenter(col, row))
        drawRect(
            scoreColor(score).copy(alpha = 0.08f + 0.5f * strength * strength),
            topLeft = center - Offset(cell / 2, cell / 2),
            size = Size(cell, cell),
        )
    }
}

/** 杆法 × 力度扫描中成功组合的白球停点，颜色同走位评分。 */
private fun DrawScope.drawScanStops(t: TableTransform, options: List<PositionPlanner.Option>) {
    val r = t.toScreen(t.table.ballRadius)
    options.filter { it.success }.forEach { option ->
        val stop = option.cueStop ?: return@forEach
        val c = t.toScreen(stop)
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = r * 0.42f, center = c)
        drawCircle(scoreColor(option.score), radius = r * 0.32f, center = c)
    }
}

private fun DrawScope.drawNextBallRing(t: TableTransform, ball: Ball) {
    val r = t.toScreen(t.table.ballRadius)
    drawCircle(
        Color(0xFF18FFFF),
        radius = r * 1.45f,
        center = t.toScreen(ball.position),
        style = Stroke(width = r * 0.14f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * 0.5f, r * 0.3f))),
    )
}

internal fun DrawScope.drawPolyline(t: TableTransform, points: List<Vec2>, color: Color, width: Float, effect: PathEffect? = null) {
    if (points.size < 2) return
    val path = Path().apply {
        val first = t.toScreen(points.first())
        moveTo(first.x, first.y)
        points.drop(1).forEach { val p = t.toScreen(it); lineTo(p.x, p.y) }
    }
    drawPath(path, color, style = Stroke(width = width, pathEffect = effect))
}

/** 走位线：参考档细线（叠加时），当前力度粗线，被碰到的球画虚线。 */
private fun DrawScope.drawPreviewPaths(t: TableTransform, preview: ShotPreview) {
    preview.references.forEachIndexed { index, result ->
        result.paths[0]?.let { drawPolyline(t, it, powerColor(index + 1).copy(alpha = 0.55f), 2f) }
    }
    val current = preview.current
    current.paths.forEach { (number, path) ->
        if (number != 0) drawPolyline(t, path, Color.White.copy(alpha = 0.45f), 2f, PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
    }
    current.paths[0]?.let { drawPolyline(t, it, powerColor(preview.power), 5f) }
}

/** 白球停点：参考档小圆点（叠加时），当前力度画一个半透明的"白球"；落袋画红叉。 */
private fun DrawScope.drawStopMarkers(t: TableTransform, preview: ShotPreview) {
    val r = t.toScreen(t.table.ballRadius)
    fun cross(c: Offset) {
        val k = r * 0.6f
        drawLine(Color(0xFFFF5252), c - Offset(k, k), c + Offset(k, k), strokeWidth = 4f)
        drawLine(Color(0xFFFF5252), c - Offset(k, -k), c + Offset(k, -k), strokeWidth = 4f)
    }
    preview.references.forEachIndexed { index, result ->
        val end = t.toScreen(result.paths[0]?.last() ?: return@forEachIndexed)
        if (0 in result.pocketed) cross(end) else drawCircle(powerColor(index + 1), radius = r * 0.35f, center = end)
    }
    val c = t.toScreen(preview.current.paths[0]?.last() ?: return)
    if (0 in preview.current.pocketed) {
        cross(c)
    } else {
        drawCircle(Color.White.copy(alpha = 0.35f), radius = r, center = c)
        drawCircle(powerColor(preview.power), radius = r, center = c, style = Stroke(width = 3f))
    }
}

private fun DrawScope.drawSight(t: TableTransform, p: Vec2) {
    drawCircle(Diamond, radius = t.toScreen(7.0), center = t.toScreen(p))
}

/** 中八球色：1–7 全色、8 黑、9–15 为对应颜色的花色。 */
private fun ballColor(number: Int): Color = when ((number - 1) % 8 + 1) {
    1 -> Color(0xFFF2C200)
    2 -> Color(0xFF1846B8)
    3 -> Color(0xFFD42020)
    4 -> Color(0xFF5B2A86)
    5 -> Color(0xFFF07800)
    6 -> Color(0xFF0E7A3A)
    7 -> Color(0xFF7A1E1E)
    else -> Color(0xFF111111)
}

/**
 * 画一颗球。[rolled] 为滚过的距离（毫米）、[heading] 为滚动方向：号码圈会沿滚动方向转到球的侧面再转回来，
 * 球的两面都印有号码，所以总有一面朝上；[sinking] 为落袋进度 0–1，球逐渐缩小变暗。
 */
internal fun DrawScope.drawBall(
    t: TableTransform,
    ball: Ball,
    textMeasurer: TextMeasurer,
    highlighted: Boolean,
    rolled: Double = 0.0,
    heading: Vec2 = Vec2(1.0, 0.0),
    sinking: Double = 0.0,
) {
    val c = t.toScreen(ball.position)
    val r = t.toScreen(t.table.ballRadius) * (1 - 0.6f * sinking.toFloat())
    val fade = 1 - 0.7f * sinking.toFloat()

    // 阴影
    drawCircle(Color.Black.copy(alpha = 0.35f * fade), radius = r, center = c + Offset(r * 0.15f, r * 0.2f))

    when {
        ball.isCue -> drawCircle(Color(0xFFF7F4EA).copy(alpha = fade), radius = r, center = c)
        ball.isStripe -> {
            drawCircle(Color(0xFFF7F4EA).copy(alpha = fade), radius = r, center = c)
            clipRect(top = c.y - r * 0.55f, bottom = c.y + r * 0.55f) {
                drawCircle(ballColor(ball.number).copy(alpha = fade), radius = r, center = c)
            }
        }
        else -> drawCircle(ballColor(ball.number).copy(alpha = fade), radius = r, center = c)
    }

    // 滚动：号码圈（白球为红点）随滚过的角度沿运动方向移动，侧面时被压扁
    val angle = rolled / t.table.ballRadius
    val facing = kotlin.math.abs(kotlin.math.cos(angle)).toFloat()
    val shift = (kotlin.math.sin(angle) * kotlin.math.sign(kotlin.math.cos(angle).takeIf { it != 0.0 } ?: 1.0)).toFloat()
    val spot = c + Offset(heading.x.toFloat(), heading.y.toFloat()) * (r * shift)
    if (ball.isCue) {
        if (rolled > 0) drawCircle(Color(0xFFD32F2F).copy(alpha = fade), radius = r * 0.16f * (0.4f + 0.6f * facing), center = spot)
    } else {
        drawCircle(Color.White.copy(alpha = fade), radius = r * 0.45f * (0.45f + 0.55f * facing), center = spot)
        if (facing > 0.45f && sinking < 0.5) {
            val label = textMeasurer.measure(
                ball.number.toString(),
                TextStyle(color = Color.Black, fontSize = (r * 0.5f * facing / density).sp, fontWeight = FontWeight.Bold),
            )
            drawText(label, topLeft = spot - Offset(label.size.width / 2f, label.size.height / 2f))
        }
    }

    // 高光固定在左上方（光源不动），做出球体感
    drawCircle(
        Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.55f * fade), Color.Transparent),
            center = c - Offset(r * 0.35f, r * 0.35f),
            radius = r * 0.7f,
        ),
        radius = r,
        center = c,
    )

    if (highlighted) {
        drawCircle(Color.White.copy(alpha = 0.8f), radius = r * 1.35f, center = c, style = Stroke(width = r * 0.12f))
    }
}
