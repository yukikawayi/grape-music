package com.grape.player.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 列表右侧的浮层快速滑块（LazyColumn / LazyVerticalGrid 各一个入口）。
 *
 * 用途：内容超过一屏时，右侧出现一条细滑块，按住往下拖就能直落列表任意位置（拖到底 = 页尾），
 * 不必一屏一屏地滑。
 *
 * 几个刻意的设计：
 *  - **只有超出一屏才出现**：`totalItems <= visibleCount` 直接不画，短列表不会多出一条灰条；
 *  - **触控带宽 14dp、滑块只有 4dp**：视觉上细，手指却按得住。列表行左右各有 16dp 内边距，
 *    14dp 的带只压在行尾的空白上，不会盖住「⋮」这类行尾按钮；
 *  - **点亮即跳 + 按住连续拖**：不必先精确按到滑块上再拖（老式滚动条的通病是滑不到）；
 *  - **手势在带内全量 consume**：绝不穿透到下面的歌曲行，避免「想滑到页尾却播放了某首歌」；
 *  - **进度按「可视区行数」归一化**：`scrolled / (total - visible)`，所以滑到底时滑块正好贴底，
 *    不会因为扣掉一屏高度而停在半路；
 *  - **拖动时滑块直接跟手**：列表 seek 有延迟（要等下一帧测量），如果只靠列表回读进度，
 *    手指已经拖到底、滑块还在半路，观感像「卡住了」。松手才回落到列表的真实进度。
 *
 * 滑块位置只在本组件内部读 [LazyListState.layoutInfo] / [LazyGridState.layoutInfo]，
 * 调用方（整页）不会因为列表滚动而整页重组。
 */
private val BAR_TOUCH_WIDTH = 14.dp
private val THUMB_WIDTH = 4.dp
private val THUMB_MIN_HEIGHT = 34.dp

/** 纵向列表（LazyColumn）的快速滑块 */
@Composable
fun FastListScrollBar(state: LazyListState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    // 注意：不能用 visibleItemsInfo.first()！列表带 stickyHeader 时，固定在顶部的表头会被
    // 塞进 visibleItemsInfo（index 恒为 0、offset 恒为 0），取 first 会永远拿到那个表头，
    // 进度就永远算成 0（现象：列表跟着滚，滑块纹丝不动）。
    // firstVisibleItemIndex / firstVisibleItemScrollOffset 不受 stickyHeader 影响。
    val firstIndex = state.firstVisibleItemIndex
    FastScrollBarImpl(
        totalItems = info.totalItemsCount,
        visibleCount = info.visibleItemsInfo.count { it.index >= firstIndex },
        firstIndex = firstIndex,
        firstScrollOffset = state.firstVisibleItemScrollOffset,
        firstItemSize = firstItemSize(info, firstIndex),
        viewportHeight = info.viewportEndOffset - info.viewportStartOffset,
        onSeek = { state.scrollToItem(it) },
        modifier = modifier,
    )
}

/** 网格（LazyVerticalGrid）的快速滑块 */
@Composable
fun FastGridScrollBar(state: LazyGridState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val firstIndex = state.firstVisibleItemIndex
    FastScrollBarImpl(
        totalItems = info.totalItemsCount,
        visibleCount = info.visibleItemsInfo.count { it.index >= firstIndex },
        firstIndex = firstIndex,
        firstScrollOffset = state.firstVisibleItemScrollOffset,
        // 网格用格子高度：和列表同一套「滚过了多少行」的换算
        firstItemSize = info.visibleItemsInfo.firstOrNull { it.index == firstIndex }?.size?.height
            ?: info.visibleItemsInfo.lastOrNull()?.size?.height ?: 0,
        viewportHeight = info.viewportEndOffset - info.viewportStartOffset,
        onSeek = { state.scrollToItem(it) },
        modifier = modifier,
    )
}

/** 第一可见项的高度（找不到就退到最后一个可见项，行高基本一致） */
private fun firstItemSize(info: LazyListLayoutInfo, firstIndex: Int): Int =
    info.visibleItemsInfo.firstOrNull { it.index == firstIndex }?.size
        ?: info.visibleItemsInfo.lastOrNull()?.size ?: 0

