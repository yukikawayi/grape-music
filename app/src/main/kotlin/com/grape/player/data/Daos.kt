package com.grape.player.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media_items ORDER BY folder, title")
    fun getAll(): Flow<List<MediaItem>>

    /**
     * 音乐列表 —— 显式列投影，不取 lyric / embLyric 两列歌词正文。
     *
     * 原来 `SELECT *` 会把每首 3~8KB 的歌词正文（lyric + embLyric 两份）拉进内存，
     * 3000 首 ≈ 几十 MB 常驻；且列表 Flow 每次重发都整表重读（补全期间每 250ms 一次），
     * 造成内存抖动与 GC 压力。列表展示与播放队列的运行都不需要歌词正文，需要时按 url 回表取。
     */
    @Query(
        "SELECT id, url, title, artist, album, year, durationMs, sizeBytes, folder, coverUrl, " +
            "replayGainGain, replayGainPeak, isVideo, trackNumber, discNumber, metaSource " +
            "FROM media_items WHERE isVideo = 0 ORDER BY title"
    )
    fun getAllMusic(): Flow<List<MediaItemLite>>

    @Query("SELECT * FROM media_items")
    suspend fun getAllOnce(): List<MediaItem>

    @Query(
        "SELECT id, url, title, artist, album, year, durationMs, sizeBytes, folder, coverUrl, " +
            "replayGainGain, replayGainPeak, isVideo, trackNumber, discNumber, metaSource " +
            "FROM media_items WHERE isVideo = 1"
    )
    fun getAllVideos(): Flow<List<MediaItemLite>>

    @Query("SELECT * FROM media_items WHERE url = :url LIMIT 1")
    suspend fun getByUrl(url: String): MediaItem?

    @Query("SELECT * FROM media_items WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MediaItem>

    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItem>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: MediaItem): Long

    @Update
    suspend fun update(item: MediaItem)

    @Query("UPDATE media_items SET lufs = :lufs, peak = :peak, coverage = :coverage WHERE url = :url")
    suspend fun updateLoudness(url: String, lufs: Float, peak: Float, coverage: Float)

    /** 首次播放后回填真实时长：纯 WebDAV 扫描阶段拿不到容器时长，只能由播放器补 */
    @Query("UPDATE media_items SET durationMs = :durationMs WHERE url = :url AND durationMs <= 0")
    suspend fun updateDurationIfEmpty(url: String, durationMs: Long)

    /**
     * 后台补全元数据：只挑还有空缺的**音频**曲目。
     *
     * 必须排除视频（isVideo = 0）：补全的在线源是 QQ / 酷狗 / 网易云这些音乐库，
     * 拿视频文件名去搜歌只会匹配到别人的艺人/专辑；更糟的是 `durationMs` 也会被
     * 歌曲时长填掉，而 updateDurationIfEmpty 只在 durationMs <= 0 时补，
     * 填错之后整首歌的真实时长（进度条/总时长）就再也修不回来了。
     * 视频的海报是另一套逻辑，不归补全管。
     *
     * year = 0 也算缺口：内嵌标签与文件名都给不出年份，只有在线源有。
     * 老条件只看 artist/album/coverUrl，导致「三字段齐、只缺年份」的歌永远进不了队列 ——
     * 而补全的在线分支里明明写了 `year == 0` 的判断，那半个条件从来没生效过。
     * 代价是上游也没有年份的歌每次扫描后会多搜一次（补全只在扫描后与续跑时启动，
     * 不会在后台反复重试），换来的是年份能补齐。
     *
     * [retryBefore] 是失败退避的截止线：只有 metaMissAt < 它的行才进队列。
     * 上游**永远**给不出某些字段的歌（尤其年份）以前每轮都白搜一次，
     * 现在失败过一次就安静一个 TTL。传 Long.MAX_VALUE 可关闭退避（仅调试用）。
     */
    @Query(
        "SELECT * FROM media_items WHERE isVideo = 0 " +
            "AND metaMissAt < :retryBefore " +
            "AND (artist = '' OR album = '' OR coverUrl = '' OR year = 0)"
    )
    suspend fun getNeedingMeta(retryBefore: Long): List<MediaItem>

    /** 记一次「在线补全试过了、但这一轮一个字段都没填上」—— TTL 内不再白搜 */
    @Query("UPDATE media_items SET metaMissAt = :ts WHERE url = :url")
    suspend fun markMetaMiss(url: String, ts: Long)

    /** 这一轮确实补到了东西 → 清掉失败标记，下次有缺口时正常重试 */
    @Query("UPDATE media_items SET metaMissAt = 0 WHERE url = :url")
    suspend fun clearMetaMiss(url: String)

    /** 用户主动清空元数据 = 明确要求重试，整批把退避归零 */
    @Query("UPDATE media_items SET metaMissAt = 0 WHERE url IN (:urls)")
    suspend fun clearMetaMissBatch(urls: List<String>)

    /** 整库归零（全量重置类操作后，所有歌都该重新获得一次机会） */
    @Query("UPDATE media_items SET metaMissAt = 0")
    suspend fun clearMetaMissAll()

    /**
     * 内嵌快照还没回填过的曲目（视频没有音频标签，直接跳过）。
     * 靠 embReadAt 判定而不是 embXxx 是否为空：文件无标签时那些列永远是空，
     * 用空值判定会导致每次补全都把全库文件头重下一遍。
     */
    @Query("SELECT * FROM media_items WHERE embReadAt = 0 AND isVideo = 0")
    suspend fun getNeedingEmbedded(): List<MediaItem>

    /** 标记「这首的内嵌快照已经读过」，置 0 之外的任意时间戳即可 */
    @Query("UPDATE media_items SET embReadAt = :ts WHERE url = :url")
    suspend fun markEmbRead(url: String, ts: Long)

    /**
     * 一次性回填「内嵌」来源标记：旧版本内嵌补全不写 metaSource，
     * 已补全的老歌 metaSource 恒为 0 导致列表胶囊不显示。
     * 有 Embedded_ 快照（任一字段非空）且来源仍为 0 的记为 1；幂等，可重复执行。
     */
    @Query(
        """UPDATE media_items SET metaSource = 1
           WHERE metaSource = 0 AND (
             embTitle != '' OR embArtist != '' OR embAlbum != '' OR embYear > 0 OR
             embTrackNumber > 0 OR embDiscNumber > 0 OR embCoverUrl != '' OR embLyric != '')"""
    )
    suspend fun backfillEmbeddedMetaSource()

    /** 只填空不覆盖（SQL 层原子完成，避免读改写竞态）；来源标记：在线填了记 2，内嵌填了且未定源记 1 */
    @Query(
        """UPDATE media_items SET
             artist = CASE WHEN artist = '' THEN :artist ELSE artist END,
             album  = CASE WHEN album  = '' THEN :album  ELSE album  END,
             year   = CASE WHEN year   = 0  THEN :year   ELSE year   END,
             coverUrl = CASE WHEN coverUrl = '' THEN :coverUrl ELSE coverUrl END,
             durationMs = CASE WHEN durationMs <= 0 AND :durationMs > 0 THEN :durationMs ELSE durationMs END,
             trackNumber = CASE WHEN trackNumber = 0 AND :trackNumber > 0 THEN :trackNumber ELSE trackNumber END,
             discNumber = CASE WHEN discNumber = 0 AND :discNumber > 0 THEN :discNumber ELSE discNumber END,
             metaSource = CASE WHEN :onlineFilled THEN 2
                               WHEN :embeddedFilled AND metaSource = 0 THEN 1
                               ELSE metaSource END
           WHERE url = :url"""
    )
    suspend fun fillBlankMeta(
        url: String,
        artist: String,
        album: String,
        year: Int,
        coverUrl: String,
        durationMs: Long,
        trackNumber: Int,
        discNumber: Int,
        onlineFilled: Boolean,
        embeddedFilled: Boolean,
    )

    /** Embedded_ 快照：内嵌标签读出的整套数据直接覆盖（文件是唯一事实来源） */
    @Query(
        """UPDATE media_items SET
             embTitle = :embTitle, embArtist = :embArtist, embAlbum = :embAlbum,
             embYear = :embYear, embTrackNumber = :embTrackNumber, embDiscNumber = :embDiscNumber,
             embCoverUrl = :embCoverUrl, embLyric = :embLyric
           WHERE url = :url"""
    )
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
    )

    /** Embedded_ 歌词（播放时内嵌兜底读到但补全阶段没覆盖到的歌） */
    @Query("UPDATE media_items SET embLyric = :lyric WHERE url = :url")
    suspend fun updateEmbLyric(url: String, lyric: String)

    /** 采用在线/内嵌快照：Using_ 整组覆盖 + 来源标记（1=内嵌 / 2=在线） */
    @Query(
        """UPDATE media_items SET
             title = :title, artist = :artist, album = :album, year = :year,
             trackNumber = :trackNumber, discNumber = :discNumber, coverUrl = :coverUrl,
             metaSource = :source
           WHERE url = :url"""
    )
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
    )

    /** 单独维护来源标记（手动编辑记 3；后台补全不动手动标记） */
    @Query("UPDATE media_items SET metaSource = :source WHERE url = :url")
    suspend fun setMetaSource(url: String, source: Int)

    /**
     * 「删除所有在线数据」专用：Using_ 全字段整组重置为内嵌值（无内嵌的字段清空），
     * 来源标记按内嵌有无元字段记 1/0。Embedded_ 列不动。
     * [title] 由调用方算好（embTitle 优先、否则文件名推导值），其余字段直接传内嵌快照值。
     */
    @Query(
        """UPDATE media_items SET
             title = :title, artist = :artist, album = :album, year = :year,
             trackNumber = :trackNumber, discNumber = :discNumber, coverUrl = :coverUrl,
             lyric = :lyric, metaSource = :metaSource
           WHERE url = :url"""
    )
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
    )

    /** 元数据编辑：只更新可编辑字段，保留 durationMs / lufs / peak / coverage / loudnessTarget 等实测值 */
    @Query("""UPDATE media_items SET title = :title, artist = :artist, album = :album, year = :year,
        coverUrl = :coverUrl, lyric = :lyric WHERE url = :url""")
    suspend fun updateMeta(
        url: String,
        title: String,
        artist: String,
        album: String,
        year: Int,
        coverUrl: String,
        lyric: String,
    )

    /** 批量编辑：只改指定字段，其余每首歌保持原值 */
    @Query("UPDATE media_items SET title = :title WHERE url = :url")
    suspend fun updateTitle(url: String, title: String)

    /**
     * 后台补全时的标题覆盖：仅当库里标题仍等于「文件名推导的原样标题」时才覆盖，
     * 用户手动改过的标题（与文件名推导值不一致）不会被碰。
     */
    @Query("UPDATE media_items SET title = :newTitle WHERE url = :url AND title = :oldTitle AND :newTitle != ''")
    suspend fun updateTitleIfMatches(url: String, newTitle: String, oldTitle: String)

    @Query("UPDATE media_items SET artist = :artist WHERE url = :url")
    suspend fun updateArtist(url: String, artist: String)

    @Query("UPDATE media_items SET album = :album WHERE url = :url")
    suspend fun updateAlbum(url: String, album: String)

    @Query("UPDATE media_items SET year = :year WHERE url = :url")
    suspend fun updateYear(url: String, year: Int)

    @Query("UPDATE media_items SET trackNumber = :track WHERE url = :url")
    suspend fun updateTrack(url: String, track: Int)

    @Query("UPDATE media_items SET discNumber = :disc WHERE url = :url")
    suspend fun updateDisc(url: String, disc: Int)

    @Query("UPDATE media_items SET coverUrl = :coverUrl WHERE url = :url")
    suspend fun updateCover(url: String, coverUrl: String)

    /** 快照封面更新（自愈：快照文件丢失时清引用，交补全重新填） */
    @Query("UPDATE media_items SET embCoverUrl = :v WHERE url = :url")
    suspend fun updateEmbCover(url: String, v: String)

    // ---- 批量「清空元数据」：把勾选字段清回空值，交给补全重新填 ----
    // 全部按 url 批量更新（调用方按 500 分片，避免撞 SQLite 变量数上限）

    @Query("UPDATE media_items SET artist = '' WHERE url IN (:urls)")
    suspend fun clearArtist(urls: List<String>)

    @Query("UPDATE media_items SET album = '' WHERE url IN (:urls)")
    suspend fun clearAlbum(urls: List<String>)

    @Query("UPDATE media_items SET year = 0 WHERE url IN (:urls)")
    suspend fun clearYear(urls: List<String>)

    @Query("UPDATE media_items SET trackNumber = 0 WHERE url IN (:urls)")
    suspend fun clearTrack(urls: List<String>)

    @Query("UPDATE media_items SET discNumber = 0 WHERE url IN (:urls)")
    suspend fun clearDisc(urls: List<String>)

    @Query("UPDATE media_items SET coverUrl = '' WHERE url IN (:urls)")
    suspend fun clearCover(urls: List<String>)

    /**
     * 清 media_items.lyric 这一列（队列里显示/播放页兜底用的那份）。
     * 歌词正文在 lyrics 表，但 MediaItem 上还留着一份会被播放队列带着走 ——
     * 只删 lyrics 表的话，正在播的那首还会从内存里拿到旧词。
     */
    @Query("UPDATE media_items SET lyric = '' WHERE url IN (:urls)")
    suspend fun clearLyricColumn(urls: List<String>)

    /** 清空后来源标记回到「未知」：否则列表上还挂着「在线 / 内嵌」胶囊，字段却是空的 */
    @Query("UPDATE media_items SET metaSource = 0 WHERE url IN (:urls)")
    suspend fun resetMetaSource(urls: List<String>)

    /** 按 url 批量取行（批量编辑页展示所选歌曲的封面用） */
    @Query("SELECT * FROM media_items WHERE url IN (:urls)")
    suspend fun getByUrls(urls: List<String>): List<MediaItem>

    @Query("DELETE FROM media_items")
    suspend fun clearAll()

    @Query("DELETE FROM media_items WHERE url NOT IN (:urls)")
    suspend fun deleteNotIn(urls: List<String>)

    /** 按 url 批量删行：取消扫描的部分保存路径用它清掉改名前的旧行，避免幽灵条目 */
    @Query("DELETE FROM media_items WHERE url IN (:urls)")
    suspend fun deleteByUrls(urls: List<String>)

    /** 只取 url 列（扫描收尾算「服务器上已不存在」的差集用，比整行查询轻得多） */
    @Query("SELECT url FROM media_items")
    suspend fun getAllUrls(): List<String>
}

