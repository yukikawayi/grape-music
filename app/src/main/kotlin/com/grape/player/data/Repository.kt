package com.grape.player.data

import androidx.room.withTransaction
import com.grape.player.util.LyricQuality
import com.grape.player.util.LyricSource
import com.grape.player.util.MediaPathUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 统一仓库 —— 业务层只依赖它，方便后续把数据源从 Room 换成其它实现。
 */
class Repository(private val db: AppDatabase) {

    /**
     * 在单个 Room 事务里执行一组读写操作。
     * 用于把「对同一首歌的多次写库」合并（如补全流程），每首歌的失效通知从 3~5 次降到 1~2 次，
     * 补全期间 UI 收到的 Flow 重发频率大幅下降。
     */
    suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    val media: Flow<List<MediaItem>> = db.mediaDao().getAll()
    val music: Flow<List<MediaItemLite>> = db.mediaDao().getAllMusic()
    val videos: Flow<List<MediaItemLite>> = db.mediaDao().getAllVideos()
    val playlists: Flow<List<Playlist>> = db.playlistDao().getAll()

    /** 歌单 id → 曲目数（歌单列表页显示用） */
    val playlistCounts: Flow<Map<Long, Int>> = db.playlistDao().itemCounts()
        .map { list -> list.associate { it.playlistId to it.itemCount } }

    /**
     * 最近播放（时间倒序，最多 [RecentPlay.LIMIT] 条）。
     * 歌单页把它渲染成永远排第一的「最近播放」虚拟歌单。
     */
    val recentPlays: Flow<List<RecentPlay>> = db.recentPlayDao().getRecent(RecentPlay.LIMIT)

    suspend fun count() = db.mediaDao().count()

    suspend fun getAllOnce() = db.mediaDao().getAllOnce()

    suspend fun getByUrl(url: String) = db.mediaDao().getByUrl(url)

    /** 按 url 批量取行（批量编辑页展示所选歌曲的封面用） */
    suspend fun getByUrls(urls: List<String>): List<MediaItem> =
        if (urls.isEmpty()) emptyList()
        else urls.chunked(500).flatMap { db.mediaDao().getByUrls(it) }

    suspend fun saveMedia(items: List<MediaItem>) = db.mediaDao().insertAll(items)

    suspend fun updateMedia(item: MediaItem) = db.mediaDao().update(item)

    /** 元数据编辑：只改可编辑字段，保留时长 / 响度实测值 / 时间偏移 */
    suspend fun updateMeta(
        url: String,
        title: String,
        artist: String,
        album: String,
        year: Int,
        coverUrl: String,
        lyric: String,
    ) = db.mediaDao().updateMeta(url, title, artist, album, year, coverUrl, lyric)

    /** 音轨号 / 碟号一起更新（0 = 清除） */
    suspend fun updateTrackDisc(url: String, track: Int, disc: Int) {
        db.mediaDao().updateTrack(url, track)
        db.mediaDao().updateDisc(url, disc)
    }

    suspend fun updateMetaField(url: String, field: String, value: String) {
        when (field) {
            "title" -> db.mediaDao().updateTitle(url, value)
            "artist" -> db.mediaDao().updateArtist(url, value)
            "album" -> db.mediaDao().updateAlbum(url, value)
            "year" -> db.mediaDao().updateYear(url, value.toIntOrNull() ?: 0)
            "track" -> db.mediaDao().updateTrack(url, value.toIntOrNull() ?: 0)
            "disc" -> db.mediaDao().updateDisc(url, value.toIntOrNull() ?: 0)
            "coverUrl" -> db.mediaDao().updateCover(url, value)
        }
    }

