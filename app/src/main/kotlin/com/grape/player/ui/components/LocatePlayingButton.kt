package com.grape.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 悬浮「定位到正在播放」按钮。
 *
 * 出现条件：列表超出一屏（还能继续滚）。一屏装得下时列表本来就没有可滚的余地，
 * 点它没有任何意义，所以干脆不占屏幕 —— 判定用 [LazyListState.canScrollForward] /
 * [canScrollBackward]，与右侧快速滑块同一套「超出一屏才出现」的语义，但不会跟着
 * 每一帧滚动反复重组（这两个值只在「能不能滚」翻转时才变）。
 *
 * 点击：把列表滚到当前播放曲目所在行，并让它落在视口上方约 1/3 处 —— 上面留几行
 * 上下文，一眼能看出「它前后还有歌」，比贴到顶部更像"定位"而不是"翻页"。
 *
 * 本页没有这首歌（不在当前歌单 / 分组 / 搜索结果里）时**不做任何事**，也不提示：
 * 用户在多处列表间跳转时，静默失效比弹一句「没有找到」更不打扰。
 *
 * 位置由调用方给：[Modifier] 里自行 align 到右下角并留出与底部播放栏的距离，
 * 这样宽屏 / 窄屏、有没有底栏都跟着各自布局走。
 *
 * @param indexOf url → 该曲目在其 LazyColumn 里的**项序号**（含分组标题等非歌曲项的偏移）；
 *   不在本页返回 null。各页的列表结构不同（分组标题 / 碟分隔 / 搜索分区），
 *   偏移只能在页面侧算，所以这里只要一个回调。
 */
@Composable
fun LocatePlayingButton(
    listState: LazyListState,
    playingUrl: String?,
    indexOf: (String) -> Int?,
    modifier: Modifier = Modifier,
) {
    // 一屏装得下（或还没布局）：不出现
    if (!listState.canScrollForward && !listState.canScrollBackward) return

    val scope = rememberCoroutineScope()

    Surface(
        modifier = modifier.size(40.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    val url = playingUrl ?: return@clickable
                    val index = indexOf(url) ?: return@clickable
                    val info = listState.layoutInfo
                    val viewport = info.viewportEndOffset - info.viewportStartOffset
                    // 负偏移 = 把目标行往下推，让它停在视口上方 1/3 而不是贴着顶边
                    val offset = -(viewport / 3)
                    scope.launch {
                        runCatching { listState.animateScrollToItem(index, offset) }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MyLocation,
                contentDescription = "定位到正在播放",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
