package com.grape.player.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 媒体项 —— 对应 Web 版 MediaItem。
 * url 是 STRM 解析出的 WebDAV 直链，作为唯一标识。
 *
 * 元数据分两套存放（对应 Embedded_ / Using_ 两组命名）：
 * - Using_（title/artist/album/year/trackNumber/discNumber/coverUrl + lyric 列 + lyrics 表）：当前采用的显示值；
 * - Embedded_（embXxx）：文件内嵌标签快照，封面落盘 EmbeddedCovers/，歌词存 embLyric；
 *   只有「清除数据库」才会删除，任何编辑/补全都不可覆盖。
 * 在线刮削结果不再留快照，直接只填空写进 Using_（Online_ 快照列已于 v6 移除）。
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["url"], unique = true),
        // 列表查询按 isVideo 过滤、按 folder 排序/分组，补索引避免大库整表扫
        Index(value = ["isVideo"]),
        Index(value = ["folder"]),
    ],
)
data class MediaItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val year: Int = 0,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    val folder: String = "",
    val coverUrl: String = "",
    val lyric: String = "",
    val replayGainGain: Float = 0f,
    val replayGainPeak: Float = 0f,
    val lufs: Float = 0f,
    val peak: Float = 0f,
    val loudnessTarget: Int = -16,
    val coverage: Float = 0f,
    val isVideo: Boolean = false,
    /** 音轨号（0 = 未设置） */
    val trackNumber: Int = 0,
    /** 碟号（0 = 未设置） */
    val discNumber: Int = 0,
    /**
     * Using_ 元数据来源标记（来源可追溯）：
     * 0=未补全（纯文件名推导）/ 1=内嵌标签 / 2=在线刮削 / 3=手动编辑。
     */
    val metaSource: Int = 0,

    // ===== Embedded_：文件内嵌标签快照 =====
    val embTitle: String = "",
    val embArtist: String = "",
    val embAlbum: String = "",
    val embYear: Int = 0,
    val embTrackNumber: Int = 0,
    val embDiscNumber: Int = 0,
    /** 内嵌封面（EmbeddedCovers/ 下的本地 file:// 路径） */
    val embCoverUrl: String = "",
    /** Embedded_歌词（内嵌 LRC 原文） */
    val embLyric: String = "",
    /**
     * 内嵌快照读取时间（0 = 还没读过）。
     *
     * 用途：补全时据此挑「快照还没回填过」的曲目。不能只靠 embXxx 是否为空判断 ——
     * 文件本身没有标签时那些列永远为空，会每次补全都把整个文件头重新下一遍。
     * 读到标签就置位（即使标签内容是空的）；读取失败（网络/解析异常）保持 0，下次再试。
     */
    val embReadAt: Long = 0,
    /**
     * 上一次「在线补全试过但一无所获」的时间（epoch 毫秒，0 = 没失败过）。
     *
     * 为什么需要：`getNeedingMeta` 的条件是「某个字段还空着」，而上游对一部分歌**永远**给不出
     * 对应字段（最常见的是年份，很多老歌在 QQ/酷狗/网易云都不带 year）。没有失败记忆时，
     * 这些歌每次扫描结束都会进队列、发一轮搜索请求、拿回同一个空结果，然后下次扫描再来一遍 ——
     * 请求白烧，进度条上的 total 还看不出来。歌词侧早有等价机制（[LyricEntry.missAt]），这里补齐。
     *
     * 语义：只有「搜索正常回答过、但这一轮没有任何字段被填上」才写；网络异常/熔断不写
     * （那属于这次没问成，不该让这首歌白白等一个 TTL）。TTL 内不再进队列，
     * 过期后自动重试；用户主动「清空元数据」会把它归零，立刻给一次机会。
     */
    val metaMissAt: Long = 0,
) {
    companion object {
        /** metaSource：Using_ 元数据来源 —— 未补全（文件名推导） */
        const val META_SOURCE_NONE = 0
        /** metaSource：来自内嵌标签 */
        const val META_SOURCE_EMBEDDED = 1
        /** metaSource：来自在线刮削 */
        const val META_SOURCE_ONLINE = 2
        /** metaSource：用户手动编辑（优先级最高，在线/内嵌采用都不会自动冲掉手动值语义） */
        const val META_SOURCE_MANUAL = 3
    }
}

/**
 * 列表用的媒体项轻量投影 —— 不取 lyric / embLyric 两列歌词正文。
 *
 * 这两列每首 3~8KB，3000 首 × 2 份就是几十 MB 的 String 常驻内存；而且列表 Flow
 * 每次重发都会整表重读重建（补全期间每 250ms 一次），是「大库滑动掉帧 + 补全时发烫」
 * 的主源头。列表展示与播放队列的运行都不需要歌词正文（需要时按 url 回表取 [MediaItem.lyric]）。
 */
