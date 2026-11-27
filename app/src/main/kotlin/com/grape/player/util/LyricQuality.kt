package com.grape.player.util

/**
 * 歌词「有效性」判定 —— 决定一份在线结果能不能入库。
 *
 * 背景：原来只判 `lrc.isNotBlank()`，于是三类垃圾都会被当成成功结果永久缓存：
 *  1. 纯音乐占位文案（QQ「此歌曲为没有填词的纯音乐，请您欣赏」、网易云「纯音乐，请欣赏」）；
 *  2. 无时间戳的纯文本（[LrcParser.parse] 返回空列表，界面空白，但库里认为「有词」）；
 *  3. 源返回的降级页 / 错误页。
 * 一旦入库，`dbLrc.isNotBlank()` 就会让后续每次播放直接早退，其他源再也没机会补上。
 */
object LyricQuality {

    /** 占位文案特征（比较前会抹掉空白与标点，所以这里也只写「连续正文」形态） */
    private val PLACEHOLDER_MARKERS = listOf(
        "此歌曲为没有填词的纯音乐",
        "没有填词的纯音乐",
        "纯音乐请欣赏",
        "纯音乐",
        "暂无歌词",
        "该歌曲暂无歌词",
        "抱歉暂无歌词",
        "歌词加载中",
        "instrumental",
        "nolyrics",
        "lyricsnotavailable",
        "没有歌词",
    )

    private val NOISE = Regex("[\\s\\p{Punct}，。！？、．·（）【】]")

    /** 「有效」= 有时间轴（≥3 行带标签且有正文）且不是占位文案 */
    fun isUsable(lrc: String): Boolean {
        if (lrc.isBlank()) return false
        if (isPlaceholder(lrc)) return false
        return LrcParser.timedLineCount(lrc) >= 3
    }

    /**
     * 整份歌词只是一句占位文案（「纯音乐，请欣赏」之类）。
     * 单独暴露出来是为了「自愈」：老版本把这类内容当成功结果存进库里，
     * 读取时认出它就能允许被新结果覆盖；而用户手写的无时间轴纯文本不算占位，不会被误覆盖。
     */
    fun isPlaceholder(lrc: String): Boolean {
        val text = lrc.lineSequence()
            .map { LrcParser.stripTiming(it) }
            .filter { it.isNotBlank() }
            .joinToString("")
        if (text.isEmpty()) return false
        val compact = NOISE.replace(text.lowercase(), "")
        if (compact.isEmpty()) return false
        // 真歌词不可能短到几十个字；限长既能把「整首歌都叫纯音乐」的极端情况放过，
        // 也避免标题里带「纯音乐」的正经歌曲被误判
        if (compact.length > 60) return false
        return PLACEHOLDER_MARKERS.any { compact.contains(NOISE.replace(it, "")) }
    }
}
