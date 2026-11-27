package com.grape.player.util

/**
 * LRC 歌词解析器 —— 对应 Web 版 LyricViewer 的数据源。
 * 支持 [mm:ss.xx] 多时间标签、`[offset:±ms]` 全局偏移、双语（同一时间点两行）、
 * 逐字卡拉 OK（<mm:ss.xx>）。
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String = "",
    val wordTimings: List<WordTiming> = emptyList(),
)

data class WordTiming(val timeMs: Long, val text: String)

/**
 * 双语歌词的排列格式 —— 同一个时间点出现两行时，哪一行是原文。
 *
 * 这件事没有行业约定：中文歌几乎都是「原文在上、译文在下」，但英文歌、日文歌的汉化词
 * 经常反过来把译文写在上方。判断反了整页歌词就是「译文大字、原文小字」，而且原文会被
 * 当成译文，跟着「显示翻译」开关一起被关掉 —— 所以顺序必须交给用户能改，
 * 解析器只按这个顺序取正文，不靠文字系统去猜（日文对中文两边都是汉字，猜不出来）。
 */
enum class LyricOrder {
    /** 原文在前、译文在后（默认，覆盖绝大多数 LRC） */
    ORIGINAL_FIRST,

    /** 译文在前、原文在后（英文 / 日文歌的汉化词常见） */
    TRANSLATION_FIRST,
}

object LrcParser {

