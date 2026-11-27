package com.grape.player.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File

/**
 * 内嵌标签读取 —— 对应原后端 music-metadata 的部分。
 * JAudiotagger 支持 ID3v1/v2（MP3）、FLAC、MP4/M4A、OGG、WAV 等。
 */
object TagReader {

    data class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val year: Int = 0,
        /** 音轨号（0 = 标签里没有） */
        val trackNumber: Int = 0,
        /** 碟号（0 = 标签里没有） */
        val discNumber: Int = 0,
        val durationMs: Long = 0,
        val lyric: String = "",
        val replayGainGain: Float = 0f,
        val replayGainPeak: Float = 0f,
        /** 内嵌封面原图字节（jpg/png/webp），空表示无内嵌封面 */
        val artwork: ByteArray = ByteArray(0),
    )

    suspend fun read(file: File): Tags? = withContext(Dispatchers.IO) {
        try {
            val audioFile = AudioFileIO.read(file)
            val tag: Tag? = audioFile.tag
            val header = audioFile.audioHeader
            val duration = (header.trackLength * 1000).toLong()

            fun field(key: FieldKey): String = try {
                tag?.getFirst(key)?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }

            val lyric = field(FieldKey.LYRICS).ifEmpty {
                // 部分文件用 UNSYNC_LYRICS 或自定义帧
                try {
                    tag?.getFirst("TXXX:LYRICS")?.trim().orEmpty()
                } catch (_: Exception) { "" }
            }

            val rgGain = parseReplayGainGain(tag)
            val rgPeak = parseReplayGainPeak(tag)
            val artwork = try {
                tag?.firstArtwork?.binaryData ?: ByteArray(0)
            } catch (_: Exception) {
                ByteArray(0)
            }

            Tags(
                title = field(FieldKey.TITLE),
                artist = field(FieldKey.ARTIST),
                album = field(FieldKey.ALBUM),
                year = field(FieldKey.YEAR).toIntOrNull() ?: 0,
                // TRACK / DISC_NO 常写作 "3" 或 "3/10"，取前导数字
                trackNumber = field(FieldKey.TRACK).takeWhile { it.isDigit() }.toIntOrNull() ?: 0,
                discNumber = field(FieldKey.DISC_NO).takeWhile { it.isDigit() }.toIntOrNull() ?: 0,
                durationMs = duration,
                lyric = lyric,
                replayGainGain = rgGain,
                replayGainPeak = rgPeak,
                artwork = artwork,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseReplayGainGain(tag: Tag?): Float {
        if (tag == null) return 0f
        for (key in listOf("REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_ALBUM_GAIN")) {
            try {
                val raw = tag.getFirst(key) ?: continue
                val value = raw.replace("dB", "", ignoreCase = true).trim().toFloatOrNull() ?: continue
                return value
            } catch (_: Exception) { /* 继续尝试下一个 */ }
        }
        return 0f
    }

    private fun parseReplayGainPeak(tag: Tag?): Float {
        if (tag == null) return 0f
        for (key in listOf("REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_ALBUM_PEAK")) {
            try {
                val raw = tag.getFirst(key) ?: continue
                return raw.trim().toFloatOrNull() ?: continue
            } catch (_: Exception) { /* 继续尝试下一个 */ }
        }
        return 0f
    }
}
