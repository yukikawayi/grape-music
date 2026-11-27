package com.grape.player.util

/**
 * LRC 歌词解析器 —— 对应 Web 版 LyricViewer 的数据源。
 * 支持 [mm:ss.xx] 多时间标签、`[offset:±ms]` 全局偏移、双语（翻译行）、逐字卡拉 OK（<mm:ss.xx>）。
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String = "",
    val wordTimings: List<WordTiming> = emptyList(),
)

data class WordTiming(val timeMs: Long, val text: String)

object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val WORD_TAG = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>([^<]*)")

    /** `[offset:+500]` / `[offset:-320]` —— 全局时间轴微调标签 */
    private val OFFSET_TAG = Regex("\\[offset:\\s*([+-]?\\d{1,7})\\s*]", RegexOption.IGNORE_CASE)

    /**
     * 译文与正文「就近配对」的容差。
     * QQ 的 QRC 译文与旧接口正文的毫秒精度不同，实测同一句常差 10~200ms；
     * 以前按 timeMs 精确相等取译文，结果整篇译文贴不上（首播「有原文没译文」的主因）。
     */
    private const val TRANSLATION_TOLERANCE_MS = 400L

    /** 解析 LRC 文本。双语处理：如果同一时间点出现两行，第二行作为翻译。 */
    fun parse(lrc: String): List<LyricLine> {
        val offset = offsetOf(lrc)
        val lines = mutableListOf<LyricLine>()
        val rawByTime = HashMap<Long, MutableList<String>>()

        lrc.lineSequence().forEach { line ->
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = TIME_TAG.replace(line, "").trim()
            if (text.isEmpty()) return@forEach

            for (tag in tags) {
                val timeMs = shift(
                    parseTime(tag.groupValues[1].toLong(), tag.groupValues[2].toLong(), tag.groupValues[3]),
                    offset,
                )
                rawByTime.getOrPut(timeMs) { mutableListOf() }.add(text)
            }
        }

        val sortedEntries: List<Map.Entry<Long, MutableList<String>>> = rawByTime.entries.sortedBy { it.key }
        for (entry in sortedEntries) {
            val timeMs = entry.key
            val texts = entry.value
            val primary = texts[0]
            val rest = texts.drop(1)
            // 同一个时间点的第二行通常是译文（双语 LRC 的通用写法），但必须真的「像译文」：
            // 同语言的第二行更可能是重复行，硬当译文会把它从界面上抹掉。
            val translationIndex = rest.indexOfFirst { looksLikeTranslation(primary, it) }
            val translation = if (translationIndex >= 0) rest[translationIndex] else ""
            // 除译文外的重复行各占一行（+1ms 递增只为保持稳定顺序，肉眼无差）
            for ((i, extra) in rest.withIndex()) {
                if (i == translationIndex) continue
                lines.add(LyricLine(timeMs + i + 1, stripWordTags(extra)))
            }
            val words = parseWordTimings(primary, offset)
            lines.add(LyricLine(timeMs, stripWordTags(primary), translation, words))
        }
        return if (lines.size > 1) lines.sortedBy { it.timeMs } else lines
    }

    /**
     * 解析 LRC + 独立译文（在线搜索存进 translatedLrc 的形态）。
     * 译文按时间戳就近并入对应行（容差见 [TRANSLATION_TOLERANCE_MS]）；
     * LRC 自带的双行翻译优先级更高，不被覆盖。
     */
    fun parse(lrc: String, translation: String?): List<LyricLine> {
        val lines = parse(lrc)
        if (translation.isNullOrBlank() || lines.isEmpty()) return lines

        val offset = offsetOf(translation)
        val trans = ArrayList<Pair<Long, String>>()
        translation.lineSequence().forEach { line ->
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = TIME_TAG.replace(line, "").trim()
            if (text.isEmpty()) return@forEach
            for (tag in tags) {
                trans.add(
                    shift(
                        parseTime(tag.groupValues[1].toLong(), tag.groupValues[2].toLong(), tag.groupValues[3]),
                        offset,
                    ) to text
                )
            }
        }
        if (trans.isEmpty()) return lines
        trans.sortBy { it.first }

        // 双指针配对：两边都按时间升序，匹配成功才前进 ——
        // 同一条译文不会被相邻两行重复占用，也不会因为某行缺译文就整体错位
        var j = 0
        return lines.map { line ->
            if (line.translation.isNotBlank()) return@map line
            while (j < trans.size && trans[j].first < line.timeMs - TRANSLATION_TOLERANCE_MS) j++
            val cand = trans.getOrNull(j)
            if (cand != null && cand.first - line.timeMs <= TRANSLATION_TOLERANCE_MS) {
                j++
                line.copy(translation = cand.second)
            } else {
                line
            }
        }
    }

    /**
     * 去掉一行里的时间标签，只留正文（占位文案判定用）。
     * 与 [parse] 保持一致：多时间标签全部去掉。
     */
    fun stripTiming(line: String): String = TIME_TAG.replace(line, "").trim()

    /**
     * 「带时间标签且有正文」的行数 —— 判断一份歌词是不是真的可滚动的最低要求。
     * 无时间戳的纯文本歌词解析后是空列表（界面一片空白），必须在入库前就判出来，
     * 否则会被当成「有歌词」永久缓存，再也不会去问下一个源。
     */
    fun timedLineCount(lrc: String): Int = lrc.lineSequence().count { line ->
        TIME_TAG.find(line) != null && stripTiming(line).isNotBlank()
    }

    /** 当前时间对应的行索引（二分查找） */
    fun indexAt(lines: List<LyricLine>, positionMs: Long, offsetMs: Long = 0): Int {
        val target = positionMs - offsetMs
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeMs <= target) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun parseTime(min: Long, sec: Long, frac: String): Long {
        val base = min * 60_000 + sec * 1000
        if (frac.isEmpty()) return base
        // 小数部分右补零到 3 位：'5'→500ms，'50'→500ms，'500'→500ms
        val ms = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return base + ms
    }

    /** 全局偏移：`[offset:+500]` = 所有时间标签后移 500ms（歌词更晚出现） */
    private fun offsetOf(lrc: String): Long =
        OFFSET_TAG.find(lrc)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L

    /**
     * 同一时间点的第二行是不是「译文」。
     * 判据只看文字系统：一中一英/一中一日这类才是双语；
     * 两边都是汉字（或都是拉丁）时更可能是重复行，宁可多显示一行也不要把歌词吞掉。
     */
    private fun looksLikeTranslation(primary: String, other: String): Boolean {
        if (other.isBlank() || other == primary) return false
        return hasCjk(primary) != hasCjk(other)
    }

    private fun hasCjk(s: String): Boolean = s.any { c ->
        c.code in 0x4E00..0x9FFF || c.code in 0x3040..0x30FF || c.code in 0xAC00..0xD7AF
    }

    private fun shift(timeMs: Long, offset: Long): Long =
        if (offset == 0L) timeMs else (timeMs + offset).coerceAtLeast(0L)

    private fun parseWordTimings(text: String, offset: Long = 0L): List<WordTiming> {
        val result = mutableListOf<WordTiming>()
        for (match in WORD_TAG.findAll(text)) {
            val timeMs = shift(
                parseTime(
                    match.groupValues[1].toLong(),
                    match.groupValues[2].toLong(),
                    match.groupValues[3],
                ),
                offset,
            )
            result.add(WordTiming(timeMs, match.groupValues[4]))
        }
        return result
    }

    private fun stripWordTags(text: String): String = WORD_TAG.replace(text) { it.groupValues[4] }
}
