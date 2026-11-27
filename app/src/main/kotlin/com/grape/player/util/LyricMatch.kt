package com.grape.player.util

/**
 * 歌词/元数据自动匹配用的字符串归一化与相似度。
 *
 * 为什么单独抽出来：首播时的输入经常是「文件名推导值」而不是干净的标签 ——
 * 扫描阶段 artist 恒为空、标题保留音轨前缀（`08.Polaroid`）、版本说明堆在括号里
 * （`晴天 (Live)`）。直接拿这种字符串去搜，命中「另一首歌」的概率远高于命中正确的歌。
 * 这里只做纯字符串处理，不触碰网络，便于单测。
 */
object LyricMatch {

    /** 括号里的版本说明：`(Live)` `【伴奏】` `[Remastered]` —— 比对时统一丢掉 */
    private val BRACKETS = Regex("[（(\\[【].*?[）)\\]】]")

    /** 音轨前缀：`08.` `01 - ` `03_` `12、`（不含纯空格，避免把 "7 Rings" 削成 "Rings"） */
    private val TRACK_PREFIX = Regex("^\\d{1,3}\\s*[.\\-_、]\\s*")

    /** `feat. / ft. / featuring` 之后的内容（"with" 不算：会误伤 "Dance with Me"） */
    private val FEAT = Regex("(?i)\\b(feat|ft|featuring)\\b\\.?.*$")

    /** 归一化时要抹掉的标点与空白（含中日韩标点） */
    private val NOISE = Regex("[\\s\\p{Punct}·・，。！？、…—－―“”‘’《》「」]")

    /** 常见「其实等于没填」的艺人值 */
    private val PLACEHOLDER_ARTISTS = setOf(
        "unknown", "unknownartist", "未知", "未知艺术家", "未知歌手", "群星",
        "variousartists", "va", "artist", "歌手", "n/a", "na",
    )

    /** 标题归一化：小写、丢版本说明与音轨前缀、丢 feat、丢标点空白 */
    fun normTitle(raw: String): String {
        var s = raw.lowercase()
        s = BRACKETS.replace(s, " ")
        s = TRACK_PREFIX.replace(s, "")
        s = FEAT.replace(s, " ")
        return NOISE.replace(s, "").trim()
    }

    /** 只去掉音轨前缀，保留版本说明 —— 给搜索关键词用（`(Live)` 有时正好能搜到现场版） */
    fun titleForSearch(raw: String): String {
        val t = raw.trim()
        return TRACK_PREFIX.replace(t, "").ifBlank { t }
    }

    /** 丢掉括号里的版本说明，给「搜不到时降级重试」用 */
    fun stripVersionTags(raw: String): String =
        BRACKETS.replace(raw, " ").replace(Regex("\\s+"), " ").trim().ifBlank { raw.trim() }

    /** 艺人归一化：只取第一个（`周杰伦/费玉清` → `周杰伦`），去空白标点 */
    fun normArtist(raw: String): String {
        val first = raw.split('/', ',', ';', '&', '＆', '、', '·')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        val s = first.lowercase()
        val norm = NOISE.replace(s, "").trim()
        return if (isPlaceholderArtist(norm)) "" else norm
    }

    fun isPlaceholderArtist(raw: String): Boolean {
        val s = NOISE.replace(raw.lowercase(), "").trim()
        return s.isEmpty() || s in PLACEHOLDER_ARTISTS
    }

    /**
     * 标题相似度 0..1：
     * 完全相同 1.0；包含关系按长度比例取 0.7~1.0；否则字符二元组 Dice 系数。
     * 中文没有词边界，二元组比按空格切词更稳。
     */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) {
            val min = minOf(a.length, b.length).toDouble()
            val max = maxOf(a.length, b.length).toDouble()
            return 0.7 + 0.3 * (min / max)
        }
        val x = bigrams(a)
        val y = bigrams(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        var inter = 0
        for (g in x) if (g in y) inter++
        return 2.0 * inter / (x.size + y.size)
    }

    /** 艺人相似度 0..1：相等 1.0，包含 0.75，否则看二元组但不给高分（不同歌手不应被当成同一人） */
    fun artistSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.75
        return 0.0
    }

    private fun bigrams(s: String): Set<String> {
        if (s.length < 2) return if (s.isEmpty()) emptySet() else setOf(s)
        val out = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }
}
