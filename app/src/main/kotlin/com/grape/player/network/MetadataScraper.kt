package com.grape.player.network

import com.grape.player.data.MediaItem
import com.grape.player.util.LyricMatch
import com.grape.player.util.LyricQuality
import com.grape.player.util.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 元数据刮削 —— 对应原后端的 QQ 音乐 → 酷狗 → 网易云「只填空不覆盖」策略。
 * 扫描时用 search()（单条最优，一首歌只取第一个有结果的源）补空字段；
 * 编辑弹窗用 searchMulti / searchCovers / searchLyrics 取候选。
 *
 * 所有第三方请求都过 [SourceRateGuard]：同一源全局最小间隔（QQ 600ms）+ 连续失败熔断。
 * 后台补全的 4 个 worker 与前台搜索/歌词匹配共用同一道闸，全局速率与 worker 数无关；
 * 源被限流时熔断生效，请求 fast-fail，交给下一家源兜底，绝不往挂掉的接口继续轰。
 */
class MetadataScraper(
    private val client: OkHttpClient = backgroundClient(),
) {

    data class ScrapedMeta(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val year: Int = 0,
        /** 音轨号（0 = 源没给） */
        val trackNumber: Int = 0,
        /** 碟号（0 = 源没给） */
        val discNumber: Int = 0,
        val coverUrl: String = "",
        val lyric: String = "",
        /** 曲目时长（毫秒）；纯 WebDAV 扫描拿不到时长，靠刮削补 */
        val durationMs: Long = 0,
    )

    /** 综合搜索候选（编辑弹窗用） */
    data class SearchResult(
        val source: String,
        val title: String,
        val artist: String,
        val album: String,
        val year: String,
        val trackNumber: String,
        val discNumber: String,
        val coverUrl: String,
        /** 原始条目，预览歌词时回传（这里用 songmid / 索引） */
        val rawId: String = "",
        /** QQ 数字歌曲 id，GetPlayLyricInfo 拉翻译歌词时需要 */
        val songId: Long = 0,
        val durationMs: Long = 0,
    )

    /** 歌词候选 */
    data class LyricResult(
        val source: String,
        val title: String,
        val artist: String,
        val lyric: String,
        val translation: String,
    )

    /**
     * 首播自动匹配歌词的三种结论 —— 区别在于「要不要写负缓存」：
     * - [Found] 拿到可用歌词；
     * - [NotFound] 所有启用的源都正常响应、但确实没有这首的词（可以负缓存 24h）；
     * - [Failed] 有源没开 / 网络异常 / 关键词为空，这次根本没问出结果（绝不能负缓存，
     *   否则一次 Wi-Fi 抖动就让这首歌 24 小时没词）。
     */
    sealed class LyricFetch {
        class Found(val lrc: String, val translation: String, val source: String) : LyricFetch()
        object NotFound : LyricFetch()
        object Failed : LyricFetch()
    }

    /**
     * 扫描用：按标题+艺术家搜索，按 QQ → 酷狗 → 网易云 的固定优先级**按字段合并**。
     *
     * 老实现是「第一个有候选的源整首用它」，问题是「有候选」不等于「字段齐」：
     * QQ 候选封面为空、酷狗 candidates[0] 没封面（Image 多是歌手头像，已被域名白名单丢掉），
     * 一旦短路返回，后面两家就永远没机会补那个空字段，待补清单也就永远清不完。
     *
     * 现在每轮只补仍然空着的字段，字段凑齐（艺人/专辑/年份/封面）立刻停 ——
     * 正常情况（QQ 全齐）仍然只发一次请求，只有真缺字段时才会多问下一家。
     *
     * 候选选择不再用 `firstOrNull()`：扫描阶段的标题常带 `08.` 前缀、艺人恒为空，
     * 把结果交给搜索排序等于赌「第一条正好是对的那个版本」。现在关键词先归一化
     * （去音轨前缀 / 去 feat / 占位艺人丢弃），候选按 标题 0.62 + 艺人 0.23 + 时长 0.15 排序取最高。
     * 拒绝线比歌词那条松得多（只看标题 ≥ [META_MIN_TITLE_SIM]，不看总分）：元数据写错不可自愈，
     * 但这里拿不到可靠时长，套歌词的 0.58 会误杀大量能补到封面的歌 —— 具体权衡见该常量注释。
     * 全部候选都过不了线时返回 null，字段**保持为空**等下次补全，绝不拿弱匹配去填。
     * 后续源借字段仍要求标题相似度 ≥ [MERGE_MIN_TITLE_SIM]，否则不合并（防跨源串味）。
     */
    suspend fun search(
        title: String,
        artist: String,
        providers: List<String> = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE),
        /** 当前音轨真实时长（拿不到给 0）：只用于区分录音室版与现场版，未知时按中性分算 */
        durationMs: Long = 0L,
    ): ScrapedMeta? = withContext(Dispatchers.IO) {
        val keys = metaKeywords(title, artist)
        if (keys.isEmpty()) return@withContext null
        var acc: ScrapedMeta? = null
        // 熔断中的源跳过（QQ 被限流时交给下一家补，下次补全再回来试 QQ）
        for (p in listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE)
            .filter { it in providers && !SourceRateGuard.isOpen(it) }) {
            val base = acc
            if (base != null && base.isComplete()) break
            // 关键词降级：带版本说明（(Live)）搜不到时去掉括号重试一次，与歌词匹配同一套策略
            var ranked: List<ScoredCandidate> = emptyList()
            for (key in keys) {
                val candidates = when (p) {
                    SOURCE_QQ -> searchQQMulti(key)
                    SOURCE_KUGOU -> searchKugouMulti(key)
                    SOURCE_NETEASE -> searchNeteaseMulti(key)
                    else -> emptyList()
                }
                ranked = scoreCandidates(candidates, title, artist, durationMs)
                // 没有一条过得了低拒绝线 → 用降级关键词再试一次，别把「搜到了别的歌」当答案
                if (ranked.isNotEmpty() && ranked.first().titleSim >= META_MIN_TITLE_SIM) break
            }
            // 第一家给出候选的整条当基准；后续源只在「确实是同一首歌」时才借字段 ——
            // 酷狗搜「周杰伦 晴天」第一条常是「晴天 (Live)」，无脑借它的封面/专辑会串味
            val pick = if (base == null) {
                ranked.firstOrNull { it.titleSim >= META_MIN_TITLE_SIM }
            } else {
                ranked.firstOrNull { it.titleSim >= MERGE_MIN_TITLE_SIM }
            } ?: continue
            acc = base?.mergeMissing(pick.c.toScrapedMeta()) ?: pick.c.toScrapedMeta()
        }
        acc
    }

    /** 搜索关键词：先「艺人 + 去音轨前缀的标题」，再退化到去掉括号版本说明的标题 */
    private fun metaKeywords(title: String, artist: String): List<String> {
        val cleanTitle = LyricMatch.titleForSearch(title)
        // 多艺人（`周杰伦/费玉清`）把分隔符换成空格：拼成「A B 歌名」比「A/B 歌名」搜得准
        val artistPart = if (LyricMatch.isPlaceholderArtist(artist)) {
            ""
        } else {
            artist.replace(Regex("[/,;、＆]"), " ").replace(Regex("\\s+"), " ").trim()
        }
        // 标题空了就什么都不搜：只拿艺人名去搜，返回的是该歌手的热门歌，必错
        if (cleanTitle.isBlank()) return emptyList()
        fun build(t: String) = listOf(artistPart, t).filter { it.isNotBlank() }.joinToString(" ")
        val first = build(cleanTitle)
        val second = build(LyricMatch.stripVersionTags(cleanTitle))
        return if (second != first && second.isNotBlank()) listOf(first, second) else listOf(first)
    }

    /** 候选打分结果：score 是加权总分，titleSim 单独留着（下限只看标题，不看总分） */
    private class ScoredCandidate(val c: SearchResult, val score: Double, val titleSim: Double)

    /**
     * 打分排序（标题 0.62 + 艺人 0.23 + 时长 0.15），按总分从高到低。
     * 艺人未知（扫描后最常见）时给中性分，不让「没填艺人」把正确结果判死；
     * 时长未知（补全时几乎总是 0）同样给中性分，绝不用它一票否决。
     */
    private fun scoreCandidates(
        candidates: List<SearchResult>,
        wantTitle: String,
        wantArtist: String,
        wantDurationMs: Long,
    ): List<ScoredCandidate> {
        val nt = LyricMatch.normTitle(wantTitle)
        if (nt.length < 2) return emptyList()
        val na = LyricMatch.normArtist(wantArtist)
        return candidates.mapNotNull { c ->
            val ct = LyricMatch.normTitle(c.title)
            if (ct.isBlank()) return@mapNotNull null
            val titleSim = LyricMatch.similarity(nt, ct)
            val artistSim = if (na.isBlank() || LyricMatch.isPlaceholderArtist(c.artist)) {
                NEUTRAL_SIM
            } else {
                LyricMatch.artistSimilarity(na, LyricMatch.normArtist(c.artist))
            }
            ScoredCandidate(
                c = c,
                score = 0.62 * titleSim + 0.23 * artistSim +
                    0.15 * durationSimilarity(wantDurationMs, c.durationMs),
                titleSim = titleSim,
            )
        }.sortedByDescending { it.score }
    }

    /** 后台补全关心的字段是否都齐了 —— 齐了就不必再问下一家源（省请求） */
    private fun ScrapedMeta.isComplete(): Boolean =
        title.isNotBlank() && artist.isNotBlank() && album.isNotBlank() &&
            year > 0 && coverUrl.isNotBlank()

    /** 只补空：已经拿到的字段不被后面的源覆盖（与「只填空不覆盖」的整体策略一致） */
    private fun ScrapedMeta.mergeMissing(other: ScrapedMeta): ScrapedMeta = copy(
        title = title.ifBlank { other.title },
        artist = artist.ifBlank { other.artist },
        album = album.ifBlank { other.album },
        year = if (year > 0) year else other.year,
        trackNumber = if (trackNumber > 0) trackNumber else other.trackNumber,
        discNumber = if (discNumber > 0) discNumber else other.discNumber,
        coverUrl = coverUrl.ifBlank { other.coverUrl },
        durationMs = if (durationMs > 0) durationMs else other.durationMs,
    )

    private fun SearchResult.toScrapedMeta(): ScrapedMeta = ScrapedMeta(
        title = title,
        artist = artist,
        album = album,
        year = year.toIntOrNull() ?: 0,
        trackNumber = trackNumber.toIntOrNull() ?: 0,
        discNumber = discNumber.toIntOrNull() ?: 0,
        coverUrl = coverUrl,
        durationMs = durationMs,
    )

    /**
     * 综合搜索多条候选（编辑弹窗用）：按 [providers] 里启用的源查询，
     * 顺序固定 QQ → 酷狗 → 网易云（与存储顺序无关），去重后限量。
     * 歌名/艺术家/专辑可任意组合。
     *
     * 老实现无条件串三家：「单独搜索封面」因此永远绕过设置里的源开关 ——
     * 用户只勾了 QQ，封面却还是会出现酷狗的结果，与同一弹窗里的综合搜索行为不一致。
     */
    suspend fun searchMulti(
        title: String,
        artist: String,
        album: String = "",
        limit: Int = 8,
        providers: List<String> = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE),
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val keyword = listOf(artist, title, album).filter { it.isNotBlank() }.joinToString(" ")
            val result = mutableListOf<SearchResult>()
            // 按源分配配额，而不是「凑够 limit 就停」：后者在 QQ 一次返回十几条时
            // 会直接把酷狗/网易云整段跳过，全勾三家却只看得到 QQ 的结果
            val enabled = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE).filter { it in providers }
            val perSource = if (enabled.isEmpty()) limit else (limit / enabled.size).coerceAtLeast(4)
            if (SOURCE_QQ in providers) result.addAll(searchQQMulti(keyword).take(perSource))
            if (SOURCE_KUGOU in providers) result.addAll(searchKugouMulti(keyword).take(perSource))
            if (SOURCE_NETEASE in providers) result.addAll(searchNeteaseMulti(keyword).take(perSource))
            val seen = mutableSetOf<String>()
            result.filter {
                val key = "${it.title}|${it.artist}".lowercase()
                key.isNotBlank() && seen.add(key)
            }.take(limit)
        }

    /**
     * 手动搜索（编辑弹窗分源选项卡用）：按 providers 并行查询各源，
     * 返回「源名（QQ音乐/酷狗音乐/网易云音乐）→ 候选列表」。
     * 单个源失败不影响其他源；空结果的源不出现在返回值里。
     */
    suspend fun searchMultiBySource(
        title: String,
        artist: String,
        album: String = "",
        providers: List<String> = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE),
        limitPerSource: Int = 8,
    ): Map<String, List<SearchResult>> = withContext(Dispatchers.IO) {
        // 关键词降级：完整词（歌手+歌名+专辑）搜不到时退化为歌手+歌名 ——
        // 实测酷狗等源对超长关键词（长专辑名）一律返回 0 条，短词正常（与后台补全的关键词策略一致）
        val keywordFull = listOf(artist, title, album).filter { it.isNotBlank() }.joinToString(" ")
        val keywordShort = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        suspend fun searchByKey(key: String, keyword: String): List<SearchResult> = when (key) {
            SOURCE_QQ -> searchQQMulti(keyword)
            SOURCE_KUGOU -> searchKugouMulti(keyword)
            else -> searchNeteaseMulti(keyword)
        }.take(limitPerSource)

        android.util.Log.i("MetaSearch", "start: full=[$keywordFull], short=[$keywordShort], providers=$providers")
        coroutineScope {
            listOf(
                SOURCE_QQ to "QQ音乐",
                SOURCE_KUGOU to "酷狗音乐",
                SOURCE_NETEASE to "网易云音乐",
            )
                .filter { (key, _) -> key in providers }
                .map { (key, label) ->
                    async {
                        key to runCatching {
                            val direct = searchByKey(key, keywordFull)
                            if (direct.isNotEmpty() || keywordShort.isBlank() || keywordShort == keywordFull) direct
                            else searchByKey(key, keywordShort)
                        }
                            .onFailure { android.util.Log.e("MetaSearch", "$label 搜索异常", it) }
                            .getOrDefault(emptyList())
                            .also { android.util.Log.i("MetaSearch", "$label($key) -> ${it.size} 条") }
                    }
                }
                .awaitAll()
                .toMap()
                .filterValues { it.isNotEmpty() }
                .also { android.util.Log.i("MetaSearch", "合并结果源: ${it.keys}") }
        }
    }

    /** 单独搜封面：从综合结果里取有封面的（同样只查 [providers] 里启用的源） */
    suspend fun searchCovers(
        title: String,
        artist: String,
        album: String = "",
        providers: List<String> = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE),
    ): List<SearchResult> =
        withContext(Dispatchers.IO) {
            searchMulti(title, artist, album, 16, providers).filter { it.coverUrl.isNotBlank() }
        }

    /**
     * 单独搜歌词（分源版）：只查 [providers] 里启用的源，返回「源 key → 歌词候选」。
     * key 与设置里的源标识一致（qq/kugou/netease），编辑弹窗按它出页签左右滑切换。
     * 没出结果的源不会进 Map，页签里就不会出现空页签。
     */
    suspend fun searchLyricsBySource(
        title: String,
        artist: String,
        providers: List<String> = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE),
    ): Map<String, List<LyricResult>> = withContext(Dispatchers.IO) {
        val keyword = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        if (keyword.isBlank()) return@withContext emptyMap()
        coroutineScope {
            listOf(
                SOURCE_QQ to "QQ音乐",
                SOURCE_KUGOU to "酷狗音乐",
                SOURCE_NETEASE to "网易云音乐",
            )
                .filter { (key, _) -> key in providers }
                .map { (key, label) ->
                    // 各源并行：原先串行，最慢的一家决定整体耗时
                    async {
                        key to runCatching { lyricsOfSource(key, keyword) }
                            .onFailure { android.util.Log.e("LyricSearch", "$label 取词异常", it) }
                            .getOrDefault(emptyList())
                            .also { android.util.Log.i("LyricSearch", "$label($key) -> ${it.size} 条") }
                    }
                }
                .awaitAll()
                .toMap()
                .filterValues { it.isNotEmpty() }
        }
    }

    /** 单独搜歌词（平铺版）：按 QQ → 酷狗 → 网易云顺序拼起来，供「应用候选后兜底取词」用 */
    suspend fun searchLyrics(
        title: String,
        artist: String,
        providers: List<String> = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE),
    ): List<LyricResult> = searchLyricsBySource(title, artist, providers).values.flatten()

    /**
     * 单源取词。
     * QQ 逐条拉正文+独立译文，酷狗 m3ws 一次拿正文+译文（老接口兜底），网易云按歌曲 id 直取（自带译文）。
     */
    private suspend fun lyricsOfSource(key: String, keyword: String): List<LyricResult> = when (key) {
        SOURCE_QQ -> fetchQQSongList(keyword, 5).mapNotNull { song ->
            val (lyric, translation) = fetchQQLyricFull(song) ?: return@mapNotNull null
            if (lyric.isEmpty() && translation.isEmpty()) return@mapNotNull null
            LyricResult(
                source = "QQ音乐",
                title = song.optString("songname").ifEmpty { song.optString("songname_original") },
                artist = song.optJSONArray("singer")?.let { arr ->
                    (0 until arr.length()).joinToString("/") { arr.getJSONObject(it).optString("name") }
                } ?: "",
                lyric = lyric,
                translation = translation,
            )
        }
        // 酷狗：歌名/艺人搜索拿 FileHash，再并发逐首直取。优先走 m3ws get_krc（一次请求带译文），
        // 拿不到正文才退回老的 krcs+download 两步接口（只有原文）
        SOURCE_KUGOU -> coroutineScope {
            fetchKugouSongList(keyword, 3).map { song ->
                async {
                    val hash = song.optString("FileHash")
                    val durationMs = song.optLong("Duration", 0L) * 1000L
                    val krc = fetchKugouKrcByHash(
                        hash,
                        stripHighlight(song.optString("SongName")),
                        stripHighlight(song.optString("SingerName")),
                        durationMs,
                    )
                    val lrc = krc?.first ?: fetchKugouLyricByHash(hash) ?: return@async null
                    LyricResult(
                        source = "酷狗音乐",
                        title = stripHighlight(song.optString("SongName")),
                        artist = stripHighlight(song.optString("SingerName")),
                        lyric = lrc,
                        translation = krc?.second.orEmpty(),
                    )
                }
            }.awaitAll().filterNotNull()
        }
        // 网易云：按歌曲 id 直拉歌词，自带独立译文
        SOURCE_NETEASE -> coroutineScope {
            fetchNeteaseSongList(keyword, 3).map { song ->
                async {
                    val id = song.optLong("id")
                    if (id <= 0L) return@async null
                    val (lrc, trans) = fetchNeteaseLyric(id) ?: return@async null
                    LyricResult(
                        source = "网易云音乐",
                        title = song.optString("name"),
                        artist = song.optJSONArray("ar")?.let { arr ->
                            (0 until arr.length()).joinToString("/") { arr.getJSONObject(it).optString("name") }
                        } ?: "",
                        lyric = lrc,
                        translation = trans,
                    )
                }
            }.awaitAll().filterNotNull()
        }
        else -> emptyList()
    }

    /**
     * @param strict true 时把网络/解析异常抛给调用方（首播自动匹配需要区分
     *               「源说没有」和「这次没问到」，后者不能写负缓存）；false 时吞掉返回空表。
     */
    private suspend fun searchQQMulti(keyword: String, strict: Boolean = false): List<SearchResult> {
        return try {
            fetchQQSongList(keyword, 12).map { song ->
                // 注意：client_search_cp 返回的是扁平字段（albumname/albummid/pubtime/interval），
                // 并没有嵌套的 album 对象；两种形态都兼容，否则专辑与封面永远为空。
                val nestedAlbum = song.optJSONObject("album")
                val albumName = nestedAlbum?.optString("name").orEmpty()
                    .ifEmpty { song.optString("albumname") }
                val albumMid = nestedAlbum?.optString("mid").orEmpty()
                    .ifEmpty { song.optString("albummid") }
                val cover = if (albumMid.isNotEmpty())
                    "https://y.qq.com/music/photo_new/T002R300x300M000$albumMid.jpg" else ""
                val year = song.optString("year").ifEmpty { yearFromPubTime(song.optLong("pubtime", 0L)) }
                SearchResult(
                    source = "QQ音乐",
                    title = song.optString("songname").ifEmpty { song.optString("songname_original") },
                    artist = song.optJSONArray("singer")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.getJSONObject(it).optString("name") }
                    } ?: "",
                    album = albumName,
                    year = year,
                    // 2026-11 实测：client_search_cp 的返回里根本没有 track / disc / year 字段
                    // （字段表只有 belongCD、cdIdx、pubtime），老写法 optString("track") 永远拿到空串
                    // → 音轨号恒为 0 → 详情页那一列整列显示「–」、多碟 DiscHeader 永不出现。
                    // 正解：音轨号在 cdIdx（1 基，《晴天》在《叶惠美》里 cdIdx=3），
                    //       碟号在 belongCD（0 基，+1 换 1 基；0 与「未填」在详情页等价，都是第 1 碟）。
                    trackNumber = song.optString("track").ifEmpty {
                        song.optInt("cdIdx", 0).takeIf { it > 0 }?.toString() ?: "0"
                    },
                    discNumber = song.optString("disc").ifEmpty {
                        song.optInt("belongCD", -1).takeIf { it >= 0 }?.plus(1)?.toString() ?: "0"
                    },
                    coverUrl = cover,
                    rawId = song.optString("songmid"),
                    songId = song.optLong("songid"),
                    durationMs = song.optLong("interval", 0L) * 1000L,
                )
            }
        } catch (e: Exception) {
            if (strict) throw e
            emptyList()
        }
    }

    /** pubtime 是秒级时间戳，转成发行年份 */
    private fun yearFromPubTime(pubTime: Long): String {
        if (pubTime <= 0L) return "0"
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = pubTime * 1000L
        return cal.get(java.util.Calendar.YEAR).toString()
    }

    private suspend fun searchKugouMulti(keyword: String, strict: Boolean = false): List<SearchResult> {
        return try {
            fetchKugouSongList(keyword, 12).map { song ->
                SearchResult(
                    source = "酷狗音乐",
                    // 部分搜索接口会给命中的关键词套 <em> 高亮，串进艺人名后
                    // 「歌名 - 艺人」再拿去 m3ws 取词就匹配不上了
                    title = stripHighlight(song.optString("SongName")),
                    artist = stripHighlight(song.optString("SingerName")),
                    album = song.optString("AlbumName"),
                    // 2026-11 实测：响应里已没有 Year 字段，发行时间在 PublishDate（"2016-06-24"），
                    // 取前导数字作年份；音轨号/碟号酷狗搜索接口不提供（QQ 有 track/disc 字段）。
                    year = song.optString("Year").ifEmpty {
                        song.optString("PublishDate").takeWhile { it.isDigit() }
                    }.ifEmpty { "0" },
                    trackNumber = "0",
                    discNumber = "0",
                    // 2026-11 起酷狗接口封面字段改名：旧 Cover 已从响应里移除（返回 null），
                    // AlbumImage 恒为空，图片现在多数落在 Image —— 但 Image **不是专辑封面**：
                    // 实测 5 条里 4 条是歌手头像（singerimg.kugou.com/uploadpic/softhead/{size}/…），
                    // 只有 imge.kugou.com/stdmusic 才是真专辑图。以前不分域名全盘采用，结果是把
                    // 歌手照片当封面刮进库。这里做域名白名单，头像直接丢弃（宁可空着，交给下一家源补）。
                    coverUrl = kugouCoverUrl(song),
                    rawId = song.optString("FileHash"),
                    durationMs = song.optLong("Duration", 0L) * 1000L,
                )
            }
        } catch (e: Exception) {
            if (strict) throw e
            emptyList()
        }
    }

    /**
     * 酷狗封面：只认 imge.kugou.com/stdmusic 这一个图床。
     * Cover / AlbumImage 现在基本是空值，Image 多数是歌手头像（singerimg…/softhead/），
     * 所以按域名白名单取，命中不到就返回空串 —— 交给 search() 的按字段合并去问下一家源。
     */
    private fun kugouCoverUrl(song: JSONObject): String {
        val hit = listOf("Cover", "AlbumImage", "Image")
            .asSequence()
            .map { song.optString(it) }
            .firstOrNull { it.isNotBlank() && it.contains("imge.kugou.com/stdmusic") }
        return hit?.replace("{size}", "300").orEmpty()
    }

    /** 酷狗歌曲搜索列表（songsearch_v2），供综合搜索与歌词搜索共用 */
    private suspend fun fetchKugouSongList(keyword: String, n: Int): List<JSONObject> =
        SourceRateGuard.call(SOURCE_KUGOU) {
            val enc = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://songsearch.kugou.com/song_search_v2?keyword=$enc&page=1&pagesize=$n"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val list = JSONObject(body).optJSONObject("data")?.optJSONArray("lists")
                ?: return@call emptyList()
            (0 until list.length()).map { list.getJSONObject(it) }
        }

    /**
     * 酷狗歌词两步抓取：
     * 1. krcs.kugou.com/search 按 FileHash 找候选（返回 id + accesskey，hash 精确对应这个音质版本的音频）；
     * 2. lyrics.kugou.com/download?fmt=lrc 拉正文（base64，带 BOM 和 \r\n）。
     * 2026-11 实测：下载响应已不含 trans 字段，酷狗翻译歌词只在加密 KRC（3DES）里，此处只取原文。
     */
    private suspend fun fetchKugouLyricByHash(hash: String): String? {
        if (hash.isEmpty()) return null
        return SourceRateGuard.callOr(SOURCE_KUGOU, null) {
            try {
                val searchUrl =
                    "https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&hash=${URLEncoder.encode(hash, "UTF-8")}"
                val searchBody = client.newCall(
                    Request.Builder().url(searchUrl).header("User-Agent", "Mozilla/5.0").build()
                ).execute().use { it.body?.string().orEmpty() }
                val cand = JSONObject(searchBody).optJSONArray("candidates")?.optJSONObject(0)
                    ?: return@callOr null
                val id = cand.optString("id")
                val accessKey = cand.optString("accesskey")
                if (id.isEmpty() || accessKey.isEmpty()) return@callOr null
                val dlUrl =
                    "https://lyrics.kugou.com/download?ver=1&client=pc&id=$id&accesskey=$accessKey&fmt=lrc&charset=utf8"
                val dlBody = client.newCall(
                    Request.Builder().url(dlUrl).header("User-Agent", "Mozilla/5.0").build()
                ).execute().use { it.body?.string().orEmpty() }
                val content = JSONObject(dlBody).optString("content")
                if (content.isEmpty()) return@callOr null
                val lrc = String(java.util.Base64.getDecoder().decode(content), Charsets.UTF_8)
                    .removePrefix("\uFEFF")
                    .replace("\r\n", "\n")
                if (lrc.isBlank()) null else lrc
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 酷狗歌词（m3ws get_krc）—— 一次请求同时拿正文和译文，优先于上面那个两步接口。
     *
     * GET https://m3ws.kugou.com/api/v1/krc/get_krc?keyword=歌名 - 歌手&hash=…&timelength=毫秒
     * 返回 data.lrc（明文 LRC，无需 base64/解密）和 data.landata（`[{type, content}]`）：
     * type=1 是中文译文，type=0 是罗马音（日文歌的 romaji，绝不能当译文用）。
     * content 形如 `[[第1行],[第2行],…]`，条目数与 lrc 行数严格 1:1（实测 Lemon 57/57、Hello 47/47）。
     *
     * 2026-11 实测：lyrics.kugou.com/download?fmt=lrc 那条老路已经拿不到译文（响应无 trans 字段），
     * 而这里能拿到，所以歌词优先走这条；无正文时调用方再退回老接口。
     */
    private suspend fun fetchKugouKrcByHash(
        hash: String,
        title: String,
        artist: String,
        durationMs: Long,
    ): Pair<String, String>? {
        if (hash.isEmpty()) return null
        return SourceRateGuard.callOr(SOURCE_KUGOU, null) {
            try {
                // 酷狗要求 keyword 是「歌名 - 歌手」，顺序反了会匹配不到
                val keyword = listOf(title.trim(), artist.trim()).filter { it.isNotBlank() }.joinToString(" - ")
                val url = "https://m3ws.kugou.com/api/v1/krc/get_krc" +
                    "?keyword=${URLEncoder.encode(keyword, "UTF-8")}" +
                    "&hash=${URLEncoder.encode(hash, "UTF-8")}" +
                    "&timelength=${durationMs.coerceAtLeast(0)}"
                val body = client.newCall(
                    Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                ).execute().use { it.body?.string().orEmpty() }
                val data = JSONObject(body).optJSONObject("data") ?: return@callOr null
                val lrc = data.optString("lrc")
                if (lrc.isBlank()) return@callOr null
                // landata 有三种形态：JSONArray（有译文）/ 空字符串（无译文）/ 缺失（查不到词）
                lrc to kugouTranslationLrc(lrc, data.optJSONArray("landata"))
            } catch (_: Exception) {
                null
            }
        }
    }

    /**
     * 把酷狗的 landata 译文拼成「带时间戳的译文 LRC」，供 LrcParser.parse(lrc, translation) 按时间并入。
     *
     * 条目是**按行位置**对应的（空行是 `[  ]`），所以逐行贴时间戳即可，不需要另找时间轴。
     * 条目数和 lrc 行数不等时直接返回空 —— 对不齐宁可没有译文，也不能整篇错位。
     */
    private fun kugouTranslationLrc(lrc: String, landata: JSONArray?): String {
        if (landata == null) return ""
        val content = (0 until landata.length())
            .mapNotNull { landata.optJSONObject(it) }
            .firstOrNull { it.optInt("type", -1) == 1 }   // 只取 type=1 译文；type=0 是罗马音
            ?.optString("content")
            .orEmpty()
        if (content.isBlank()) return ""
        // 按括号取，不能按逗号切 —— 译文本身会含逗号（"Yes, I do"），切了就整体错位
        val translated = KUGOU_ITEM.findAll(content).map { it.groupValues[1].trim() }.toList()
        val lines = lrc.lines().filter { it.isNotBlank() }
        if (translated.size != lines.size) return ""
        return lines.mapIndexedNotNull { i, line ->
            val text = translated[i]
            if (text.isBlank()) return@mapIndexedNotNull null
            val tags = KUGOU_TIME_TAG.findAll(line).joinToString("") { it.value }
            if (tags.isEmpty()) null else "$tags$text"
        }.joinToString("\n")
    }

    /**
     * 网易云歌曲搜索（weapi 加密接口，对照 Webdav媒体管理 providers/netease_music.py）：
     * POST https://music.163.com/weapi/cloudsearch/pc，body 为 params + encSecKey 两个表单字段。
     * 加密链：payload → AES(CBC, NONCE) → AES(CBC, 随机 16 位 secKey) = params；
     * secKey 反转后 RSA(65537, MODULUS) = encSecKey。伪造 X-Real-IP 防屏蔽。
     */
    private suspend fun fetchNeteaseSongList(keyword: String, n: Int): List<JSONObject> =
        SourceRateGuard.call(SOURCE_NETEASE) {
            val payload = JSONObject().apply {
                put("s", keyword)
                put("type", 1)
                put("limit", n)
                put("total", "true")
                put("offset", 0)
            }
            val (params, encSecKey) = weapiEncrypt(payload)
            val body = FormBody.Builder()
                .add("params", params)
                .add("encSecKey", encSecKey)
                .build()
            val req = Request.Builder()
                .url("https://music.163.com/weapi/cloudsearch/pc")
                .post(body)
                .header("Referer", "https://music.163.com")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                )
                .header("X-Real-IP", neteaseFakeIp)
                .header("X-Forwarded-For", neteaseFakeIp)
                .build()
            val resp = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val json = JSONObject(resp)
            // 网易云被风控时返回 code=301/-460 之类且无 result，与「真没搜到」（code=200）要区分开
            val code = json.optInt("code", 200)
            if (code != 200) throw SourceRateGuard.RateLimitedException("网易云搜索 code=$code")
            val arr = json.optJSONObject("result")?.optJSONArray("songs") ?: return@call emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }

    private suspend fun searchNeteaseMulti(keyword: String, strict: Boolean = false): List<SearchResult> {
        return try {
            fetchNeteaseSongList(keyword, 12).map { song ->
                val album = song.optJSONObject("al")
                // picUrl 统一 https 并请求 300x300（网易云图床支持 ?param=WxH 缩放参数）。
                // 必须把协议头补回去：原来只 removePrefix("http://") 不补，得到的是
                // "p2.music.126.net/…jpg?param=300y300" 这种无 scheme 的字符串，
                // Coil 会把它当本地文件路径（StringMapper 对无 scheme 的字符串走 File 分支），
                // 于是搜索结果缩略图、套用后的封面预览、以及写进库的 coverUrl 全部加载失败。
                val cover = album?.optString("picUrl").orEmpty()
                    .trim()
                    .substringAfter("://")          // 去掉原有协议（http:// 或 https://）
                    .substringBefore('?')           // 去掉原有尺寸参数
                    .takeIf { it.isNotBlank() }
                    ?.let { "https://$it?param=300y300" }
                    .orEmpty()
                SearchResult(
                    source = "网易云音乐",
                    title = song.optString("name"),
                    artist = song.optJSONArray("ar")?.let { arr ->
                        (0 until arr.length()).joinToString("/") { arr.getJSONObject(it).optString("name") }
                    } ?: "",
                    album = album?.optString("name").orEmpty(),
                    year = yearFromMillis(album?.optLong("publishTime", 0L) ?: 0L),
                    // no 是音轨号；cd 形如 "1" 或 "1/2"，取碟号部分
                    trackNumber = song.optInt("no", 0).let { if (it > 0) it.toString() else "0" },
                    discNumber = song.optString("cd").takeWhile { it.isDigit() }.ifEmpty { "0" },
                    coverUrl = cover,
                    rawId = song.optLong("id").let { if (it > 0) it.toString() else "" },
                    durationMs = song.optLong("dt", 0L),
                )
            }
        } catch (e: Exception) {
            if (strict) throw e
            emptyList()
        }
    }

    /** 网易云歌词：不需要加密，GET 直取；tlyric 是独立译文。无正文返回 null */
    private suspend fun fetchNeteaseLyric(songId: Long): Pair<String, String>? =
        SourceRateGuard.callOr(SOURCE_NETEASE, null) {
            try {
                val url = "https://music.163.com/api/song/lyric?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
                val req = Request.Builder()
                    .url(url)
                    .header("Referer", "https://music.163.com")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
                val json = JSONObject(body)
                val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty().takeIf { it != "null" } ?: ""
                val trans = json.optJSONObject("tlyric")?.optString("lyric").orEmpty().takeIf { it != "null" } ?: ""
                if (lrc.isBlank()) null else lrc to trans
            } catch (_: Exception) {
                null
            }
        }

    /** publishTime 是毫秒时间戳，转发行年份 */
    private fun yearFromMillis(ms: Long): String {
        if (ms <= 0L) return "0"
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        return cal.get(java.util.Calendar.YEAR).toString()
    }

    // ===== 网易云 weapi 加密（对照 netease_music.py）=====

    /** 伪造国内 IP（对会话固定），防境外屏蔽 */
    private val neteaseFakeIp = "112.88.${Random.nextInt(256)}.${Random.nextInt(256)}"

    private fun weapiEncrypt(payload: JSONObject): Pair<String, String> {
        val text = payload.toString()
        val secKey = randomNeteaseKey()
        val params = neteaseAesEncrypt(neteaseAesEncrypt(text, NETEASE_NONCE), secKey)
        return params to rsaEncryptSecKey(secKey)
    }

    private fun neteaseAesEncrypt(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(NETEASE_IV.toByteArray(Charsets.UTF_8)),
        )
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    private fun rsaEncryptSecKey(secKey: String): String {
        // Python 版是先把字符串反转再按字节转 BigInteger，这里保持一致
        val data = BigInteger(1, secKey.reversed().toByteArray(Charsets.UTF_8))
        return data.modPow(BigInteger(NETEASE_PUBKEY, 16), BigInteger(NETEASE_MODULUS, 16))
            .toString(16).padStart(256, '0')
    }

    /** 16 字节随机数 hex 后截取前 16 个字符，与 Python _create_secret_key 一致 */
    private fun randomNeteaseKey(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex().take(16)
    }

    private suspend fun fetchQQSongList(keyword: String, n: Int): List<JSONObject> =
        SourceRateGuard.call(SOURCE_QQ) {
            val enc = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?w=$enc&format=json&n=$n"
            val req = Request.Builder()
                .url(url)
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val json = JSONObject(body)
            // 被限流时接口返回 HTTP 200 但 code != 0，data.song.list 直接缺失。
            // 必须显式识别并抛 RateLimitedException：老代码把限流响应当「没搜到」，
            // 后台补全既不知道退避、前台 matchLyric 还会误写 24h 负缓存。
            val code = json.optInt("code", 0)
            if (code != 0) throw SourceRateGuard.RateLimitedException("QQ 搜索 code=$code")
            val arr = json.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list")
                ?: return@call emptyList()
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }

    /**
     * QQ 歌词完整抓取（对照 Web 版 providers/qq.ts getLyric）：
     * 1. 旧 fcg_query_lyric_new 接口拿正文，偶尔自带译文；
     * 2. 译文为空时走客户端 GetPlayLyricInfo 接口，返回的 trans 是加密 QRC 十六进制串，
     *    解密后转成标准 LRC 译文（3DES + zlib，密钥与 Web 版 qrc-decoder 一致）。
     * 返回（原文, 独立译文）；无正文返回 null。
     */
    private suspend fun fetchQQLyricFull(song: JSONObject): Pair<String, String>? {
        val mid = song.optString("songmid")
        if (mid.isEmpty()) return null
        val (lyric, trans) = fetchQQLyricByMid(mid)
        if (lyric.isEmpty()) return null
        if (trans.isNotEmpty()) return lyric to trans

        val songId = song.optLong("songid")
        if (songId <= 0L) return lyric to ""
        val translation = fetchQrcTranslation(
            songId = songId,
            title = song.optString("songname"),
            artist = song.optJSONArray("singer")?.let { arr ->
                (0 until arr.length()).joinToString("/") { arr.getJSONObject(it).optString("name") }
            } ?: "",
            album = song.optString("albumname"),
            durationSec = song.optLong("interval", 0L),
        ) ?: return lyric to ""
        return lyric to translation
    }

    /** 按 songmid 拉歌词（旧接口），返回（正文, 译文）—— 译文大多数情况下为空 */
    private suspend fun fetchQQLyricByMid(mid: String): Pair<String, String> =
        SourceRateGuard.callOr(SOURCE_QQ, "" to "") {
            val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=1"
            val req = Request.Builder()
                .url(url)
                .header("Referer", "https://y.qq.com/")
                .header("User-Agent", "Mozilla/5.0")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val json = JSONObject(body)
            // 与搜索接口同理：code != 0 是限流信号，抛出去让护栏记失败，
            // 而不是把「限流期间的空歌词」当「这首真没词」
            if (json.optInt("code", 0) != 0) {
                throw SourceRateGuard.RateLimitedException("QQ 歌词 code=${json.optInt("code")}")
            }
            json.optString("lyric") to json.optString("translation")
        }

    /** 客户端 GetPlayLyricInfo 接口：拉加密翻译歌词并解密成 LRC，无译文返回 null */
    private suspend fun fetchQrcTranslation(
        songId: Long,
        title: String,
        artist: String,
        album: String,
        durationSec: Long,
    ): String? = SourceRateGuard.callOr(SOURCE_QQ, null) {
        try {
            fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
            val payload = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("ct", 11); put("cv", "1003006"); put("v", "1003006")
                    put("os_ver", "15"); put("phonetype", "24122RKC7C")
                    put("tmeAppID", "qqmusiclight"); put("nettype", "NETWORK_WIFI"); put("udid", "0")
                })
                put("request", JSONObject().apply {
                    put("method", "GetPlayLyricInfo")
                    put("module", "music.musichallSong.PlayLyricInfo")
                    put("param", JSONObject().apply {
                        put("albumName", b64(album))
                        put("crypt", 1); put("ct", 19); put("cv", 2111)
                        put("interval", durationSec.coerceAtLeast(0))
                        put("lrc_t", 0); put("qrc", 1); put("qrc_t", 0)
                        put("roma", 1); put("roma_t", 0)
                        put("singerName", b64(artist))
                        put("songID", songId)
                        put("songName", b64(title))
                        put("trans", 1); put("trans_t", 0); put("type", 0)
                    })
                })
            }
            val req = Request.Builder()
                .url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "okhttp/3.14.9")
                .header("Cookie", "tmeLoginType=-1;")
                .build()
            val body = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val root = JSONObject(body)
            if (root.optInt("code") != 0) return@callOr null
            val data = root.optJSONObject("request")?.optJSONObject("data") ?: return@callOr null
            if (data.optLong("trans_t") == 0L) return@callOr null
            val encrypted = data.optString("trans")
            if (encrypted.isEmpty()) return@callOr null
            // 解密出来直接是标准 LRC 译文。QRC 用 "//" 表示「这句没有译文」，
            // 但以前是全文 replace("//","")，会把歌词正文里合法的 // 一起吃掉（URL、分隔符），
            // 现在只丢「正文整句都是斜杠」的行
            com.grape.player.util.QrcCodec.decryptQrc(encrypted)
                .lineSequence()
                .map { it.trim() }
                .filterNot { line ->
                    val body = line.substringAfter(']', line).trim()
                    body.isNotEmpty() && body.all { c -> c == '/' }
                }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 首播自动匹配歌词：**多候选打分**，而不是「搜到的第一条」。
     *
     * 老实现是 `fetchQQSongList(keyword, 1).firstOrNull()`，等于把结果完全交给搜索排序：
     * 扫描阶段 artist 恒为空、标题还带 `08.` 前缀，关键词经常只剩一个歌名，
     * 于是同名不同曲 / Live / 翻唱版都会被当成正确结果永久缓存。
     *
     * 现在的策略（每源仍是「1 次搜索 + 1 次取词」，不多发请求）：
     *  1. 关键词先带版本说明搜（`(Live)` 有时正好能搜到现场版），无候选再去掉括号重试一次；
     *  2. 拿回候选后按 标题相似度 62% + 艺人相似度 23% + 时长差 15% 打分；
     *  3. 过线的才去取正文，取回后还要过 [LyricQuality]（占位文案 / 无时间轴都算无效），
     *     无效继续下一个源。
     *
     * [durationMs] 传当前音轨的真实时长（拿不到给 0），用于区分录音室版与现场版。
     */
    suspend fun matchLyric(
        title: String,
        artist: String,
        providers: List<String> = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE),
        durationMs: Long = 0L,
    ): LyricFetch = withContext(Dispatchers.IO) {
        val order = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE).filter { it in providers }
        // 一个源都没开 = 这次没尝试过，不能当成「确认没有歌词」
        if (order.isEmpty()) return@withContext LyricFetch.Failed

        val keys = lyricKeywords(title, artist)
        if (keys.isEmpty()) return@withContext LyricFetch.Failed

        var answered = false       // 至少一个源正常响应过（哪怕 0 条）
        var errored = false        // 有源网络/解析失败
        var rejectedOnly = false   // 有候选，但都过不了打分线（多半是同名不同曲，不敢断言「没有歌词」）
        for (provider in order) {
            // 熔断中的源（多半是被后台补全打挂了）直接跳过并标记 errored：
            // 结果按 Failed 处理（60s 短退避、下次重试），绝不能当成「已正常问答、确认没有」
            // 写 24h 负缓存 —— 以前 QQ 被补全限流后一堆歌 24h 没词，根子就在这里
            if (SourceRateGuard.isOpen(provider)) {
                errored = true
                continue
            }
            var candidates: List<SearchResult>? = null
            for (key in keys) {
                candidates = try {
                    searchByProvider(provider, key, strict = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    errored = true
                    null
                }
                if (!candidates.isNullOrEmpty()) break
            }
            if (candidates == null) continue
            answered = true
            if (candidates.isEmpty()) continue
            val best = pickBestCandidate(candidates, title, artist, durationMs)
            if (best == null) {
                rejectedOnly = true
                continue
            }
            val body = try {
                lyricForCandidate(best)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                errored = true
                continue
            }
            if (body == null || !LyricQuality.isUsable(body.first)) continue
            return@withContext LyricFetch.Found(body.first, body.second, provider)
        }
        // 只有「三个源都正常问答且都没有候选」才算确认没有歌词；
        // 打分拒绝 / 网络失败都留着下次重试，宁可多问一次也不要把歌词藏 24 小时
        if (answered && !errored && !rejectedOnly) LyricFetch.NotFound else LyricFetch.Failed
    }

    /** 搜索关键词：先「艺人 + 带版本说明的标题」，再退化到去掉括号的标题 */
    private fun lyricKeywords(title: String, artist: String): List<String> {
        val cleanTitle = LyricMatch.titleForSearch(title)
        // 多艺人（`周杰伦/费玉清`）把分隔符换成空格：拼成「A B 歌名」比「A/B 歌名」搜得准
        val artistPart = if (LyricMatch.isPlaceholderArtist(artist)) {
            ""
        } else {
            artist.replace(Regex("[/,;、＆]"), " ").replace(Regex("\\s+"), " ").trim()
        }
        if (cleanTitle.isBlank() && artistPart.isBlank()) return emptyList()
        fun build(t: String) = listOf(artistPart, t).filter { it.isNotBlank() }.joinToString(" ")
        val first = build(cleanTitle)
        val second = build(LyricMatch.stripVersionTags(cleanTitle))
        return if (second != first && second.isNotBlank()) listOf(first, second) else listOf(first)
    }

    private suspend fun searchByProvider(provider: String, keyword: String, strict: Boolean): List<SearchResult> =
        when (provider) {
            SOURCE_QQ -> searchQQMulti(keyword, strict)
            SOURCE_KUGOU -> searchKugouMulti(keyword, strict)
            SOURCE_NETEASE -> searchNeteaseMulti(keyword, strict)
            else -> emptyList()
        }

    /**
     * 候选打分：标题为主、艺人为辅、时长用来区分版本。
     * 艺人未知（扫描后最常见）时给中性分，不让「没填艺人」把正确结果判死；
     * 但标题至少要有一点相似度 —— 只靠艺人命中一律不认，同歌手的另一首歌比同名歌常见得多。
     */
    private fun pickBestCandidate(
        candidates: List<SearchResult>,
        wantTitle: String,
        wantArtist: String,
        wantDurationMs: Long,
    ): SearchResult? {
        // 先过标题下限（只靠艺人命中一律不认），再取加权总分最高的一条
        val best = scoreCandidates(candidates, wantTitle, wantArtist, wantDurationMs)
            .firstOrNull { it.titleSim >= MIN_TITLE_SIM } ?: return null
        return if (best.score >= MIN_MATCH_SCORE) best.c else null
    }

    /** 时长相似度：任一未知时给中性分，绝不用它一票否决（扫描/首次播放常常拿不到时长） */
    private fun durationSimilarity(want: Long, cand: Long): Double {
        if (want <= 0L || cand <= 0L) return NEUTRAL_SIM
        val d = kotlin.math.abs(want - cand)
        return when {
            d <= 2_000L -> 1.0
            d <= 6_000L -> 0.85
            d <= 12_000L -> 0.6
            d <= 25_000L -> 0.3
            else -> 0.05
        }
    }

    /** 单条候选取歌词：QQ 正文 + QRC 译文 / 酷狗 m3ws（自带译文）/ 网易云 id 直取（自带译文） */
    private suspend fun lyricForCandidate(candidate: SearchResult): Pair<String, String>? = when (candidate.source) {
        LABEL_QQ -> {
            if (candidate.rawId.isEmpty()) null else {
                val (lrc, embeddedTrans) = fetchQQLyricByMid(candidate.rawId)
                if (lrc.isBlank()) null else {
                    val trans = embeddedTrans.ifBlank {
                        if (candidate.songId > 0L) {
                            fetchQrcTranslation(
                                songId = candidate.songId,
                                title = candidate.title,
                                artist = candidate.artist,
                                album = candidate.album,
                                durationSec = candidate.durationMs / 1000L,
                            ).orEmpty()
                        } else ""
                    }
                    lrc to trans
                }
            }
        }
        LABEL_KUGOU -> {
            if (candidate.rawId.isEmpty()) null else {
                val krc = fetchKugouKrcByHash(
                    candidate.rawId, candidate.title, candidate.artist, candidate.durationMs,
                )
                val lrc = krc?.first ?: fetchKugouLyricByHash(candidate.rawId)
                if (lrc.isNullOrBlank()) null else lrc to krc?.second.orEmpty()
            }
        }
        LABEL_NETEASE -> {
            val id = candidate.rawId.toLongOrNull() ?: 0L
            if (id <= 0L) null else fetchNeteaseLyric(id)?.takeIf { it.first.isNotBlank() }
        }
        else -> null
    }

    /** 酷狗搜索偶尔给命中词套 `<em>`，串进歌名/艺人后 m3ws 就匹配不到 */
    private fun stripHighlight(raw: String): String =
        raw.replace("<em>", "").replace("</em>", "").trim()

    /**
     * 「应用并修改」某条候选后精确取对应歌词（避免按歌名/艺人模糊搜索拿到别的版本）：
     * QQ：按 songmid 直接拉正文，译文缺失时用 songId 走 QRC 解密；
     * 酷狗：按 FileHash 走 m3ws 一次拿正文+译文（老的两步接口兜底，只有原文）；
     * 网易云：按歌曲 id 直取（自带译文）。
     * 都拿不到再退回打分匹配：老实现是「模糊搜索的第一条非空」，同名不同版本
     * （Live / 翻唱 / 伴奏）会被原样拿来用；[matchLyric] 复用同一套关键词归一化 +
     * 打分 + [LyricQuality] 校验，而且只在已启用的源里找。
     */
    suspend fun lyricForApplied(
        result: SearchResult,
        providers: List<String> = listOf(SOURCE_QQ, SOURCE_KUGOU, SOURCE_NETEASE),
    ): LyricResult? = withContext(Dispatchers.IO) {
        // 与首播自动匹配共用同一条「按 rawId 精确取词」的实现，避免两处逻辑各改一半
        lyricForCandidate(result)
            ?.let { (lrc, trans) -> LyricResult(result.source, result.title, result.artist, lrc, trans) }
            ?: when (val hit = matchLyric(result.title, result.artist, providers)) {
                is LyricFetch.Found ->
                    LyricResult(hit.source, result.title, result.artist, hit.lrc, hit.translation)
                else -> null
            }
    }

    companion object {
        /** 数据源标识（settings.enabledProviders 里的键；补全优先级固定 QQ → 酷狗 → 网易云） */
        const val SOURCE_QQ = "qq"
        const val SOURCE_KUGOU = "kugou"
        const val SOURCE_NETEASE = "netease"

        /** 候选里的源显示名（SearchResult.source 用它区分来源，取词时按它分发） */
        const val LABEL_QQ = "QQ音乐"
        const val LABEL_KUGOU = "酷狗音乐"
        const val LABEL_NETEASE = "网易云音乐"

        /** 标题相似度下限：低于这个值直接不认（防止只靠艺人对上就当成同一首） */
        private const val MIN_TITLE_SIM = 0.5

        /** 综合得分下限：标题 0.62 + 艺人 0.23 + 时长 0.15 的加权总分要过这条线 */
        private const val MIN_MATCH_SCORE = 0.58

        /**
         * 跨源「借字段」的标题相似度下限（只有元数据补全用）。
         * 0.7 与老实现 sameSong() 的「互含」判断强度相当 —— 归一化后已能容忍音轨前缀与标点差异，
         * 但「晴天」和「晴天 (Live)」的标题归一化后完全相同，版本差异只能靠时长分拉开，所以不能更松。
         */
        private const val MERGE_MIN_TITLE_SIM = 0.7

        /** 艺人未知 / 时长未知时的中性分：既不奖励也不惩罚 */
        private const val NEUTRAL_SIM = 0.55

        /**
         * 元数据补全的**低拒绝线**（只看标题相似度，与歌词那条 MIN_MATCH_SCORE 相互独立）。
         *
         * 为什么必须有：`fillBlankMeta` 只填空、错了不自愈。没有这条线时判据只是「标题相似度 > 0」，
         * 而 0.62×0.10 + 0.23×0.55 + 0.15×0.55 = 0.27 分这种明显不是同一首歌的候选也会被写成
         * 艺人 / 专辑 / 封面，且永久错着，只有「清空元数据」能救。歌词那条错了只是没词看，
         * 这里错了是错歌手 + 错的专辑封面 —— 宁可留空等下次，也不写错。
         *
         * 为什么不复用 0.58：元数据这条路基本拿不到可靠时长（库里的 durationMs 要首次播放才回填），
         * 那 0.15 的权重多数时候吃中性分，套歌词的线会误杀大量本来能补到封面的歌。
         * 0.40 大致对应「标题有明显重合、但不是包含关系」这一档（包含关系会给到 0.7 以上）。
         */
        private const val META_MIN_TITLE_SIM = 0.40

        // 网易云 weapi 常量（对照 netease_music.py）
        private const val NETEASE_MODULUS =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
        private const val NETEASE_PUBKEY = "010001"
        private const val NETEASE_NONCE = "0CoJUm6Qyw8W8jud"
        private const val NETEASE_IV = "0102030405060708"

        /** 酷狗译文 content 里的单行条目（形如 `[[行1],[行2],…]`），必须按括号取而不是按逗号切 */
        private val KUGOU_ITEM = Regex("\\[([^\\[\\]]*)\\]")

        /** 酷狗 LRC 的时间标签，给译文行贴时间轴用 */
        private val KUGOU_TIME_TAG = Regex("\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]")

        /** 后台批量补全用：普通超时；共享全局连接池（保留独立 Dispatcher 队列语义由 interactive() 承担） */
        private fun backgroundClient(): OkHttpClient =
            HttpClients.base.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

        /**
         * 前台交互专用（手动综合搜索 / 搜封面 / 搜歌词 / 播放时歌词匹配）。
         *
         * 关键：**全新构造**一个 OkHttpClient，而不是 newBuilder() —— newBuilder 会复用
         * 原实例的连接池和 Dispatcher（每个 Dispatcher 默认对同一 host 只放行 5 个请求）。
         * 后台补全 4 个并发在跑时，手动搜索会排在同一队列里等，表现就是「点了没反应」。
         * 独立实例 = 独立连接池 + 独立 Dispatcher + 独立线程池，互不排队。
         * 超时也调短一点，前台等不起 15 秒。
         */
        fun interactive(): MetadataScraper = MetadataScraper(
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        )
    }

    /** 只填空不覆盖：把刮削结果合并进 MediaItem */
    fun mergeBlankFields(item: MediaItem, meta: ScrapedMeta): MediaItem {
        return item.copy(
            title = item.title.ifEmpty { meta.title },
            artist = item.artist.ifEmpty { meta.artist },
            album = item.album.ifEmpty { meta.album },
            year = if (item.year == 0) meta.year else item.year,
            coverUrl = item.coverUrl.ifEmpty { meta.coverUrl },
            lyric = item.lyric.ifEmpty { meta.lyric },
            durationMs = if (item.durationMs <= 0) meta.durationMs else item.durationMs,
        )
    }
}