@Composable
private fun FastScrollBarImpl(
    totalItems: Int,
    visibleCount: Int,
    firstIndex: Int,
    /** 第一可见项被滚出视口顶部的像素数（>=0），用来让滑块随像素级滑动小幅前进 */
    firstScrollOffset: Int,
    firstItemSize: Int,
    viewportHeight: Int,
    onSeek: suspend (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 一屏装得下（或还没布局、空列表）：不画
    if (totalItems <= visibleCount || visibleCount <= 0 || viewportHeight <= 0) return

    var dragging by remember { mutableStateOf(false) }
    // 拖动中滑块跟手用的比例；松手置空，回到列表真实进度
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    // onSeek 每次重组都是新 lambda（闭包捕获了新的 state），用 rememberUpdatedState 保证
    // 手势里拿到的一定是最新的那个，而不是手势开始时的旧引用
    val seekLatest by rememberUpdatedState(onSeek)

    val scope = rememberCoroutineScope()
    // 同一次拖动会连续触发 seek：只保留最后一个，避免几十个 scrollToItem 抢同一帧
    val seekJob = remember { arrayOfNulls<Job>(1) }

    val density = LocalDensity.current
    val thumbWidthPx = with(density) { THUMB_WIDTH.toPx() }
    val edgePaddingPx = with(density) { 2.dp.toPx() }
    val minThumbPx = with(density) { THUMB_MIN_HEIGHT.toPx() }.coerceAtMost(viewportHeight.toFloat())

    // 可滚动行程 = 总行数 - 一屏行数；滑到页尾时正好 1.0，滑块贴底
    val scrollableRows = (totalItems - visibleCount).coerceAtLeast(1)
    // 第一可见行滚出视口顶部的比例（0~1），让滑块随像素级滑动小幅前进，不一格一格跳
    val withinFirst = if (firstItemSize > 0) {
        (firstScrollOffset.toFloat() / firstItemSize).coerceIn(0f, 1f)
    } else 0f
    val listProgress = ((firstIndex + withinFirst) / scrollableRows).coerceIn(0f, 1f)
    // 拖动时以手指为准，松手后回到列表真实进度
    val progress = dragFraction ?: listProgress

    // 滑块高度按「一屏占全部内容的比例」缩放，太短就抬到最小高度（大列表也按得住）
    val thumbHeightPx = (viewportHeight.toFloat() * visibleCount / totalItems)
        .coerceIn(minThumbPx, viewportHeight.toFloat())
    val thumbTopPx = (viewportHeight - thumbHeightPx) * progress

    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(
        alpha = if (dragging) 0.55f else 0.28f,
    )

    Canvas(
        modifier = modifier
            .width(BAR_TOUCH_WIDTH)
            .fillMaxHeight()
            // 只按总行数重启手势处理：拖拽过程中列表在滚、状态一直在变，不能用那些值做 key，
            // 否则一拖就重启 pointerInput，手指还没抬起来手势就断了
            .pointerInput(totalItems) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 按下就消费：下面（行尾的「⋮」等）不会同时收到这次点击
                    down.consume()

                    fun seekToY(y: Float) {
                        val h = size.height
                        if (h <= 0 || totalItems <= 1) return
                        val fraction = (y / h).coerceIn(0f, 1f)
                        // 滑块立刻跟到手指处，不等列表测量
                        dragFraction = fraction
                        val target = (fraction * (totalItems - 1)).roundToInt()
                        seekJob[0]?.cancel()
                        seekJob[0] = scope.launch { seekLatest(target) }
                    }

                    dragging = true
                    try {
                        seekToY(down.position.y)
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) break
                            seekToY(change.position.y)
                        }
                    } finally {
                        // 手指被系统手势/取消打断时也要复位，否则滑块会卡在按下态、透明度不回
                        dragging = false
                        dragFraction = null
                    }
                }
            },
    ) {
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(
                x = size.width - thumbWidthPx - edgePaddingPx,
                y = thumbTopPx,
            ),
            size = Size(thumbWidthPx, thumbHeightPx),
            cornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f),
        )
    }
}
