package com.grape.player.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import android.app.PendingIntent
import android.content.Intent
import com.grape.player.MainActivity
import com.grape.player.network.MetadataEnricher

/**
 * 播放前台服务 —— 保证切后台/锁屏后继续播放，并提供通知栏、锁屏控制。
 * MediaSessionService 会自动管理前台通知和 MediaSession 生命周期。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this, LoudnessRenderersFactory(this))
            // WebDAV 直链必须带 Basic 认证头，统一在数据源层注入；音频开着本地缓存
            // （命中就本地播放，没命中边下边存，上限见设置页「缓存」）
            .setMediaSourceFactory(DefaultMediaSourceFactory(WebDavDataSourceFactory(enableCache = true)))
            // 自定义缓冲：默认 bufferForPlaybackMs=2500，局域网/云盘直链首播要干等 2.5s 才出声。
            // min/max 缓冲 15s/60s，起播 1s、恢复 2s 即可（局域网带宽充足，无需长缓冲）。
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 15_000,
                        /* maxBufferMs = */ 60_000,
                        /* bufferForPlaybackMs = */ 1_000,
                        /* bufferForPlaybackAfterRebufferMs = */ 2_000,
                    )
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        mediaSession = MediaSession.Builder(this, player)
            // 让通知栏/锁屏能加载远端封面（否则 artworkUri 不会渲染）
            .setBitmapLoader(CoverBitmapLoader())
            // 点击「会话」的去处：通知栏卡片、锁屏控制、系统媒体面板（HyperOS 灵动岛胶囊）
            // 点下去都读这个值。不设的话 Media3 只填一个空的内容意图，点了没反应。
            .setSessionActivity(openAppIntent())
            .build()
    }

    /**
     * 点通知 / 灵动岛回到应用。
     *
     * 用带 ACTION_MAIN + CATEGORY_LAUNCHER 的显式 Intent，等价于点桌面图标：
     * Activity 是 `launchMode="singleTask"`，已有任务会被直接带到前台（走 onNewIntent，
     * 不重建），进程恰好被杀时则冷启动恢复。UI 状态都在 ViewModel 里，不需要额外传参。
     */
    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            // 服务里发起 Activity 必须 NEW_TASK；单任务栈实例用 SINGLE_TOP 复用
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            // 内容不变，允许系统复用同一个 PendingIntent；Android 12+ 必须声明可变性
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /**
     * 用户从最近任务里划掉应用。
     *
     * 此时进程常常因为播放服务还活着而没被杀掉，后台的扫描/元数据补全原本会继续跑。
     * 这里通过 Activity 注册的回调把两者都停掉（各自保留断点标记，下次打开应用续跑）。
     * 正在播放则不 stopSelf，音乐继续 —— 只停「后台取数」这件事。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        (application as? com.grape.player.WebDAVPlayerApp)?.backgroundWorkStopper?.invoke()
        // 兜底：即使没有注册回调（Activity 已销毁），补全也要停
        MetadataEnricher.cancel()
        val playing = mediaSession?.player?.isPlaying == true
        if (!playing) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        // 释放缓存实例：SimpleCache 只在 release 时把索引刷回磁盘，
        // 不刷的话下次启动会认不出这些分片，等于白下一遍
        com.grape.player.data.MediaCache.release()
        super.onDestroy()
    }
}
