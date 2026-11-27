package com.grape.player.util

import android.graphics.BitmapFactory
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 封面主色提取 —— 对应 Web 版的「封面主色实时提取并驱动全站背景与强调色」。
 *
 * 实现要点（对齐 Web 版的取舍）：
 * - 整个下载/解码/Palette 计算都在 IO 线程，不抢音频首字节的主线程时间；
 * - 调用方负责延后触发（切歌稳定后再取色），这里只做纯计算；
 * - 只取封面的一小块降采样位图，避免大图 OOM；
 * - 返回 ARGB Int，交给动态主题生成 ColorScheme。
 */
object CoverColorExtractor {
    // 复用全局连接池（newBuilder 共享底层连接池与线程池，只覆盖超时），
    // 取色请求与播放/扫描的 TLS 连接可以复用，不再各养一套握手
    private val client = com.grape.player.network.HttpClients.base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 封面过大时直接放弃，避免为取色下载几十 MB 的原图 */
    private const val MAX_COVER_BYTES = 3L * 1024 * 1024

    suspend fun extract(coverUrl: String): Int? = withContext(Dispatchers.IO) {
        if (coverUrl.isBlank()) return@withContext null
        try {
            val bytes: ByteArray = if (coverUrl.startsWith("file:") || coverUrl.startsWith("/")) {
                // 本地封面（files/cover/ 下的落盘文件），直接读文件
                val path = if (coverUrl.startsWith("file:"))
                    android.net.Uri.parse(coverUrl).path ?: return@withContext null
                else coverUrl
                val f = java.io.File(path)
                if (!f.exists() || f.length() > MAX_COVER_BYTES) return@withContext null
                f.readBytes()
            } else {
                val req = Request.Builder()
                    .url(coverUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val len = resp.body?.contentLength() ?: -1
                    if (len > MAX_COVER_BYTES) return@withContext null
                    resp.body?.bytes() ?: return@withContext null
                }
            }
            if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return@withContext null

            // 降采样：Palette 只需要颜色分布，1/4 采样足够且省内存
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return@withContext null
            try {
                val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                val swatch = palette.vibrantSwatch
                    ?: palette.dominantSwatch
                    ?: palette.lightVibrantSwatch
                    ?: palette.mutedSwatch
                    ?: palette.darkVibrantSwatch
                swatch?.rgb
            } finally {
                bitmap.recycle()
            }
        } catch (_: Exception) {
            null
        }
    }
}