@Dao
interface LyricDao {
    @Query("SELECT * FROM lyrics WHERE url = :url LIMIT 1")
    suspend fun get(url: String): LyricEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: LyricEntry)

    @Query("UPDATE lyrics SET offsetMs = :offset WHERE url = :url")
    suspend fun updateOffset(url: String, offset: Long)

    /** 直链迁移：旧 url 换成新的 /dav url 时把歌词一起搬过去 */
    @Query("UPDATE OR REPLACE lyrics SET url = :newUrl WHERE url = :oldUrl")
    suspend fun renameUrl(oldUrl: String, newUrl: String)

    /**
     * 作废全部「已确认无歌词」负缓存。
     *
     * 旧版本把「设置里一个在线源都没启用」也当成「确认没有歌词」写进 missAt，
     * 于是一整批歌被藏 24 小时。这类记录不可信，升级后统一清一次。
     */
    @Query("UPDATE lyrics SET missAt = 0 WHERE lrc = ''")
    suspend fun resetMiss()

    @Query("DELETE FROM lyrics WHERE url = :url")
    suspend fun delete(url: String)

    /** 按 url 批量删歌词（批量「清空元数据」勾了歌词时用） */
    @Query("DELETE FROM lyrics WHERE url IN (:urls)")
    suspend fun deleteByUrls(urls: List<String>)

    @Query("DELETE FROM lyrics")
    suspend fun clearAll()
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAll(): Flow<List<Playlist>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playlist: Playlist): Long

    @Update
    suspend fun update(playlist: Playlist)

    @Delete
    suspend fun delete(playlist: Playlist)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    fun getItems(playlistId: Long): Flow<List<PlaylistItem>>

    /** 添加歌曲时算下一个 position，避免用时间戳（int 溢出会导致新歌插到随机位置） */
    @Query("SELECT COALESCE(MAX(position), 0) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    /** 单个歌单的曲目数（歌单列表页一次性取全部） */
    @Query("SELECT playlistId, COUNT(*) AS itemCount FROM playlist_items GROUP BY playlistId")
    fun itemCounts(): Flow<List<PlaylistCount>>

    /** 拖拽/上下移动后重排：按新顺序重写 position */
    @Query("UPDATE playlist_items SET position = :position WHERE playlistId = :playlistId AND url = :url")
    suspend fun updatePosition(playlistId: Long, url: String, position: Int)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    suspend fun getItemsOnce(playlistId: Long): List<PlaylistItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItem(item: PlaylistItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addItems(items: List<PlaylistItem>)

    /**
     * 追加到歌单尾部（事务内读 max + 写入）：
     * maxPosition 与 addItems 不在同一事务时，并发添加会 position 撞号
     */
    @Transaction
    suspend fun addItemsAtTail(playlistId: Long, urls: List<String>): List<PlaylistItem> {
        var pos = maxPosition(playlistId)
        val items = urls.map { PlaylistItem(playlistId = playlistId, url = it, position = ++pos) }
        addItems(items)
        return items
    }

    /**
     * 按给定顺序批量重写 position（拖拽/上下移动排序后调用）。
     * 事务包裹：避免逐条 UPDATE 中途被打断留下错序，也减少事务开销
     */
    @Transaction
    suspend fun reorderPositions(playlistId: Long, orderedUrls: List<String>) {
        orderedUrls.forEachIndexed { index, url ->
            updatePosition(playlistId, url, index)
        }
    }

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId AND url = :url")
    suspend fun removeItem(playlistId: Long, url: String)

    /** 直链迁移：歌单里的旧 url 同步换成新的 /dav url */
    @Query("UPDATE OR REPLACE playlist_items SET url = :newUrl WHERE url = :oldUrl")
    suspend fun renameUrl(oldUrl: String, newUrl: String)

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearItems(playlistId: Long)

    @Query("DELETE FROM playlist_items")
    suspend fun clearAllItems()

    @Query("DELETE FROM playlists")
    suspend fun clearAll()

    @Transaction
    @Query("SELECT p.* FROM playlists p JOIN playlist_items pi ON p.id = pi.playlistId WHERE pi.url = :url")
    suspend fun getPlaylistsForUrl(url: String): List<Playlist>
}

/**
 * 最近播放 —— 与歌单完全独立的一张表（见 [RecentPlay]）。
 * 只由播放侧写入（每次换曲记一条），UI 侧只读，没有改名/删除/调序这类操作。
 */
@Dao
interface RecentPlayDao {
    /** 时间由近到远；limit 传 [RecentPlay.LIMIT]（Room 的 LIMIT 支持绑定变量） */
    @Query("SELECT * FROM recent_plays ORDER BY playedAt DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<RecentPlay>>

    /** 同一首再播 = 覆盖 playedAt（url 是主键），不会出现重复行 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecentPlay)

    /** 裁剪到最新 [limit] 条（播放时顺手调用，表不会无限长大） */
    @Query(
        "DELETE FROM recent_plays WHERE url NOT IN " +
            "(SELECT url FROM recent_plays ORDER BY playedAt DESC LIMIT :limit)"
    )
    suspend fun trim(limit: Int)

    /** 曲目从库里删除 / 改名时同步清理，避免留下点不开的幽灵记录 */
    @Query("DELETE FROM recent_plays WHERE url IN (:urls)")
    suspend fun deleteByUrls(urls: List<String>)

    @Query("UPDATE OR REPLACE recent_plays SET url = :newUrl WHERE url = :oldUrl")
    suspend fun renameUrl(oldUrl: String, newUrl: String)

    @Query("DELETE FROM recent_plays")
    suspend fun clearAll()
}

@Dao
interface SettingDao {
    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun set(entry: SettingEntry)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)

    /**
     * 一次性清理旧格式的「派生设置键」（key 里带 url / 封面地址的那些缓存）：
     *  - `stream_gain_*`：增益缓存，只写不读，播一首留一行；
     *  - `accent_url_*` / `lyric_off_*`：用 `String.hashCode()`（32 位）做键，
     *    理论上不同 url 会撞上同一个键，现已换成 MD5 键 + 新前缀。
     *
     * 用 substr 前缀比较而不是 LIKE：LIKE 里的 `_` 是单字符通配符，
     * `key LIKE 'lyric_off_%'` 会把新键 `lyric_off2_...` 一起匹配掉。
     */
    @Query(
        """DELETE FROM settings WHERE
             substr(key, 1, 12) = 'stream_gain_' OR
             substr(key, 1, 11) = 'accent_url_' OR
             substr(key, 1, 10) = 'lyric_off_'"""
    )
    suspend fun deleteLegacyDerivedKeys()
}

@Dao
interface MetaCacheDao {
    @Query("SELECT * FROM meta_cache WHERE url = :url LIMIT 1")
    suspend fun get(url: String): MetaCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entry: MetaCache)

    @Query("DELETE FROM meta_cache WHERE url = :url")
    suspend fun delete(url: String)

    @Query("DELETE FROM meta_cache")
    suspend fun clearAll()
}