    /**
     * 批量清空 Using_ 字段，让后台补全重新接手。
     *
     * 与「批量填入」相反，这里把字段清回空值。两个例外值得记住：
     * - **标题不清成空串**，而是恢复成文件名推导值：清成空串会让列表出现一排空白行，
     *   补全拿空标题去搜也只剩艺人名，返回的必然是热门歌。恢复成推导值后，
     *   `updateTitleIfMatches` 那条「只在标题仍等于推导值时才覆盖」的规则又成立了。
     * - **清 artist / album / year / coverUrl 会把 metaSource 归 0**：
     *   字段都空了还挂着「在线 / 内嵌」胶囊，界面上就是假信息。
     *
     * 歌词走 lyrics 表（不是 media_items 的列），一并按 url 批量删。
     */
    suspend fun clearMetaFields(urls: List<String>, fields: Set<String>) {
        if (urls.isEmpty() || fields.isEmpty()) return
        inTransaction {
            urls.chunked(500).forEach { chunk ->
                if ("artist" in fields) db.mediaDao().clearArtist(chunk)
                if ("album" in fields) db.mediaDao().clearAlbum(chunk)
                if ("year" in fields) db.mediaDao().clearYear(chunk)
                if ("track" in fields) db.mediaDao().clearTrack(chunk)
                if ("disc" in fields) db.mediaDao().clearDisc(chunk)
                if ("cover" in fields) db.mediaDao().clearCover(chunk)
                if ("lyric" in fields) {
                    db.mediaDao().clearLyricColumn(chunk)
                    db.lyricDao().deleteByUrls(chunk)
                }
                // metaSource 描述的是「剩下这些字段是谁填的」，字段被清掉就不成立了；
                // 同时把失败退避归零 —— 清空是用户明确的「再补一次」指令，否则刚清完的歌
                // 可能还压在上一次的 TTL 里，界面上看着就像「清了但没反应」
                if (fields.any { it == "artist" || it == "album" || it == "year" || it == "cover" }) {
                    db.mediaDao().resetMetaSource(chunk)
                    db.mediaDao().clearMetaMissBatch(chunk)
                }
            }
            if ("title" in fields) {
                urls.forEach { url ->
                    val derived = MediaPathUtil.titleFromPath(url)
                    if (derived.isNotBlank()) db.mediaDao().updateTitle(url, derived)
                }
            }
        }
    }

    /** 后台补全覆盖标题：仅当库里标题仍是文件名推导的原样时生效 */
    suspend fun updateTitleIfMatches(url: String, newTitle: String, oldTitle: String) =
        db.mediaDao().updateTitleIfMatches(url, newTitle, oldTitle)

    suspend fun updateLoudness(url: String, lufs: Float, peak: Float, coverage: Float) =
        db.mediaDao().updateLoudness(url, lufs, peak, coverage)

    /** 播放一次后把真实时长写回库（扫描阶段拿不到时长） */
    suspend fun updateDurationIfEmpty(url: String, durationMs: Long) =
        db.mediaDao().updateDurationIfEmpty(url, durationMs)

    suspend fun updateCover(url: String, coverUrl: String) = db.mediaDao().updateCover(url, coverUrl)

    /** 快照封面更新（启动自愈：快照文件丢失时清引用，交补全重新填） */
    suspend fun updateEmbCover(url: String, v: String) = db.mediaDao().updateEmbCover(url, v)

    /**
     * 后台补全元数据的待处理清单。
     * [retryBefore] 之前的失败记录会被跳过（默认 [Long.MAX_VALUE] = 不退避）。
     */
    suspend fun getNeedingMeta(retryBefore: Long = Long.MAX_VALUE) =
        db.mediaDao().getNeedingMeta(retryBefore)

    /** 记一次「在线补全试过但一无所获」：TTL 内不再进队列，避免永远补不上的歌每轮白搜 */
    suspend fun markMetaMiss(url: String, ts: Long = System.currentTimeMillis()) =
        db.mediaDao().markMetaMiss(url, ts)

    /** 这一轮补到了东西 → 清掉失败记录 */
    suspend fun clearMetaMiss(url: String) = db.mediaDao().clearMetaMiss(url)

    /** 整库清掉失败记录（「删除所有在线数据」这类全量重置后用） */
    suspend fun clearMetaMissAll() = db.mediaDao().clearMetaMissAll()

    /** 内嵌快照还没读过的曲目清单（补全时的第一趟：只读标签，不联网） */
    suspend fun getNeedingEmbedded() = db.mediaDao().getNeedingEmbedded()

    /** 标记内嵌快照已读（读到标签就置位，哪怕标签是空的；读取失败不置位，留给下次重试） */
    suspend fun markEmbRead(url: String, ts: Long = System.currentTimeMillis()) =
        db.mediaDao().markEmbRead(url, ts)

    /** 一次性回填「内嵌」来源标记（幂等） */
    suspend fun backfillEmbeddedMetaSource() = db.mediaDao().backfillEmbeddedMetaSource()

