package com.grape.player.playback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.math.min

/**
 * 通知栏 / 锁屏封面加载器 —— Media3 的 MediaSession 默认不加载 artworkUri，
 * 需要我们提供 BitmapLoader 才会把远端封面渲染到通知栏。
 *
 * 本版本 BitmapLoader 是三段式接口：
 *  - [supportsMimeType] 声明支持的图片类型；
 *  - [loadBitmap] 下载_uri_对应的字节；
 *  - [decodeBitmap] 把字节解码为 Bitmap（等比缩放到最大边 512px，通知栏无需原图）。
 * 下载用 OkHttp 异步 enqueue，通过 SettableFuture 回传，不阻塞 MediaSession 线程。
 */
class CoverBitmapLoader : BitmapLoader {

    // 共享连接池：通知栏封面与库内其他请求复用 keep-alive 连接
    private val client = com.grape.player.network.HttpClients.base

    override fun supportsMimeType(mimeType: String): Boolean =
        mimeType.startsWith("image/")

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val scheme = uri.scheme ?: ""
        if (scheme == "file") {
            // 本地落盘封面（files/cover/ 下的内嵌或已下载封面），直接读文件解码
            try {
                val bytes = java.io.File(uri.path ?: "").readBytes()
                future.set(decode(bytes))
            } catch (e: Exception) {
                future.setException(e)
            }
            return future
        }
        if (scheme != "http" && scheme != "https") {
            future.setException(IOException("无有效封面地址: $uri"))
            return future
        }
        val request = Request.Builder()
            .url(uri.toString())
            // 远程封面直链可能需要 WebDAV Basic 认证（多源：按 url 匹配对应源的凭据）
            .apply {
                com.grape.player.data.WebDavCredentials.authHeaderFor(uri.toString())
                    ?.let { header("Authorization", it) }
            }
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                future.setException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        future.setException(IOException("封面 HTTP ${resp.code}"))
                        return
                    }
                    val bytes = resp.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        future.setException(IOException("封面为空"))
                        return
                    }
                    try {
                        future.set(decode(bytes))
                    } catch (e: Exception) {
                        future.setException(e)
                    }
                }
            }
        })
        return future
    }

    override fun decodeBitmap(bytes: ByteArray): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        try {
            future.set(decode(bytes))
        } catch (e: Exception) {
            future.setException(e)
        }
        return future
    }

    /** 解码并等比缩放，控制通知栏封面内存占用 */
    private fun decode(bytes: ByteArray): Bitmap {
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("封面解码失败")
        val maxSide = 512
        val scale = min(maxSide.toFloat() / raw.width, maxSide.toFloat() / raw.height)
        if (scale < 1f) {
            val scaled = Bitmap.createScaledBitmap(
                raw,
                (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1),
                true,
            )
            raw.recycle()
            return scaled
        }
        return raw
    }
}
