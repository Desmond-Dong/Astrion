package com.example.astrion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.astrion.ui.theme.RemoteColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 原生手感滑杆（原版 ViewAcTemperatureSlider / ViewLightBrightnessHr /
 * ViewColorTempPickerVertical / VerticalCapsuleSlider 的通用横杆）：
 *  - 按下时不跳变，拇指跟随手指的“偏移量”移动
 *  - <100ms 的轻点 = 直接吸附到点击处
 *  - 拖动中每个手势事件都回调 [onDrag]，由调用方按节流频率连续下发
 *  - 松手必定精确回调一次 [onCommit]（最终发送）
 *  - [vertical] 用 -90° 旋转实现，无需深拷贝一份垂直数学
 *
 * 横向使用时传入宽度（如 fillMaxWidth()），[axisLength] 可省略；
 * 纵向使用 [vertical] = true 且传 [axisLength] 作为视觉上的竖直长度。
 */
@Composable
fun NativeSlider(
    value: Float,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    enabled: Boolean = true,
    vertical: Boolean = false,
    axisLength: Dp = 300.dp,
    thickness: Dp = 6.dp,
    trackColor: Color = RemoteColors.key,
    activeColor: Color = RemoteColors.accent,
    trackBrush: Brush? = null,
    activeBrush: Brush? = null,
    thumbColor: Color = RemoteColors.onSurface,
    thumbSize: Dp = 18.dp,
) {
    val latestValue by rememberUpdatedState(value)
    val latestRange by rememberUpdatedState(valueRange)
    var widthPx by remember { mutableStateOf(0) }

    val density = LocalDensity.current
    val thumbPx = with(density) { thumbSize.toPx() }

    val fraction = ((value - valueRange.start) /
        (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)

    val mapFromDrag: (startValue: Float, startX: Float, x: Float) -> Float =
        { startValue, startX, x ->
            val span = if (widthPx > 0) widthPx.toFloat() else 1f
            (startValue + (x - startX) / span *
                (latestRange.endInclusive - latestRange.start))
                .coerceIn(latestRange.start, latestRange.endInclusive)
        }
    val mapFromAbsolute: (x: Float) -> Float = { x ->
        val span = if (widthPx > 0) widthPx.toFloat() else 1f
        (latestRange.start + x / span *
            (latestRange.endInclusive - latestRange.start))
            .coerceIn(latestRange.start, latestRange.endInclusive)
    }

    val baseSize = if (vertical) {
        Modifier
            .size(width = axisLength, height = thickness)
            .graphicsLayer { rotationZ = -90f }
    } else {
        Modifier.height(thumbSize)
    }

    Box(
        modifier = modifier
            .then(baseSize)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(valueRange, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startValue = latestValue
                    val startX = down.position.x
                    var dragging = false
                    var lastSent = startValue
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            val elapsed = change.uptimeMillis - down.uptimeMillis
                            val moved = abs(change.position.x - startX)
                            if (!dragging && elapsed < 100 && moved < thumbPx / 2 + 8f) {
                                val snap = mapFromAbsolute(change.position.x)
                                onDrag(snap)
                                onCommit(snap)
                            } else if (dragging) {
                                onCommit(lastSent)
                            }
                            change.consume()
                            break
                        }
                        if (change.positionChanged()) {
                            val delta = change.position.x - startX
                            if (dragging || abs(delta) > viewConfiguration.touchSlop) {
                                dragging = true
                                lastSent = mapFromDrag(startValue, startX, change.position.x)
                                onDrag(lastSent)
                            }
                            change.consume()
                        }
                    }
                }
            }
    ) {
        val trackShape = RoundedCornerShape(thickness / 2)
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thickness)
                    .clip(trackShape)
                    .then(
                        if (trackBrush != null) Modifier.background(trackBrush)
                        else Modifier.background(trackColor)
                    )
            )
            if (activeBrush != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(thickness)
                        .clip(trackShape)
                        .background(activeBrush)
                        .width((widthPx * fraction).dp)
                )
            } else if (activeColor.alpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(thickness)
                        .clip(trackShape)
                        .background(activeColor)
                        .width((widthPx * fraction).dp)
                )
            }
        }
        // 拇指
        Surface(
            shape = CircleShape,
            color = thumbColor,
            modifier = Modifier
                .offset { IntOffset(roundToInt((widthPx - thumbPx).coerceAtLeast(0) * fraction), 0) }
                .size(thumbSize)
                .align(Alignment.CenterStart)
        ) {}
    }
}

/**
 * 按住重复语义（原版 LongPressKeyHandler）：长按 [initialDelayMs] 后
 * 每 [repeatDelayMs] 重复触发一次 [onRepeat]；短按/松手触发 [onTap]。
 */
fun Modifier.holdRepeat(
    onTap: () -> Unit,
    onRepeat: () -> Unit,
    initialDelayMs: Long = 500,
    repeatDelayMs: Long = 500,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up = withTimeoutOrNull(initialDelayMs) { waitForUpOrCancellation() }
        if (up == null) {
            onRepeat()
            while (true) {
                val released = withTimeoutOrNull(repeatDelayMs) { waitForUpOrCancellation() }
                if (released != null) break
                onRepeat()
            }
        } else {
            onTap()
        }
    }
}