    /** 后台补全元数据：只填空不覆盖；onlineFilled 表示本次有在线刮削写入（来源记「在线」=2） */
    suspend fun fillBlankMeta(
        url: String,
        artist: String,
        album: String,
        year: Int,
        coverUrl: String,
        durationMs: Long,
        trackNumber: Int = 0,
        discNumber: Int = 0,
        onlineFilled: Boolean = false,
        embeddedFilled: Boolean = false,
    ) = db.mediaDao().fillBlankMeta(
        url, artist, album, year, coverUrl, durationMs, trackNumber, discNumber, onlineFilled, embeddedFilled,
    )

    /** Embedded_ 快照整体覆盖（文件是唯一事实来源） */
    suspend fun saveEmbeddedMeta(
        url: String,
        embTitle: String,
        embArtist: String,
        embAlbum: String,
        embYear: Int,
        embTrackNumber: Int,
        embDiscNumber: Int,
        embCoverUrl: String,
        embLyric: String,
    ) = db.mediaDao().saveEmbeddedMeta(
        url, embTitle, embArtist, embAlbum, embYear, embTrackNumber, embDiscNumber, embCoverUrl, embLyric,
    )

    suspend fun updateEmbLyric(url: String, lyric: String) = db.mediaDao().updateEmbLyric(url, lyric)

    /** 采用在线/内嵌快照为 Using_（整组覆盖 + 维护来源标记） */
    suspend fun adoptMeta(
        url: String,
        title: String,
        artist: String,
        album: String,
        year: Int,
        trackNumber: Int,
        discNumber: Int,
        coverUrl: String,
        source: Int,
    ) = db.mediaDao().adoptMeta(url, title, artist, album, year, trackNumber, discNumber, coverUrl, source)

    suspend fun setMetaSource(url: String, source: Int) = db.mediaDao().setMetaSource(url, source)

    /** 「删除所有在线数据」：Using_ 整组重置为内嵌值（title 由调用方算好） */
    suspend fun resetUsingFromEmbedded(
        url: String,
        title: String,
        artist: String,
        album: String,
        year: Int,
        trackNumber: Int,
        discNumber: Int,
        coverUrl: String,
        lyric: String,
        metaSource: Int,
    ) = db.mediaDao().resetUsingFromEmbedded(
        url, title, artist, album, year, trackNumber, discNumber, coverUrl, lyric, metaSource,
    )

    suspend fun clearMedia() = db.mediaDao().clearAll()

    /** 按 url 批量删除媒体行（改名迁移后清旧行；空列表直接跳过，避免生成 IN () 非法 SQL） */
    suspend fun deleteMediaByUrls(urls: List<String>) {
        if (urls.isEmpty()) return
        // 分批删：老设备（SQLite < 3.32）单语句变量上限 999，大库重扫改名超千条不分会直接
        // SQLiteException: too many SQL variables 崩溃（与 deleteStaleMedia 同一套防护）
        urls.chunked(500).forEach {
            db.mediaDao().deleteByUrls(it)
            // 最近播放里对应的记录一并清掉，否则会留下点不开的幽灵条目
            db.recentPlayDao().deleteByUrls(it)
        }
    }

    /**
     * 删除「本次扫描没再出现」的旧行（服务器上已删掉/移走的文件）。
     *
     * 取代原来「clearMedia() + 整批 insert」里的清库动作：先算差集再分批删，
     * 好处是 (1) 不会把还没写入的行一起清空，扫描中途中断不丢数据；
     * (2) 不用一次性给 `NOT IN (:urls)` 传几千个参数，避免撞 SQLite 变量数上限。
     */
    suspend fun deleteStaleMedia(keepUrls: Collection<String>) {
        val keep = keepUrls.toHashSet()
        val stale = db.mediaDao().getAllUrls().filterNot { it in keep }
        stale.chunked(500).forEach {
            db.mediaDao().deleteByUrls(it)
            db.recentPlayDao().deleteByUrls(it)
        }
    }

