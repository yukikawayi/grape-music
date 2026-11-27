package com.grape.player.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.grape.player.data.WebDavCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 切歌预热 —— 对应 Web 版 prewarm.ts。
 *
 * 对下一首直链发一个 Range: bytes=0-1MB 的请求，让 OpenList / 网盘侧提前解析直链并把首块拉进缓存，
 * ExoPlayer 随后的真实播放请求能命中该缓存，降低首字节延迟。
 *
 * 设计要点（与 Web 版一致）：
 * - 去重表：同 url 在 TTL 内只预热一次；过期允许重发（长曲目 > TTL 时兜底预热需要）。
 * - 并发上限：快速切歌时不让一堆预热请求抢带宽，超出排队 FIFO。
 * - 延时合并：延时期间同 url 的重复请求合并。
 */
object WarmTracker {
    private const val WARM_TTL_MS = 6 * 60 * 1000L
    private const val MAX_INFLIGHT = 2
    private const val WARM_BYTES = 1024 * 1024L

    // 复用全局连接池（newBuilder 共享底层连接池，只覆盖超时）；
    // 预热请求和随后的 ExoPlayer 真实播放请求在传输层也更亲近
    private val client = HttpClients.base.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmedAt = HashMap<String, Long>()
    private val pending = HashMap<String, kotlinx.coroutines.Job>()
    private val queue = ArrayDeque<String>()
    private var inflight = 0

    /** 请求预热一首歌。delayMs=0 立即发出；延时期间同 url 重复请求合并。 */
    fun warm(url: String?, delayMs: Long = 0) {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        // 只预热网络直链，file:// 之类的本地 URI 交给系统
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return
        synchronized(this) {
            if (isWarmLocked(trimmed) || pending.containsKey(trimmed)) return
            if (delayMs <= 0) {
                sendLocked(trimmed)
            } else {
                pending[trimmed] = scope.launch {
                    delay(delayMs)
                    synchronized(this@WarmTracker) {
                        pending.remove(trimmed)
                        sendLocked(trimmed)
                    }
                }
            }
        }
    }

    /** 取消所有尚未发出的延时预热 */
    fun cancelPending() {
        synchronized(this) {
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    private fun isWarmLocked(url: String): Boolean {
        val at = warmedAt[url] ?: return false
        if (System.currentTimeMillis() - at > WARM_TTL_MS) {
            warmedAt.remove(url)
            return false
        }
        return true
    }

    private fun sendLocked(url: String) {
        if (inflight >= MAX_INFLIGHT) {
            queue.addLast(url)
            return
        }
        inflight++
        // 先记录再发，避免飞行中被重复排入
        warmedAt[url] = System.currentTimeMillis()
        scope.launch {
            var ok = false
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-$WARM_BYTES")
                    .header("User-Agent", "Mozilla/5.0")
                    // WebDAV 直链需要 Basic 认证，否则预热请求 401 白跑
                    .apply { WebDavCredentials.authHeader()?.let { header("Authorization", it) } }
                    .build()
                client.newCall(req).execute().use { resp ->
                    ok = resp.isSuccessful || resp.code == 206
                    // 只要响应头，不读 body；use 关闭时自动取消连接
                }
            } catch (_: Exception) {
                ok = false
            } finally {
                synchronized(this@WarmTracker) {
                    if (!ok) warmedAt.remove(url)
                    inflight--
                    val next = queue.removeFirstOrNull()
                    if (next != null) sendLocked(next)
                }
            }
        }
    }
}
