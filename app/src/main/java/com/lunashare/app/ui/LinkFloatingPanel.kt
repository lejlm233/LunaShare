package com.lunashare.app.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * Link Tab 右侧悬浮胶囊面板（沉浸式替代旧底部文字栏）。
 *
 * - 纯图标、无文字：主页(Home) / 缩放复位(ZoomOut，任何 WebView 页) / 侧边栏(Menu，仅 WebView 页)。
 * - 竖向胶囊（pill）吸附右边默认位置，可自由拖动避免遮挡内容；松手时吸附到最近的竖边
 *   （默认在右，拖到左半屏则吸左），纵向位置保留并夹紧在屏幕内。
 * - 位置记忆：吸附结果与纵向位置持久化到 SharedPreferences，下次进入 Link Tab 还原。
 * - 用 [visible] 控制仅 Link Tab 显示；其层级(zIndex=20)高于 WebView 与侧边栏抽屉，始终可触达。
 */
@Composable
fun LinkFloatingPanel(
    visible: Boolean,
    showSidebar: Boolean,
    showZoomReset: Boolean,
    onBack: () -> Unit,
    onToggleSidebar: () -> Unit,
    onZoomReset: () -> Unit,
) {
    if (!visible) return

    val density = LocalDensity.current
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("luna_link_panel_prefs", Context.MODE_PRIVATE) }
    val rightPad = 12.dp
    val rightPadPx = with(density) { rightPad.toPx() }
    // 胶囊尺寸：初始估计，首帧 onSizeChanged 后修正
    var panelW by remember { mutableFloatStateOf(with(density) { 46.dp.toPx() }) }
    var panelH by remember { mutableFloatStateOf(0f) }
    // 相对「右边居中」锚点的位移（px）。offsetX=0 → 贴右；负值 → 向左移动
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    // 是否已从持久化位置加载过（只加载一次，避免每次 onSizeChanged 覆盖拖动结果）
    var loaded by remember { mutableStateOf(false) }

    // 根据当前布局尺寸计算吸附/夹紧边界
    fun computeBounds(maxW: Float, maxH: Float): Pair<Float, Pair<Float, Float>> {
        val leftTargetX = panelW + 2 * rightPadPx - maxW
        val midH = (maxH - panelH) / 2f
        val edge = with(density) { 8.dp.toPx() }
        val yMin = edge - midH
        val yMax = midH - edge
        return leftTargetX to (yMin to yMax)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(20f)
    ) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()

        val animX = animateFloatAsState(offsetX, spring(stiffness = 1200f, dampingRatio = 1f))
        val animY = animateFloatAsState(offsetY, spring(stiffness = 1200f, dampingRatio = 1f))

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = rightPad)
                .offset { IntOffset(animX.value.roundToInt(), animY.value.roundToInt()) }
                .onSizeChanged {
                    panelW = it.width.toFloat()
                    panelH = it.height.toFloat()
                    if (!loaded) {
                        loaded = true
                        // 还原上次记忆：side(0=右,1=左) + 纵向比例 vf(0=顶,1=底)
                        val side = prefs.getInt("link_panel_side", 0)
                        val vf = prefs.getFloat("link_panel_vf", 0.5f).coerceIn(0f, 1f)
                        val (leftTargetX, bounds) = computeBounds(maxW, maxH)
                        val (yMin, yMax) = bounds
                        offsetX = if (side == 1) leftTargetX else 0f
                        offsetY = yMin + (yMax - yMin) * vf
                    }
                }
                .pointerInput(maxW, maxH, panelW) {
                    detectDragGestures(
                        onDragEnd = {
                            // 松手：按当前水平中心决定吸附到左/右竖边；纵向保留并夹紧屏幕内，
                            // 然后持久化 side + 纵向比例，下次进入还原
                            val centerX = (maxW - rightPadPx - panelW / 2f) + offsetX
                            val side = if (centerX > maxW / 2f) 0 else 1
                            val (leftTargetX, bounds) = computeBounds(maxW, maxH)
                            val (yMin, yMax) = bounds
                            offsetX = if (side == 0) 0f else leftTargetX
                            offsetY = offsetY.coerceIn(yMin, yMax)
                            val vf = ((offsetY - yMin) / (yMax - yMin)).coerceIn(0f, 1f)
                            prefs.edit()
                                .putInt("link_panel_side", side)
                                .putFloat("link_panel_vf", vf)
                                .apply()
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            val (_, bounds) = computeBounds(maxW, maxH)
                            val (yMin, yMax) = bounds
                            offsetY = (offsetY + dragAmount.y).coerceIn(yMin, yMax)
                        }
                    )
                }
        ) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.width(46.dp)
            ) {
                Column(
                    Modifier.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = "主页",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 主页 与 其余操作之间用细分隔线区分
                    if (showZoomReset || showSidebar) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                    if (showZoomReset) {
                        IconButton(onClick = onZoomReset, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Default.ZoomOut,
                                contentDescription = "缩放复位",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    if (showSidebar) {
                        IconButton(onClick = onToggleSidebar, modifier = Modifier.size(40.dp)) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "侧边栏",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
