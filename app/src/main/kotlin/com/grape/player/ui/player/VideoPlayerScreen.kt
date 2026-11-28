package com.grape.player.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.grape.player.playback.WebDavDataSourceFactory

/**
 * 视频基础播放页 —— 手机端用 ExoPlayer 替代 PotPlayer。
 * 全屏播放，支持手势进度/音量（PlayerView 默认提供），系统返回键退出。
 */
@Composable
fun VideoPlayerScreen(
    url: String,
    title: String = "",
    onBack: () -> Unit,
) {
    val context = LocalContext.current

    // 只在 remember 里构建 player 实例；setMediaItem/prepare 这类重活
    // 延后到组合完成后的协程里执行，避免在组合期（主线程）直接 prepare 造成进页掉帧。
    val player = remember(url) {
        ExoPlayer.Builder(context)
            // 视频不进歌曲缓存：整部片子塞进 Cache 只会挤掉歌曲
            .setMediaSourceFactory(DefaultMediaSourceFactory(WebDavDataSourceFactory(enableCache = false)))
            .build()
    }

    LaunchedEffect(player, url) {
        player.setMediaItem(
            MediaItem.Builder().setUri(url).setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder().setTitle(title).build()
            ).build()
        )
        player.playWhenReady = true
        player.prepare()
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = true
                setShowNextButton(false)
                setShowPreviousButton(false)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
