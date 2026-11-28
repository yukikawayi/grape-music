package com.grape.player.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import com.grape.player.data.MediaCache
import com.grape.player.data.WebDavCredentials

/**
 * ExoPlayer 数据源工厂 —— 给每个网络请求注入对应 WebDAV 源的 Basic 认证头。
 *
 * 纯 WebDAV 方案的核心件：库里存的是 http://host:5244/dav/<路径>，
 * 直接请求会 401，必须在 DataSource 层统一补 Authorization。
 *
 * 多源之后认证头不能在建工厂时定死（同一个工厂要服务多个源的直链），
 * 所以建 HTTP 源的动作推到 [WebDavHttpDataSource.open] 里 —— 那时才拿得到 url，
 * 再按 url 去匹配属于哪个源（见 [WebDavCredentials.authHeaderFor]）。
 *
 * [enableCache] 为 true 时再套一层 media3 的 [CacheDataSource]（音频播放走这条）：
 * span 级缓存，读到哪存到哪、seek 命中已有分片、缺段才回网络。视频页传 false ——
 * 一整部片子几十 GB，塞进歌曲缓存目录既超上限又毫无意义。
 */
class WebDavDataSourceFactory(
    private val enableCache: Boolean = false,
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val http = WebDavHttpDataSource()
        // 上限为 0（用户关了缓存）时直接走网络，连缓存实例都不建
        if (!enableCache || !MediaCache.isEnabled()) return http
        val cache = MediaCache.get() ?: return http
        return CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory { WebDavHttpDataSource() }
            // 缓存读坏了就当没有，继续走网络 —— 不能因为缓存故障让歌放不出来
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()
    }
}

/**
 * 按 url 现取凭据的 HTTP 数据源：每次 open 建一个 DefaultHttpDataSource（带上这个 url 所属源的认证头）。
 *
 * 为什么不复用同一个 DefaultHttpDataSource 实例：认证头是构造时的默认请求属性，
 * 而同一个工厂要同时服务多个源（A 源的歌、B 源的封面），定死就会串号。
 */
internal class WebDavHttpDataSource : DataSource {

    private var delegate: DataSource? = null
    private val listeners = mutableListOf<TransferListener>()

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent("WebDAVPlayer/1.0 (Android)")
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

        WebDavCredentials.authHeaderFor(dataSpec.uri.toString())?.let { auth ->
            factory.setDefaultRequestProperties(mapOf("Authorization" to auth))
        }

        val source = factory.createDataSource()
        listeners.forEach { source.addTransferListener(it) }
        delegate = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: -1

    override fun close() {
        try {
            delegate?.close()
        } finally {
            delegate = null
        }
    }

    override fun getUri(): Uri? = delegate?.uri
}
