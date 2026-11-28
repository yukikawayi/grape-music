package com.grape.player.network

import com.grape.player.data.MediaItem
import com.grape.player.data.Repository
import com.grape.player.util.MediaPathUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 媒体库扫描器 —— 纯 WebDAV 方案，只做「快速建库」。
 *
 * 流程：PROPFIND 递归枚举 OpenList 挂载目录里的音频/视频 → 以 /dav/<路径> 作为播放直链入库。
 * 联网刮削（艺人/专辑/封面）不在这里做，交给 [MetadataEnricher] 在后台慢慢补，
 * 这样点一下扫描几秒钟就能看到整个库。
 *
 * 相比旧方案的额外好处：/dav 路径不带签名、不会过期，因此重扫时 url 稳定，
 * 响度实测值 / 歌词偏移 / 歌单 这些按 url 关联的数据不会因重扫而丢失。
 */
class LibraryScanner(
    private val repo: Repository,
) {
    data class ScanProgress(
        val phase: String = "",
        val current: Int = 0,
        val total: Int = 0,
    )

    data class ScanResult(
        val total: Int,
        val music: Int,
        val video: Int,
        val failed: Int,
        /** 用户中途点了「取消扫描」：只把已枚举到的条目存进库，未扫到的旧条目原样保留 */
        val cancelled: Boolean = false,
        /**
         * 本次写进库的 url 集合。多源扫描时由调用方把各个源的结果并起来，
         * 最后统一做一次「服务器上已删除」清理 —— 否则扫完 A 源就会把 B 源的条目当成多余项删掉。
         */
        val urls: Set<String> = emptySet(),
    )

    suspend fun scan(
        webdavUrl: String,
        user: String,
        pass: String,
        davRoot: String,
        onProgress: (ScanProgress) -> Unit = {},
        /** 软停止信号（用户点「取消扫描」）：命中后停止枚举，把已扫到的条目落库后返回 */
        shouldStop: () -> Boolean = { false },
        /**
         * 扫完是否顺手删掉「服务器上已不存在」的旧行。
         * 多源扫描时必须传 false：单个源只看到自己那部分 url，删差集会误伤其它源，
         * 由调用方在全部源扫完后用全量 url 统一清理。
         */
        deleteStale: Boolean = true,
    ): ScanResult = withContext(Dispatchers.IO) {
        val client = WebDavClient(webdavUrl, user, pass)

        onProgress(ScanProgress("正在连接 WebDAV", 0, 0))
        // 认证失败 / 路径不存在在这里就抛出来，避免用户对着空白进度条干等
        client.verify(davRoot)

        onProgress(ScanProgress("正在枚举目录", 0, 0))
        val files = client.scanMedia(
            root = davRoot,
            onProgress = { dirs, found ->
                onProgress(ScanProgress("已扫描 $dirs 个目录，发现 $found 个媒体文件", 0, 0))
            },
            shouldStop = shouldStop,
        )
        // 枚举阶段就被取消：一个条目都没拿到（或只拿到一部分，见下方 partial 分支）
        val stopEarly = shouldStop()
        if (files.isEmpty()) {
            onProgress(ScanProgress("该目录下没有找到音频或视频", 0, 0))
            return@withContext ScanResult(0, 0, 0, 0, cancelled = stopEarly)
        }

        // 取消态要在建条目之前就定下来（进度文案、写入阶段是否允许中途再中断都用它）
        val cancelBeforeWrite = stopEarly || shouldStop()
        // 旧库按「OpenList 路径」建索引：既复用用户改过的元数据，也顺带把旧签名 url 迁移过来。
        // 多源之后同一个路径可能在两台机器上都存在（/quark/music/01.flac），所以索引里
        // 同时放「host|path」和纯 path 两种键：优先精确匹配同一个 host，匹配不到再退回纯路径
        // （换域名/换端口重扫时，元数据仍然继承得过去，跟单源时代的行为一致）。
        val oldByHostPath = HashMap<String, MediaItem>()
        val oldByPathOnly = HashMap<String, MediaItem>()
        if (repo.count() > 0) {
            for (item in repo.getAllOnce()) {
                val path = pathKeyOf(item.url) ?: continue
                oldByHostPath[hostOf(item.url) + "|" + path] = item
                oldByPathOnly[path] = item
            }
        }
        val host = hostOf(webdavUrl)

        fun prevOf(path: String): MediaItem? =
            oldByHostPath[host + "|" + path] ?: oldByPathOnly[path]

        var failed = 0
        val rebuilt = mutableListOf<MediaItem>()
        val renamedUrls = mutableListOf<Pair<String, String>>()

        for ((idx, entry) in files.withIndex()) {
            // 大库（几千首）在组装阶段也能随时中断，不会因为「枚举完了」就一路写下去
            if (idx % 200 == 0) currentCoroutineContext().ensureActive()
            val url = client.fileUrl(entry.path)
            if (url.isEmpty()) {
                failed++
                continue
            }
            val name = entry.name.ifEmpty { entry.path.substringAfterLast('/') }
            val prev = prevOf(entry.path)
            if (prev != null && prev.url != url) {
                renamedUrls.add(prev.url to url)
            }
            val item = MediaItem(
                url = url,
                title = repairText(prev?.title, MediaPathUtil.titleFromPath(name).ifEmpty { name }),
                artist = repairText(prev?.artist, ""),
                album = repairText(prev?.album, ""),
                year = prev?.year ?: 0,
                durationMs = prev?.durationMs ?: 0,
                sizeBytes = entry.size,
                folder = MediaPathUtil.folderFromPath(entry.path),
                coverUrl = prev?.coverUrl.orEmpty(),
                lyric = prev?.lyric.orEmpty(),
                replayGainGain = prev?.replayGainGain ?: 0f,
                replayGainPeak = prev?.replayGainPeak ?: 0f,
                lufs = prev?.lufs ?: 0f,
                peak = prev?.peak ?: 0f,
                loudnessTarget = prev?.loudnessTarget ?: -16,
                coverage = prev?.coverage ?: 0f,
                isVideo = WebDavClient.isVideo(name),
            )
            // 把旧行里「扫描不会重建」的字段整段继承过来：音轨号 / 碟号 / 来源标记 / 内嵌快照 /
            // 快照读取时间 / 补全失败时间。扫描读不到文件标签，这些只能由补全流程产生：
            //  - 快照若每次重扫都被清空，补全就得把全库几千个文件头重新下一遍，也违背「快照只由清库删除」；
            //  - metaSource 描述的是 Using_ 现有值的来源，而 Using_ 本身就是从旧行继承的，标记当然要跟着继承；
            //  - metaMissAt 是失败退避的时间戳，丢了就等于每次扫描都重新白搜一轮，「永远补不上」的歌
            //    会一直烧请求 —— 这个字段的意义正是跨扫描保留记忆。
            rebuilt.add(
                if (prev != null) item.copy(
                    trackNumber = prev.trackNumber,
                    discNumber = prev.discNumber,
                    metaSource = prev.metaSource,
                    embTitle = prev.embTitle,
                    embArtist = prev.embArtist,
                    embAlbum = prev.embAlbum,
                    embYear = prev.embYear,
                    embTrackNumber = prev.embTrackNumber,
                    embDiscNumber = prev.embDiscNumber,
                    embCoverUrl = prev.embCoverUrl,
                    embLyric = prev.embLyric,
                    embReadAt = prev.embReadAt,
                    metaMissAt = prev.metaMissAt,
                ) else item
            )
        }

        // 重复挂载 / 软链接可能造成同一 url 出现多次
        val unique = rebuilt.distinctBy { it.url }

        // ===== 落库：分批 upsert + 收尾删差集，写库过程中也能取消 =====
        //
        // 旧做法是 clearMedia() + saveMedia(整批)，两个毛病：
        //   ① 一旦点过清库，后面被打断（用户取消 / 进程被杀）库就空了 —— 这是灾难；
        //   ② 「正在写入媒体库」这一阶段完全没有检查点，用户点取消只能干等它跑完。
        // 现在改成：按批 upsert（REPLACE），每批前检查取消信号；全部写完才删「服务器上已不存在的旧行」。
        // 于是任何时刻中断都不会丢数据 —— 已写入的批留在库里，没轮到的旧条目原样还在。
        if (unique.isNotEmpty()) {
            onProgress(
                ScanProgress(
                    if (cancelBeforeWrite) "正在保存已扫描的条目" else "正在写入媒体库",
                    0, unique.size,
                )
            )
        }

        var written = 0
        var cancelledInWrite = false
        for (chunk in unique.chunked(WRITE_CHUNK)) {
            // 枚举阶段就取消时，用户要的是「把已扫到的存下来」，所以这里不再中断；
            // 只有「写入途中才点取消」才真的停下，已写入的批次保留。
            if (!cancelBeforeWrite && shouldStop()) {
                cancelledInWrite = true
                break
            }
            currentCoroutineContext().ensureActive()
            repo.saveMedia(chunk)
            written += chunk.size
            // 进度文案由 UI 拼成「阶段  current/total」，这里只给阶段名和计数，别重复写数字
            onProgress(ScanProgress("正在写入媒体库", written, unique.size))
        }
        val cancelled = cancelBeforeWrite || cancelledInWrite
        // 真正落库的前缀（chunked 是顺序写的，所以前 written 条就是已写入的）
        val writtenItems = if (written >= unique.size) unique else unique.subList(0, written)
        val writtenUrls = writtenItems.mapTo(HashSet()) { it.url }

        // 改名迁移：歌词缓存 / 歌单关联从旧 url 搬到新 url，再把旧行删掉
        // （不清库的前提下，旧行必须显式删，否则留一条指向死链的幽灵条目）。
        // 只搬「新 url 已经写进库」的那些 —— 写入阶段被取消时，没轮到的条目新行还不存在，
        // 此时搬过去会让歌词/歌单指向一个查不到的 url，不如把旧行留着等下次扫描收拾。
        val safeRenames = if (renamedUrls.isEmpty()) emptyList() else renamedUrls.filter { it.second in writtenUrls }
        if (safeRenames.isNotEmpty()) {
            onProgress(ScanProgress("正在迁移历史数据", 0, safeRenames.size))
            safeRenames.forEach { (old, new) -> repo.renameTrackUrl(old, new) }
            repo.deleteMediaByUrls(safeRenames.map { it.first })
        }

        if (cancelled) {
            onProgress(ScanProgress("已取消扫描，已保存 $written 条", 0, 0))
            return@withContext ScanResult(
                total = writtenItems.size,
                music = writtenItems.count { !it.isVideo },
                video = writtenItems.count { it.isVideo },
                failed = failed,
                cancelled = true,
                urls = writtenUrls,
            )
        }

        // 全部写完才做「服务器上已删除」的清理：取消时跳过，留给下次扫描收拾，
        // 免得用户刚点取消就看到库里少了一堆歌（差集删除本身安全，但不符合「取消就是停」的预期）。
        // 多源扫描（deleteStale = false）时这一步交给调用方，等所有源都扫完再用全量 url 清理。
        if (deleteStale) {
            onProgress(ScanProgress("正在清理已移除的文件", 0, 1))
            repo.deleteStaleMedia(writtenUrls)
        }

        onProgress(ScanProgress("扫描完成", unique.size, unique.size))
        ScanResult(
            total = unique.size,
            music = unique.count { !it.isVideo },
            video = unique.count { it.isVideo },
            failed = failed,
            urls = writtenUrls,
        )
    }

    companion object {
        /**
         * 每批写入条数。300 条一批约 1~2 万个绑定参数，远低于 SQLite 单语句变量上限，
         * 又足够大不会让事务次数爆炸；批与批之间是检查取消信号的位置。
         */
        private const val WRITE_CHUNK = 300

        /**
         * 标题/艺人/专辑取值：优先保留库里已有内容（用户可能手动改过），
         * 但会自动修复旧 strm 时代留下的乱码。
         *
         * 老方案从 strm 的 #EXTINF 读标题，若文件内容是 GBK 而按 UTF-8/Latin-1 读取，
         * 就会得到 “Î÷¹ÏJUN ÄàöúNiko …” 这类乱码。这里能把它们还原成“西瓜JUN 泥鳅Niko …”。
         */
        private fun repairText(prev: String?, fallback: String): String {
            if (prev.isNullOrBlank()) return fallback
            return demojibake(prev) ?: prev
        }

        /**
         * 尝试把「UTF-8/GBK 字节被当成 Latin-1 解释」的乱码还原：
         * 逐字符取回原字节，再按 GBK / UTF-8 解码，能解出汉字就认定是乱码。
         * 只有全部字符都落在 0x00–0xFF 时才尝试，正常标题几乎不可能被误判。
         */
        private fun demojibake(text: String): String? {
            if (text.isEmpty()) return null
            val bytes = ByteArray(text.length)
            for (i in text.indices) {
                val code = text[i].code
                if (code > 0xFF) return null
                bytes[i] = code.toByte()
            }
            for (charsetName in listOf("GBK", "UTF-8")) {
                val decoded = try {
                    String(bytes, charset(charsetName))
                } catch (_: Exception) {
                    continue
                }
                if (decoded.count { it.code in 0x4E00..0x9FFF } >= 2) return decoded.trim()
            }
            return null
        }

        /**
         * 取 url 的 `scheme://host[:port]` 前缀。
         * 多源之后要靠它区分「同一路径落在不同机器上」：pathKeyOf 只保留了路径，
         * 两台机器都有 /quark/music/01.flac 时不能互相继承元数据。
         */
        fun hostOf(url: String): String {
            val schemeEnd = url.indexOf("://")
            if (schemeEnd < 0) return ""
            val hostStart = schemeEnd + 3
            val slash = url.indexOf('/', hostStart)
            val end = if (slash < 0) url.length else slash
            return url.substring(0, end).lowercase()
        }

        /**
         * 从任意历史 url 里抽出 OpenList 路径，用于跨版本对账：
         *   .../p/quark/a%20b/01.flac?sign=...  →  /quark/a b/01.flac
         *   .../dav/quark/a%20b/01.flac         →  /quark/a b/01.flac
         */
        fun pathKeyOf(url: String): String? {
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
            val noQuery = url.substringBefore('?').substringBefore('#')
            val schemeEnd = noQuery.indexOf("://")
            if (schemeEnd < 0) return null
            val slash = noQuery.indexOf('/', schemeEnd + 3)
            if (slash < 0) return null
            val rawPath = noQuery.substring(slash)
            var path = WebDavClient.normalizePath(WebDavClient.decodePath(rawPath))
            path = when {
                path == "/dav" -> "/"
                path.startsWith("/dav/") -> path.removePrefix("/dav")
                path == "/p" -> "/"
                path.startsWith("/p/") -> path.removePrefix("/p")
                else -> path
            }
            return WebDavClient.normalizePath(path)
        }
    }
}