    /**
     * 清空媒体库数据（设置页「清除数据库」用）。
     * 保留 settings 表 —— 否则 WebDAV 地址/账号、外观偏好会被一起抹掉，清完还得重配。
     */
    suspend fun clearDatabase() {
        db.mediaDao().clearAll()
        db.lyricDao().clearAll()
        db.playlistDao().clearAllItems()
        db.playlistDao().clearAll()
        // 最近播放存的是 url，曲目清空后这些记录全部失效
        db.recentPlayDao().clearAll()
        db.metaCacheDao().clearAll()
    }

    suspend fun deleteMediaNotIn(urls: List<String>) = db.mediaDao().deleteNotIn(urls)

    suspend fun getLyric(url: String) = db.lyricDao().get(url)

    /**
     * 事务内「只填空」写歌词（播放页 / 切歌预取 / 后台补全 可能同时写同一首）。
     *
     * 原来的 [saveLyric] 是 `@Insert(REPLACE)`：并发场景下 A 刚写进正确歌词、
     * B 晚一步拿到空结果，REPLACE 会把 A 的整行抹掉，只剩空 lrc + missAt。
     * 这里把「读—判断—写」放进同一个事务：已有正文的行一律不动，只补空行/新增行。
     *
     * @return true = 本次真的写入了（原来没行或原行为空）；false = 已有歌词，保持不变
     */
    suspend fun fillLyricIfBlank(entry: LyricEntry): Boolean = db.withTransaction {
        val cur = db.lyricDao().get(entry.url)
        val now = System.currentTimeMillis()
        when {
            cur == null -> {
                // 新行：出处与写入时间都由调用方给（内嵌 / 具体某个在线源）
                db.lyricDao().save(
                    entry.copy(source = LyricSource.normalize(entry.source), updatedAt = now)
                )
                true
            }
            // 已确认没有歌词的占位行（老版本会把它当成功结果存进来）允许被新结果覆盖；
            // 用户手写的无时间轴纯文本不算占位，保持不动
            cur.lrc.isNotBlank() && !LyricQuality.isPlaceholder(cur.lrc) -> false
            else -> {
                // 复用原行的 id 与用户已调好的 offset；missAt 归零 = 重新算「有歌词」。
                // 内嵌歌词格式（新版替换占位行时会带默认值 false）同样按原行保留 ——
                // 换一份正文不该把用户为这首歌选好的「原词/译文顺序」重置掉
                db.lyricDao().save(
                    entry.copy(
                        id = cur.id,
                        offsetMs = cur.offsetMs,
                        translationFirst = cur.translationFirst,
                        missAt = 0,
                        source = LyricSource.normalize(entry.source),
                        updatedAt = now,
                    )
                )
                true
            }
        }
    }

    /**
     * 事务内标「确认无歌词」（负缓存）。
     * 已有正文的行绝不覆盖 —— 失败的并发方无权删掉成功方刚写好的歌词。
     *
     * 出处与写入时间随原行保留：负缓存行里没有正文，但「上一次这份词是谁给的」仍有参考价值，
     * 也避免后续重试命中后 new row 的 updatedAt 被误解成「刚写的」。
     *
     * @return true = 标记成功；false = 这行已经有歌词，什么都没做
     */
    suspend fun markLyricMiss(url: String, at: Long): Boolean = db.withTransaction {
        val cur = db.lyricDao().get(url)
        if (cur != null && cur.lrc.isNotBlank()) {
            false
        } else {
            db.lyricDao().save(
                LyricEntry(
                    id = cur?.id ?: 0,
                    url = url,
                    lrc = "",
                    translatedLrc = cur?.translatedLrc.orEmpty(),
                    offsetMs = cur?.offsetMs ?: 0L,
                    translationFirst = cur?.translationFirst ?: false,
                    missAt = at,
                    source = cur?.source.orEmpty(),
                    updatedAt = cur?.updatedAt ?: 0L,
                )
            )
            true
        }
    }

    /**
     * 整体写一行（手动编辑 / 采用内嵌用）。
     *
     * `updatedAt` 由调用方决定，这里**不**自动补「现在」：为写偏移而顺手建的空行、
     * 以及「只是改歌名、正文没动」的保存，都不该把老的写入时间刷新成今天 ——
     * 一旦刷了，「这首的词是哪天拿到的」这条信息就永久丢了。
     */
    suspend fun saveLyric(entry: LyricEntry) = db.lyricDao().save(
        entry.copy(source = LyricSource.normalize(entry.source))
    )

