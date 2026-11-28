package com.grape.player.data

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import android.net.Uri
import java.io.File

/**
 * 歌曲缓存 —— 用 ExoPlayer 自带的 [SimpleCache]（span 级），目录仍是数据根目录下的 `Cache/`
 * （与 `OnlineCovers/` 同级）。
 *
 * 相比上一版「整首文件 + 读完才转正」的自建实现，span 级缓存带来的差别：
 *  - 播放器读到哪就存到哪，**没读完也留着**：下次从断点继续补，不用重下已听过的部分；
 *  - seek 到任意位置都能命中已有分片，缺哪段补哪段；
 *  - 一个 url 对应磁盘上一组 `<uid>.v3.exo` 分片 + `journal` + `index`，
 *    因此目录不再「肉眼可读」（这是换这套方案的代价）。
 *
 * 上限仍是设置页的四挡（0/4/8/16GB），淘汰交给自定义的 [LimitEvictor]：
 * media3 官方的 `LeastRecentlyUsedCacheEvictor` 上限在构造时写死，改设置就得重建缓存实例
 * （播放中做不到），所以这里自己实现一个「上限随时可变、按最近使用时间淘汰」的 evictor。
 */
object MediaCache {

    /** 缓存目录名：与 OnlineCovers / EmbeddedCovers 平级 */
    private const val DIR_NAME = DataDirManager.CACHE_DIR_NAME

    /**
     * 上一版「自建缓存」的文件名（`<24 位 sha1>.<扩展名>`，下载中的再加 `.part`）。
     *
     * 只按这个精确形状清理，绝不再用「目录里有没有某个文件名」来判断整个目录是不是旧格式 ——
     * 之前用 `journal` 判断（以为 SimpleCache 会有这个文件），但 media3 1.8.1 的 SimpleCache
     * 写的是 `cached_content_index.exi` + `<uid>.uid` + 分片目录，根本没有 journal，
     * 于是每次冷启动都走到「清空重建」，缓存全被删掉。
     */
    private val LEGACY_FILE = Regex("^[0-9a-f]{24}(\\.[A-Za-z0-9]{1,5})?(\\.part)?$")

    /** 默认上限 4GB（设置项没写过时的初始值） */
    const val DEFAULT_LIMIT_MB = 4 * 1024

    /** 可选的几挡（MB）：0 = 不缓存 */
    val LIMIT_OPTIONS_MB = listOf(0, 4 * 1024, 8 * 1024, 16 * 1024)

    /**
     * 当前生效的上限（字节，0 = 关闭缓存）。
     * 由 [WebDAVPlayerApp][com.grape.player.WebDAVPlayerApp] 收集设置后写入，
     * 播放线程只读这个 volatile 值，不去碰 DataStore（那里是协程 API，读不了同步值）。
     */
    @Volatile
    var limitBytes: Long = DEFAULT_LIMIT_MB * 1024L * 1024L
        private set

    @Volatile
    private var instance: SimpleCache? = null

    /** 缓存目录（数据根目录还没确定时为 null） */
    fun dir(): File? = DataDirManager.effectiveBase?.let { File(it, DIR_NAME) }

    /** 缓存目录的绝对路径（设置页展示用；还没初始化时给占位文案） */
    fun dirPath(): String = dir()?.absolutePath ?: "启动后自动确定（数据目录/Cache）"

    /** 缓存是否开启（上限 > 0） */
    fun isEnabled(): Boolean = limitBytes > 0L

    /**
     * 取（必要时创建）缓存实例。整个进程只能有一个 [SimpleCache] 指向同一个目录，
     * 所以加锁并缓存实例；创建失败（索引损坏 / 目录不可写）就清空重来，
     * 再失败返回 null —— 缓存是锦上添花，不能因此让播放起不来。
     */
    @Synchronized
    fun get(): SimpleCache? {
        instance?.let { return it }
        val d = dir() ?: return null
        if (!d.exists() && !d.mkdirs()) return null
        // 只删上一版自建缓存留下的文件（精确匹配文件名），SimpleCache 自己的
        // cached_content_index.exi / *.uid / 分片目录一律不动
        d.listFiles()?.forEach { f ->
            if (f.isFile && LEGACY_FILE.matches(f.name)) runCatching { f.delete() }
        }
        return try {
            SimpleCache(d, LimitEvictor()).also { instance = it }
        } catch (e: Exception) {
            // 走到这里说明索引真的坏了（不是"没有某个文件"），此时才整体重建：
            // 缓存是可再生数据，宁可清掉也不能让播放起不来
            android.util.Log.w("MediaCache", "缓存索引异常，清空重建", e)
            d.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
            runCatching { SimpleCache(d, LimitEvictor()) }.getOrNull()?.also { instance = it }
        }
    }

