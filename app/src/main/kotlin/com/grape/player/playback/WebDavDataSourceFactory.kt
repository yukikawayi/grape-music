package com.grape.player.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.grape.player.data.WebDavCredentials

/**
 * ExoPlayer 数据源工厂 —— 给每个网络请求注入 WebDAV 的 Basic 认证头。
 *
 * 纯 WebDAV 方案的核心件：库里存的是 http://host:5244/dav/<路径>，
 * 直接请求会 401，必须在 DataSource 层统一补 Authorization。
 * 凭据在 createDataSource() 时现取，所以改密码后下一次请求即生效。
 */
class WebDavDataSourceFactory : DataSource.Factory {

    override fun createDataSource(): DataSource {
        val factory = DefaultHttpDataSource.Factory()
            .setUserAgent("WebDAVPlayer/1.0 (Android)")
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setAllowCrossProtocolRedirects(true)

        WebDavCredentials.authHeader()?.let { auth ->
            factory.setDefaultRequestProperties(mapOf("Authorization" to auth))
        }
        return factory.createDataSource()
    }
}