    private val TIME_TAG = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]")
    private val WORD_TAG = Regex("<(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?>([^<]*)")

    /** `[offset:+500]` / `[offset:-320]` —— 全局时间轴微调标签 */
    private val OFFSET_TAG = Regex("\\[offset:\\s*([+-]?\\d{1,7})\\s*]", RegexOption.IGNORE_CASE)

    /**
     * 译文与正文「就近配对」的容差（独立译文文件用）。
     * QQ 的 QRC 译文与旧接口正文的毫秒精度不同，实测同一句常差 10~200ms；
     * 以前按 timeMs 精确相等取译文，结果整篇译文贴不上（首播「有原文没译文」的主因）。
     */
    private const val TRANSLATION_TOLERANCE_MS = 400L

    /**
     * 同一份文件里「原文 + 译文」两行的时间容差。
     * 只在**跨文字系统**（一中一英 / 一中一日）时给这点余量：
     * 那种组合不可能是同一句歌词被写了两遍，差几十毫秒也能确认是一对；
     * 同一种文字（日文原词 + 中文译文这种）必须时间戳完全相同才算一对 ——
     * 放宽会把相隔几十毫秒的两句正常歌词并成一行。
     */
    private const val INLINE_PAIR_TOLERANCE_MS = 150L

    /** 摊平后的一行：时间点 + 原始文本（还带着 <mm:ss.xx> 逐字标签） */
    private data class TimedLine(val timeMs: Long, val raw: String)

    /**
     * 解析 LRC 文本。
     *
     * @param lrc 正文（可以自带同时间点的译文行，内嵌标签常见）
     * @param translation 独立的译文 LRC（在线源常见：正文一份、译文一份），按时间就近并入
     * @param order 正文里原文 / 译文谁在前 —— 决定同一时间点两行中哪一行是正文
     */
    fun parse(
        lrc: String,
        translation: String? = null,
        order: LyricOrder = LyricOrder.ORIGINAL_FIRST,
    ): List<LyricLine> {
        val lines = parseInline(lrc, order)
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
     * 解析正文，并把「同一时间点的第二行」配成译文。
     *
     * 两遍走：
     *  1. 摊平成「时间点 + 原始行」按时间稳定排序（同一时间点保留文件里的先后 ——
     *     原文/译文谁在前，是判断格式的唯一依据，不能被排序打乱）；
     *  2. 顺序扫一遍配对：紧跟在上一行之后、时间点相同（跨语言允许 [INLINE_PAIR_TOLERANCE_MS]）
     *     的那一行就是译文，按 [order] 决定谁是正文。
     */
    private fun parseInline(lrc: String, order: LyricOrder): List<LyricLine> {
        val offset = offsetOf(lrc)

        // 1) 摊平 + 稳定排序
        val raw = ArrayList<TimedLine>()
        lrc.lineSequence().forEach { line ->
            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach
            val text = TIME_TAG.replace(line, "").trim()
            if (text.isEmpty()) return@forEach
            for (tag in tags) {
                // 这里**不夹取到 0**：[offset] 给得很负时很多行会被压到同一个 0ms，
                // 夹取之后它们看起来像「同一时间点的两行」，会被误配成原词 + 译文。
                // 真正的夹取留到建 LyricLine 时做。
                raw.add(
                    TimedLine(
                        timeMs = parseTime(
                            tag.groupValues[1].toLong(),
                            tag.groupValues[2].toLong(),
                            tag.groupValues[3],
                        ) + offset,
                        raw = text,
                    )
                )
            }
        }
        raw.sortBy { it.timeMs }

        // 2) 边扫边配对
        // prevRawMs 记的是上一行的**未夹取**时间：偏移很负时多行会被夹到同一个 0ms，
        // 用夹取后的时间比较会把它们看成「同一时间点」，从而误配成原词 + 译文。
        var prevRawMs = Long.MIN_VALUE
        val lines = ArrayList<LyricLine>(raw.size)
        for (entry in raw) {
            val text = stripWordTags(entry.raw)
            val last = lines.lastOrNull()
            val gap = if (prevRawMs == Long.MIN_VALUE) Long.MAX_VALUE else entry.timeMs - prevRawMs

            if (last != null && last.translation.isEmpty() &&
                gap <= pairWindowMs(last.text, text)
            ) {
                lines[lines.lastIndex] = when (order) {
                    // 原文在前：上一行就是原文，这一行补成译文。
                    // 逐字时间戳留在原文上不动（译文行没有卡拉OK）
                    LyricOrder.ORIGINAL_FIRST -> last.copy(translation = text)
                    // 译文在前：上一行其实是译文，这一行才是原文 —— 整体换过来，
                    // 逐字时间戳跟着原文走
                    LyricOrder.TRANSLATION_FIRST -> LyricLine(
                        timeMs = last.timeMs,
                        text = text,
                        translation = last.text,
                        wordTimings = parseWordTimings(entry.raw, offset),
                    )
                }
                prevRawMs = entry.timeMs
                continue
            }

            // 同一时间点的第三行起：跨语言的并入小字译文（一份原文两份译文）；
            // 同语言的一律独立成行 —— 宁可多显示一行，也不要把歌词吞掉
            if (last != null && last.translation.isNotEmpty()) {
                val extraWindow = pairWindowMs(last.text, text)
                if (extraWindow > 0 && gap <= extraWindow && !last.translation.contains(text)) {
                    lines[lines.lastIndex] = last.copy(translation = last.translation + "\n" + text)
                    prevRawMs = entry.timeMs
                    continue
                }
            }

            lines.add(
                LyricLine(
                    timeMs = entry.timeMs.coerceAtLeast(0L),
                    text = text,
                    wordTimings = parseWordTimings(entry.raw, offset),
                )
            )
            prevRawMs = entry.timeMs
        }
        return lines
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

    /** 当前时间对应的行索引（二分查找；时间非递减即成立，同一时间点的多行取最后一行） */
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
     * 两行能算作「原文 + 译文」的时间容差；负数 = 永远不是一对。
     *
     * 判据只看文字系统，不看语义（猜不了）：
     *  - 文字完全相同：同一句被写了两遍，永远不配对；
     *  - 一中一英 / 一中一日：一定不是同一句的重复，给点毫秒余量；
     *  - 同一种文字（日文原词 + 中文译文、中文原词 + 中文译文）：只认完全相同的时刻。
     */
    private fun pairWindowMs(a: String, b: String): Long = when {
        a == b -> -1L
        hasCjk(a) != hasCjk(b) -> INLINE_PAIR_TOLERANCE_MS
        else -> 0L
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