    /** 当前占用字节数（未开启缓存时恒为 0，避免为此创建缓存实例） */
    fun usedBytes(): Long {
        if (limitBytes <= 0L) return 0L
        return runCatching { get()?.cacheSpace ?: 0L }.getOrDefault(0L)
    }

    /**
     * 这个 url 在缓存里有没有任何分片。
     *
     * 用途：切歌预热（WarmTracker）前判断「本地已经有货」，有就不再发那 1MB 的预热请求。
     * 用「有没有分片」而不是「是否整首缓存完整」：只要有一点缓存，首播就会读本地，
     * 预热的意义（抢首块）已经不存在了。
     */
    fun hasCacheFor(uri: Uri): Boolean {
        if (limitBytes <= 0L) return false
        val cache = get() ?: return false
        return runCatching { cache.keys.contains(uri.toString()) }.getOrDefault(false)
    }

    /** 设置变化时调用：0 = 关闭并清空；其余按新上限就地淘汰 */
    fun applyLimit(limitMb: Int) {
        limitBytes = limitMb.coerceAtLeast(0) * 1024L * 1024L
        if (limitBytes <= 0L) clearAll() else trim()
    }

    /** 清空整份缓存（把上限改成 0、或用户点「清空缓存」时调用） */
    fun clearAll() {
        val cache = instance ?: return
        for (key in runCatching { cache.keys }.getOrDefault(emptySet())) {
            // 正在被播放的那一首会加锁，删不掉是正常的，跳过即可
            runCatching { cache.removeResource(key) }
        }
    }

    /**
     * 超出上限就按「最近最久未使用」逐首删除，直到降回上限以内。
     * 调用时机：准备写新的一首之前（[LimitEvictor.onStartFile]）、改了上限之后。
     */
    fun trim(keepKey: String? = null) {
        val cache = instance ?: return
        val limit = limitBytes
        val keys = runCatching { cache.keys }.getOrDefault(emptySet())
        if (limit <= 0L) {
            clearAll()
            return
        }
        if (cache.cacheSpace <= limit) return
        // 每个 key 用「最晚一次触碰时间」代表它的新旧；先删最旧的
        val ordered = keys.filter { it != keepKey }
            .map { key -> key to lastTouchOf(cache, key) }
            .sortedBy { it.second }
        for ((key, _) in ordered) {
            if (cache.cacheSpace <= limit) break
            runCatching { cache.removeResource(key) }
        }
    }

    /** 释放实例并落盘索引（不释放的话下次启动可能丢掉一批缓存记录） */
    @Synchronized
    fun release() {
        runCatching { instance?.release() }
        instance = null
    }

    private fun lastTouchOf(cache: Cache, key: String): Long {
        val spans = runCatching { cache.getCachedSpans(key) }.getOrNull() ?: return 0L
        return spans.maxOfOrNull { it.lastTouchTimestamp } ?: 0L
    }

    /**
     * 上限可变的淘汰器。
     *
     * [requiresCacheSpanTouches] 必须为 true：SimpleCache 才会在每次读取分片时更新
     * `lastTouchTimestamp`，这里的「最久未使用」才是真正的使用时间（否则只能按写入时间淘汰）。
     * 只在 [onStartFile] 里腾空间：那是「准备写入新内容」的唯一时机，写入中途腾会打断当前曲目。
     */
    private class LimitEvictor : CacheEvictor {
        override fun requiresCacheSpanTouches(): Boolean = true

        override fun onCacheInitialized() = Unit

        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            trim(keepKey = key)
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) = Unit

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) = Unit

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) = Unit
    }
}
