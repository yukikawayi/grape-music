package com.grape.player.util

/**
 * 标题首字母索引 —— 从 Web 版移植。
 * 汉字按拼音首字母归组，数字归 0，其它归 #；同组内汉字在前、英文在后。
 */
object AlphaIndex {
    val INDEX_LETTERS: List<String> = listOf("0") + ('A'..'Z').map { it.toString() } + listOf("#")

    private val groupOrder = INDEX_LETTERS.withIndex().associate { it.value to it.index }

    data class AlphaKey(val letter: String, val rank: Int)

    /** 汉字 → 拼音首字母；不在表内返回 null */
    private fun hanInitial(ch: Char): Char? {
        val cp = ch.code
        if (cp < PinyinInitial.FIRST_CP || cp > PinyinInitial.LAST_CP) return null
        var lo = 0
        var hi = PinyinInitial.MISSING.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (PinyinInitial.MISSING[mid] < cp) lo = mid + 1 else hi = mid
        }
        val idx = cp - PinyinInitial.FIRST_CP - lo
        return PinyinInitial.LETTERS.getOrNull(idx)
    }

    /** 取名称的首字母分组与组内排位 */
    fun alphaKeyOf(name: String?): AlphaKey {
        val raw = name?.trim().orEmpty()
        if (raw.isEmpty()) return AlphaKey("#", 1)

        // 跳过开头标点/符号/空白
        val text = raw.dropWhile { it.isWhitespace() || (!it.isLetterOrDigit()) }
        if (text.isEmpty()) return AlphaKey("#", 1)
        val ch = text[0]

        // 全角数字/全角字母归一化
        val normalized = normalizeWidth(ch)
        if (normalized in '0'..'9' || normalized == '〇') return AlphaKey("0", 1)
        if (normalized in 'A'..'Z') return AlphaKey(normalized.toString(), 1)
        if (normalized in 'a'..'z') return AlphaKey(normalized.uppercaseChar().toString(), 1)

        val initial = hanInitial(normalized)
        return if (initial != null) AlphaKey(initial.toString(), 0) else AlphaKey("#", 1)
    }

    /** 分组序号，用于排序 */
    fun orderOf(letter: String): Int = groupOrder[letter] ?: (INDEX_LETTERS.size - 1)

    private fun normalizeWidth(c: Char): Char {
        val cp = c.code
        return when {
            cp in 0xFF10..0xFF19 -> ('0'.code + (cp - 0xFF10)).toChar() // ０-９
            cp in 0xFF21..0xFF3A -> ('A'.code + (cp - 0xFF21)).toChar() // Ａ-Ｚ
            cp in 0xFF41..0xFF5A -> ('a'.code + (cp - 0xFF41)).toChar() // ａ-ｚ
            else -> c
        }
    }
}