data class MediaItemLite(
    val id: Long,
    val url: String,
    val title: String,
    val artist: String,
    val album: String,
    val year: Int,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String,
    val coverUrl: String,
    val replayGainGain: Float,
    val replayGainPeak: Float,
    val isVideo: Boolean,
    val trackNumber: Int,
    val discNumber: Int,
    val metaSource: Int,
) {
    /** 是否归类为可播放音频（与 [MediaItem] 的 isVideo 语义一致） */
    val isMusic: Boolean get() = !isVideo

    companion object {
        /** 从完整 [MediaItem] 投影出轻量版（丢歌词正文，用于播放队列等不需要歌词的场景） */
        fun from(item: MediaItem) = MediaItemLite(
            id = item.id,
            url = item.url,
            title = item.title,
            artist = item.artist,
            album = item.album,
            year = item.year,
            durationMs = item.durationMs,
            sizeBytes = item.sizeBytes,
            folder = item.folder,
            coverUrl = item.coverUrl,
            replayGainGain = item.replayGainGain,
            replayGainPeak = item.replayGainPeak,
            isVideo = item.isVideo,
            trackNumber = item.trackNumber,
            discNumber = item.discNumber,
            metaSource = item.metaSource,
        )
    }
}

/** 歌词缓存 —— 对应 KV 命名空间 lyric */
@Entity(tableName = "lyrics", indices = [Index(value = ["url"], unique = true)])
data class LyricEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val lrc: String,
    val translatedLrc: String = "",
    val offsetMs: Long = 0,
    /**
     * 「已确认无歌词」负缓存标记（epoch 毫秒，0 = 有歌词/未标记）。
     * 内嵌 + 在线都拿不到歌词时写一条 lrc 为空、missAt=now 的行，
     * TTL 内播放同一首直接给空，不再反复拉内嵌（Range 下载）和在线搜索；
     * 过期后自动重试（网络恢复 / 在线源数据补齐后能重新拿到）。
     */
    val missAt: Long = 0,
    /**
     * 这份正文是谁给的：[LyricSource] 里的 key —— qq / kugou / netease / embedded / manual，
     * 空串 = 升级前写入的老行，出处已不可考。
     *
     * 只有它能回答「用户说歌词变了，到底是换了源、上游改了词、还是他自己套用过」。
     */
    val source: String = "",
    /**
     * 这份**正文**的写入时间（epoch 毫秒，0 = 老数据没记录）。
     *
     * 注意：调时间偏移、标负缓存、改歌名都不算「重写正文」，不会更新它 ——
     * 它表示的是「词是什么时候拿到手的」，不是「这行最后一次被碰的时间」。
     */
    val updatedAt: Long = 0,
    /**
     * 内嵌歌词格式：同一时间点出现两行时，是不是「译文在前、原文在后」。
     * false（默认）= 先原词后翻译，true = 先翻译后原词。
     *
     * 按曲目存而不是全局设置：双语内嵌词的排列方式**各首不一样**（中文歌几乎都是原词在上，
     * 英文/日文歌的汉化词常反过来），一个全局开关必然把另一批歌显示错。
     * 和时间偏移一样属于「这首词的显示方式」，所以放在歌词行上，跟着歌一起删改。
     */
    val translationFirst: Boolean = false,
)

/** 歌单 —— 对应 KV 命名空间 playlist */
@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 歌单-歌曲关联（多对多） */
@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "url"],
    indices = [Index(value = ["playlistId"]), Index(value = ["url"])],
)
data class PlaylistItem(
    val playlistId: Long,
    val url: String,
    val position: Int = 0,
)

/**
 * 歌单曲目数统计 —— 聚合查询结果，不是独立表。
 * 歌单列表页要显示「N 首」，不能在 LazyColumn 里为每个歌单各起一条 Flow。
 */
data class PlaylistCount(
    val playlistId: Long,
    val itemCount: Int,
)

/**
 * 最近播放 —— 歌单页里「最近播放」这个虚拟歌单的数据源，**不是**普通歌单。
 *
 * 单独建表而不写进 playlists / playlist_items 的原因：
 * 它必须永远排在歌单列表第一位、不可改名、不可删除、不可手动加减曲目，
 * 放在同一张表里就得处处特判「哪个 id 是它」。
 *
 * url 作主键：同一首重复播放只更新时间（REPLACE 即 upsert），天然去重；
 * 写入时按 [LIMIT] 裁剪，表内最多 100 条。
 */
@Entity(tableName = "recent_plays")
data class RecentPlay(
    @PrimaryKey val url: String,
    /** 最近一次播放的时间（epoch 毫秒），列表按它倒序 */
    val playedAt: Long,
) {
    companion object {
        /** 最近播放最多保留 / 展示的曲目数 */
        const val LIMIT = 100
    }
}

/** 通用设置 —— 对应 setting.json */
@Entity(tableName = "settings")
data class SettingEntry(
    @PrimaryKey val key: String,
    val value: String,
)

/** 刮削元数据缓存 —— 对应 KV 命名空间 self（只填空不覆盖的来源数据） */
@Entity(tableName = "meta_cache", indices = [Index(value = ["url"], unique = true)])
data class MetaCache(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: Int = 0,
    val coverUrl: String = "",
    val lyric: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
)
