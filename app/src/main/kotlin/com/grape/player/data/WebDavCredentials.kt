package com.grape.player.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import okhttp3.Credentials

/**
 * 全局 WebDAV 凭据（进程内单例）。
 *
 * 为什么要单例：ExoPlayer 跑在 PlaybackService 里，MediaController 无法给单个媒体项附加请求头，
 * 所以认证头只能在 DataSource 创建那一刻注入。DataSource 拿不到 Activity / ViewModel，
 * 只能读这个由 SettingsManager 同步过来的快照。
 *
 * 全部字段 @Volatile：设置页保存后立刻生效，不需要重启播放器或重建 ExoPlayer。
 */
object WebDavCredentials {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var rawUrl: String = ""
    @Volatile private var user: String = ""
    @Volatile private var pass: String = ""
    @Volatile private var loaded: Boolean = false

    /** 在 Application 启动时调用一次：先同步读一遍（避免首播抢跑），再持续跟随设置变化 */
    fun attach(settings: SettingsManager) {
        if (!loaded) {
            try {
                runBlocking {
                    val u = settings.webdavUrl.first()
                    val n = settings.webdavUser.first()
                    val p = settings.webdavPass.first()
                    update(u, n, p)
                }
            } catch (_: Exception) {
                // 读失败不阻塞启动，后续 collect 会补上
            }
        }
        scope.launch {
            combine(
                settings.webdavUrl,
                settings.webdavUser,
                settings.webdavPass,
            ) { u, n, p -> Triple(u, n, p) }.collect { (u, n, p) -> update(u, n, p) }
        }
    }

    /** 设置页保存后立即生效 */
    fun update(url: String, username: String, password: String) {
        rawUrl = url.trim()
        user = username
        pass = password
        loaded = true
    }

    /**
     * WebDAV 根地址，统一以 /dav 结尾。
     * 兼容用户填 http://127.0.0.1:5244、http://127.0.0.1:5244/、.../dav、.../dav/ 四种写法。
     */
    fun davBase(): String = normalizeDavBase(rawUrl)

    /** Basic 认证头；用户名为空时不发头（适用于允许匿名读取的 OpenList） */
    fun authHeader(): String? = if (user.isNotEmpty()) Credentials.basic(user, pass) else null

    fun hasCredentials(): Boolean = loaded

    private fun normalizeDavBase(url: String): String {
        var base = url.trim()
        if (base.isEmpty()) return ""
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) return ""
        base = base.trimEnd('/')
        return if (base.endsWith("/dav", ignoreCase = true)) base else "$base/dav"
    }
}
