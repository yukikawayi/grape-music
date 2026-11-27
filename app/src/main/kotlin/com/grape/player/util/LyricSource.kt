package com.grape.player.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 歌词来源标记（对应 `lyrics.source` 列）与写入时间（`lyrics.updatedAt`）。
 *
 * 为什么要单独记：歌词正文本身看不出出处 —— QQ、酷狗、网易云对同一首会给出**不同的版本**
 * （行数、时间轴、首行「作词/作曲」写法都不一样），三家又都会在上游悄悄改词。
 * 只留正文时，用户说「这首的词怎么变了」只能靠猜：是自动匹配换了源、是上游改了词、
 * 还是自己手动套用过。记下 source + updatedAt 就能一眼分辨。
 *
 * 取值固定为下面几个 key，**不要存中文显示名**（显示名随时可以改，库里存了就洗不掉）。
 */
object LyricSource {

    /** 旧数据 / 空歌词行：没有出处可考 */
    const val UNKNOWN = ""

    /** 从音频文件内嵌标签（ID3 USLT / Vorbis LYRICS）读出来的 */
    const val EMBEDDED = "embedded"

    /** 用户在编辑弹窗里手写、粘贴或改动过的正文 */
    const val MANUAL = "manual"

    const val QQ = "qq"
    const val KUGOU = "kugou"
    const val NETEASE = "netease"

    /** 三个在线源（顺序即自动匹配优先级） */
    val ONLINE = listOf(QQ, KUGOU, NETEASE)

    /** 候选里的中文显示名 → key（[MetadataScraper.LyricResult.source] 用的是显示名） */
    private val LABEL_TO_KEY = mapOf(
        "qq音乐" to QQ,
        "酷狗音乐" to KUGOU,
        "网易云音乐" to NETEASE,
    )

    /**
     * 把各处拿到的「来源」统一成 key。
     * 传入的可能是 key 本身、中文显示名、或是老数据里的空串。
     */
    fun normalize(raw: String?): String {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return UNKNOWN
        if (s in ONLINE || s == EMBEDDED || s == MANUAL) return s
        return LABEL_TO_KEY[s.lowercase()] ?: s
    }

    /** key → 中文显示名 */
    fun label(key: String): String = when (normalize(key)) {
        EMBEDDED -> "内嵌标签"
        MANUAL -> "手动编辑"
        QQ -> "QQ音乐"
        KUGOU -> "酷狗音乐"
        NETEASE -> "网易云音乐"
        else -> "来源未记录"
    }

    /** 「网易云音乐 · 11-26 15:20」；两者都没有时说明这是升级前写的老行 */
    fun describe(key: String, updatedAt: Long): String {
        val hasKey = normalize(key).isNotEmpty()
        if (!hasKey && updatedAt <= 0L) return "来源未记录（旧数据）"
        val name = label(key)
        return if (updatedAt > 0L) "$name · ${stamp(updatedAt)}" else name
    }

    private fun stamp(ms: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
}
