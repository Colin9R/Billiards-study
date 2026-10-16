package com.zhongba.billiards.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import com.zhongba.engine.Ball
import com.zhongba.engine.BallPose
import com.zhongba.engine.Impact
import com.zhongba.engine.ShotPlayback
import com.zhongba.engine.Vec2
import kotlin.math.min

/** 球杆的姿态：杆头贴在白球后方 [pullBack] 毫米处（负数为出杆后的送杆）。 */
class CueStickPose(val cueBall: Vec2, val direction: Vec2, val pullBack: Double, val alpha: Float)

/** 动画的一帧：每颗球的姿态、最近的碰撞闪光、球杆。 */
class AnimationFrame(val poses: List<BallPose>, val impacts: List<Impact>, val cueStick: CueStickPose?)

/** 正在播放的一杆。[skip] 被置为 true 时立即跳到结束。 */
class PlayingShot(val playback: ShotPlayback, val onFinished: () -> Unit) {
    @Volatile var skip = false
}

private const val AIM_SECONDS = 0.5      // 后拉瞄准
private const val STRIKE_SECONDS = 0.08  // 出杆
private const val FOLLOW_SECONDS = 0.3   // 送杆后球杆淡出
private const val PULL_BACK_MM = 90.0
private const val FOLLOW_THROUGH_MM = -20.0

/**
 * 逐帧驱动一杆的回放：先画球杆后拉、出杆，然后按物理引擎算出的轨迹以真实时间播放，
 * 结束（或被跳过）后调用 [PlayingShot.onFinished]。
 */
@Composable
fun ShotAnimator(playing: PlayingShot?, frame: MutableState<AnimationFrame?>) {
    LaunchedEffect(playing) {
        val shot = playing ?: return@LaunchedEffect
        val playback = shot.playback
        val cuePath = playback.result.paths[0]
        val cueStart = cuePath?.firstOrNull()
        val direction = cuePath?.let { path -> path.firstOrNull { it.distanceTo(path.first()) > 1.0 }?.minus(path.first())?.normalized() }
        val ballsStart = AIM_SECONDS + STRIKE_SECONDS
        var startNanos = -1L
        while (!shot.skip) {
            val now = withFrameNanos { it }
            if (startNanos < 0) startNanos = now
            val elapsed = (now - startNanos) / 1e9
            val simTime = (elapsed - ballsStart).coerceAtLeast(0.0)
            if (simTime >= playback.duration) break
            val stick = if (cueStart != null && direction != null) stickPose(elapsed, cueStart, direction) else null
            frame.value = AnimationFrame(playback.poses(simTime), playback.recentImpacts(simTime), stick)
        }
        frame.value = null
        shot.onFinished()
    }
}

private fun stickPose(elapsed: Double, cueBall: Vec2, direction: Vec2): CueStickPose? = when {
    elapsed < AIM_SECONDS -> {
        val k = elapsed / AIM_SECONDS
        CueStickPose(cueBall, direction, PULL_BACK_MM * (1 - (1 - k) * (1 - k)), 1f)
    }
    elapsed < AIM_SECONDS + STRIKE_SECONDS -> {
        val k = (elapsed - AIM_SECONDS) / STRIKE_SECONDS
        CueStickPose(cueBall, direction, PULL_BACK_MM + (FOLLOW_THROUGH_MM - PULL_BACK_MM) * k, 1f)
    }
    elapsed < AIM_SECONDS + STRIKE_SECONDS + FOLLOW_SECONDS -> {
        val k = (elapsed - AIM_SECONDS - STRIKE_SECONDS) / FOLLOW_SECONDS
        CueStickPose(cueBall, direction, FOLLOW_THROUGH_MM, (1 - k).toFloat())
    }
    else -> null
}

/** 画一帧：碰撞闪光、按姿态画出的球（带滚动和落袋效果）、球杆。 */
internal fun DrawScope.drawAnimationFrame(t: TableTransform, frame: AnimationFrame, textMeasurer: TextMeasurer) {
    val r = t.toScreen(t.table.ballRadius)
    frame.impacts.forEach { impact ->
        val k = (impact.age / 0.18).toFloat().coerceIn(0f, 1f)
        drawCircle(Color.White.copy(alpha = 0.8f * (1 - k)), radius = r * (0.6f + 2.2f * k), center = t.toScreen(impact.position), style = Stroke(width = 3f))
    }
    frame.poses.forEach { pose ->
        drawBall(
            t,
            Ball(pose.number, pose.position),
            textMeasurer,
            highlighted = false,
            rolled = pose.rolled,
            heading = pose.heading,
            sinking = pose.sinking ?: 0.0,
        )
    }
    frame.cueStick?.let { drawCueStick(t, it) }
}

/** 球杆：深色杆尾 → 浅色杆身 → 白色先角 → 蓝色皮头，带阴影，粗细由杆尾向皮头渐细。 */
private fun DrawScope.drawCueStick(t: TableTransform, pose: CueStickPose) {
    val r = t.table.ballRadius
    val back = -pose.direction
    fun at(mm: Double) = t.toScreen(pose.cueBall + back * (r + pose.pullBack + mm))
    fun width(mm: Double) = t.toScreen(6.5 + 8.0 * min(mm, CUE_LENGTH_MM) / CUE_LENGTH_MM)
    val a = pose.alpha
    val shadow = Offset(t.toScreen(10.0), t.toScreen(14.0))
    drawLine(Color.Black.copy(alpha = 0.3f * a), at(0.0) + shadow, at(CUE_LENGTH_MM) + shadow, strokeWidth = width(CUE_LENGTH_MM), cap = StrokeCap.Round)
    drawLine(Color(0xFF3E2414).copy(alpha = a), at(CUE_LENGTH_MM * 0.55), at(CUE_LENGTH_MM), strokeWidth = width(CUE_LENGTH_MM), cap = StrokeCap.Round)
    drawLine(Color(0xFFE2C48F).copy(alpha = a), at(TIP_MM + FERRULE_MM), at(CUE_LENGTH_MM * 0.55), strokeWidth = width(CUE_LENGTH_MM * 0.3))
    drawLine(Color(0xFFF5F5F0).copy(alpha = a), at(TIP_MM), at(TIP_MM + FERRULE_MM), strokeWidth = width(TIP_MM))
    drawLine(Color(0xFF1E88E5).copy(alpha = a), at(0.0), at(TIP_MM), strokeWidth = width(0.0), cap = StrokeCap.Round)
}

private const val CUE_LENGTH_MM = 1450.0
private const val TIP_MM = 8.0
private const val FERRULE_MM = 22.0
