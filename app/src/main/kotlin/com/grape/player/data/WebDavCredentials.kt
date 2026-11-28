package com.grape.player.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials

/**
 * 全局 WebDAV 凭据（进程内单例）。
 *
 * 为什么要单例：ExoPlayer 跑在 PlaybackService 里，MediaController 无法给单个媒体项附加请求头，
 * 所以认证头只能在 DataSource 创建那一刻注入。DataSource 拿不到 Activity / ViewModel，
 * 只能读这个由 SettingsManager 同步过来的快照。
 *
 * 多源之后不再只有一份凭据：按请求 url 去匹配「哪个源的 davBase 是它的前缀」，
 * 取最长匹配（同一台机器上挂了多个前缀时也不会串）。匹配不到就退化成单源时的行为 ——
 * 只有配置了一个源时直接用它，多个源时宁可不发认证头（免得把 A 的账号密码发给 B）。
 */
object WebDavCredentials {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var sources: List<WebDavSource> = emptyList()
    @Volatile private var loaded: Boolean = false

    /** 在 Application 启动时调用一次：先同步读一遍（避免首播抢跑），再持续跟随设置变化 */
    fun attach(settings: SettingsManager) {
        if (!loaded) {
            try {
                runBlocking { update(settings.webDavSources.first()) }
            } catch (_: Exception) {
                // 读失败不阻塞启动，后续 collect 会补上
            }
        }
        scope.launch {
            settings.webDavSources.collect { update(it) }
        }
    }

    /** 设置页保存后立即生效 */
    fun update(list: List<WebDavSource>) {
        sources = list
        loaded = true
    }

    /**
     * 给某个请求挑认证头：
     *  - 命中某个源的 davBase 前缀（取最长）→ 用这个源的账号；
     *  - 没命中且只配了一个源 → 用那个源（兼容用户填的地址和库里存的直链不完全一致的老情况）；
     *  - 其余情况返回 null，不发认证头（允许匿名读取的 OpenList 也能正常播）。
     */
    fun authHeaderFor(url: String): String? {
        val src = matchSource(url) ?: return null
        if (src.user.isEmpty()) return null
        return Credentials.basic(src.user, src.pass)
    }

    /** 不带 url 时的兜底：第一个源的认证头（通知栏封面、预热等没有精确 url 的场合慎用） */
    fun authHeader(): String? = sources.firstOrNull()?.let { src ->
        if (src.user.isEmpty()) null else Credentials.basic(src.user, src.pass)
    }

    /** WebDAV 根地址（第一个源），统一以 /dav 结尾 */
    fun davBase(): String = sources.firstOrNull()?.davBase().orEmpty()

    fun hasCredentials(): Boolean = loaded

    private fun matchSource(url: String): WebDavSource? {
        if (url.isBlank()) return sources.firstOrNull()
        val invalid = sources.filter { it.isValid }
        if (invalid.isEmpty()) return null
        var best: WebDavSource? = null
        var bestLen = -1
        for (src in invalid) {
            val base = src.davBase()
            if (base.isNotEmpty() && url.startsWith(base) && base.length > bestLen) {
                best = src
                bestLen = base.length
            }
        }
        if (best != null) return best
        // 库里存的直链可能是老地址（换域名 / 换端口前扫进来的），单源时仍然按它认证
        return if (invalid.size == 1) invalid.first() else null
    }
}
