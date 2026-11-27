package com.grape.player.playback

import android.content.Context
import android.os.Handler
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * 自定义 RenderersFactory —— 把响度归一化 AudioProcessor 注入音频渲染链。
 * 通过 DefaultAudioSink 的 audioProcessors 让 PCM 数据经过响度测量与增益处理。
 */
class LoudnessRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        val processors: Array<AudioProcessor> = arrayOf(LoudnessAudioProcessor())
        val loudnessSink: AudioSink = DefaultAudioSink.Builder(context)
            .setAudioProcessors(processors)
            .build()
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            loudnessSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}