    /** 清空歌词缓存（「删除所有在线数据」用；内嵌歌词随后按快照重建） */
    suspend fun clearLyrics() = db.lyricDao().clearAll()

    /** 作废旧版本误写的「无歌词」负缓存（空词行长按播放时重新联网匹配一次） */
    suspend fun resetLyricMiss() = db.lyricDao().resetMiss()

    /** 清空在线刮削缓存表（「删除所有在线数据」用） */
    suspend fun clearMetaCache() = db.metaCacheDao().clearAll()

    suspend fun setLyricOffset(url: String, offset: Long) = db.lyricDao().updateOffset(url, offset)

    /** 按曲目记内嵌歌词格式（true = 译文写在原词上方） */
    suspend fun setLyricTranslationFirst(url: String, first: Boolean) =
        db.lyricDao().updateTranslationFirst(url, first)

    /** 直链变更（旧签名 url → 稳定 /dav url）时，把歌词与歌单关联一起搬过去 */
    suspend fun renameTrackUrl(oldUrl: String, newUrl: String) {
        if (oldUrl == newUrl) return
        db.lyricDao().renameUrl(oldUrl, newUrl)
        db.playlistDao().renameUrl(oldUrl, newUrl)
        db.recentPlayDao().renameUrl(oldUrl, newUrl)
    }

    suspend fun getMeta(url: String) = db.metaCacheDao().get(url)

    suspend fun saveMeta(entry: MetaCache) = db.metaCacheDao().save(entry)

    suspend fun getSetting(key: String) = db.settingDao().get(key)

    suspend fun setSetting(key: String, value: String) = db.settingDao().set(SettingEntry(key, value))

    /** 一次性清理旧格式的派生设置键（stream_gain_* / accent_url_* / lyric_off_*） */
    suspend fun deleteLegacyDerivedKeys() = db.settingDao().deleteLegacyDerivedKeys()

    suspend fun createPlaylist(name: String): Long =
        db.playlistDao().insert(Playlist(name = name))

    suspend fun renamePlaylist(playlist: Playlist) = db.playlistDao().update(playlist)

    /** 删歌单必须连带清掉关联行，否则 playlist_items 会留下永远查不到的孤儿数据 */
    suspend fun deletePlaylist(playlist: Playlist) {
        db.playlistDao().clearItems(playlist.id)
        db.playlistDao().delete(playlist)
    }

    fun playlistItems(id: Long): Flow<List<PlaylistItem>> = db.playlistDao().getItems(id)

    suspend fun playlistItemsOnce(id: Long): List<PlaylistItem> = db.playlistDao().getItemsOnce(id)

    suspend fun addToPlaylist(playlistId: Long, urls: List<String>) {
        // position 从当前最大值往后接，追加顺序稳定（原来用当前毫秒的 int 会溢出）；
        // 读 max + 写入在同一事务里，并发添加不会 position 撞号
        db.playlistDao().addItemsAtTail(playlistId, urls)
    }

    /** 按给定顺序重写 position（上移/下移/拖动排序后调用）；单事务批量完成 */
    suspend fun reorderPlaylist(playlistId: Long, orderedUrls: List<String>) {
        db.playlistDao().reorderPositions(playlistId, orderedUrls)
    }

    suspend fun removeFromPlaylist(playlistId: Long, url: String) =
        db.playlistDao().removeItem(playlistId, url)

    suspend fun clearPlaylist(playlistId: Long) = db.playlistDao().clearItems(playlistId)

    suspend fun playlistsForUrl(url: String) = db.playlistDao().getPlaylistsForUrl(url)

    /**
     * 记一条「最近播放」。
     *
     * 同一首（url）重复播放只把 playedAt 刷成现在，不会新增行 —— 所以「最近播放」是
     * 「听过的歌按最后一次我听它的时间倒序」，同一首不会在列表里出现两次。
     * 写入后立刻裁剪到 [RecentPlay.LIMIT] 条，放同一个事务里，表长永远有界。
     */
    suspend fun recordRecentPlay(url: String) {
        if (url.isBlank()) return
        db.withTransaction {
            val dao = db.recentPlayDao()
            dao.upsert(RecentPlay(url = url, playedAt = System.currentTimeMillis()))
            dao.trim(RecentPlay.LIMIT)
        }
    }
}
