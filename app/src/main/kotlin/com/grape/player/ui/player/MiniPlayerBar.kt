package com.grape.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.grape.player.ui.AppViewModel

/**
 * 底部迷你播放栏 —— 固定在四个导航选项上方，所有页面常驻。
 * 有当前曲目时显示封面 / 标题 / 艺人 + 播放暂停 / 下一首 + 细进度条，点击整栏进入全屏播放页；
 * 没有当前曲目时保留一条矮占位条（点击去音乐库选歌），不再整块消失导致布局跳动。
 */
@Composable
fun MiniPlayerBar(
    vm: AppViewModel,
    onOpenPlayer: () -> Unit,
    onBrowseLibrary: () -> Unit = onOpenPlayer,
    modifier: Modifier = Modifier,
) {
    val state by vm.player.state.collectAsState()

    /**
     * 当前曲目 —— 必须以「切歌」为依赖在**本函数体里**解析出来，不能只写
     * `val current = vm.player.currentItem()`。
     *
     * 原因：`currentItem()` 内部读的 `_state.value` 是普通 StateFlow，不参与 Compose 快照，
     * 函数体本身没有任何 state 依赖。原来 state 只在下方 `Column` 的 content lambda
     * （进度条那行 `state.durationMs`）和 IconButton 的 content lambda 里被读，
     * Compose 的失效范围就只到这些子作用域：函数体不重组 → 子 lambda 重组时用的
     * 仍是上次闭包捕获的旧 `current`（`state` 是委托对象，lambda 里每次读都是新值）。
     *
     * 症状正好是这个 bug：进度条在跑（子 lambda 里直接读 state）、歌名/艺术家/封面
     * 定格在上一首；而播放页在 body 里读了 `state.currentIndex`，所以显示正常。
     *
     * 用 derivedStateOf 只对 currentIndex / queueVersion 敏感，避免 250ms 的进度刷新
     * 把整栏带着重组；queueVersion 也参与 key，换队列/元数据编辑后能立刻重取。
     */
    val trackKey by remember { derivedStateOf { state.currentIndex to state.queueVersion } }
    val current = remember(trackKey) { vm.player.currentItem() }

    // 【诊断】只在曲目真正变化时打一行，便于对照「迷你栏看到的」和「播放器实际的」是否一致。
    // 不用 SideEffect（每次重组都打，250ms 一条刷满日志）；LaunchedEffect 以 trackKey 为 key，
    // 一次切歌只打一条。
    androidx.compose.runtime.LaunchedEffect(trackKey) {
        android.util.Log.i(
            "PlayerCtl",
            "minibar: idx=${state.currentIndex} qv=${state.queueVersion} " +
                "title=${current?.title?.take(24)} url=${current?.url?.takeLast(32)}",
        )
    }

    // 进度刷新不再自建 500ms 轮询：AppViewModel 已有全局 250ms 轮询驱动 state.positionMs，
    // 这里 collect 即跟随刷新（原来两处轮询重复打 MediaController）

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        if (current == null) {
            // 未播放占位：保持同一位置常驻，点击去音乐库
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable(onClick = onBrowseLibrary)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "未播放",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "点此选择歌曲",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clickable(onClick = onOpenPlayer)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 封面缩略图（file:// 或远程 URL，Coil 都能加载）；无封面显示音符占位
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (current.coverUrl.isBlank()) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    } else {
                        AsyncImage(
                            model = current.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        current.title.ifBlank { "未知标题" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.bufferStalled) {
                        // 缓冲停滞提示：BUFFERING 且 15s 没有任何数据推进。
                        // 这里在 Column 内容作用域里读 state（与下方进度条同范围），
                        // 不额外扩大重组面
                        Text(
                            "网络缓慢，正在缓冲…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (state.playbackError != null) {
                        // 播放失败提示（此前完全静默：出错后既不切歌也不出声，用户只能重启）。
                        // 文案直接给 ExoPlayer 的错误码 + message，便于把原因抄回来定位。
                        Text(
                            "播放失败：${state.playbackError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else if (current.artist.isNotBlank()) {
                        Text(
                            current.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(onClick = { vm.player.togglePlayPause() }) {
                    Icon(
                        if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.isPlaying) "暂停" else "播放",
                    )
                }
                IconButton(onClick = { vm.player.next() }) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "下一首")
                }
            }

            // 细进度条：保护必须写在 lambda 内部——progress 是延迟执行的，
            // 语义阶段重读状态时 durationMs 可能已变 0（0/0=NaN），NaN 无法被 coerceIn 拦截，
            // 会传进 ProgressBarRangeInfo 直接闪退
            if (state.durationMs > 0) {
                LinearProgressIndicator(
                    progress = {
                        val d = state.durationMs
                        if (d > 0) (state.positionMs.toFloat() / d.toFloat()).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                )
            }
        }
        }
    }
}
