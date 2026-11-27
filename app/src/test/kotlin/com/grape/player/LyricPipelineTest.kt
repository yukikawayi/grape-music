package com.grape.player

import com.grape.player.util.LrcParser
import com.grape.player.util.LyricMatch
import com.grape.player.util.LyricOrder
import com.grape.player.util.LyricQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首播歌词链路里「纯逻辑」那几层的回归测试：
 * 解析（offset / 译文容差 / 重复时间戳）、匹配归一化与打分素材、有效性判定。
 * 跑在 PC JVM 上（./gradlew :app:testDebugUnitTest），与手机端共用同一份实现。
 */
class LyricPipelineTest {

    private val lrc = """
        [ti:晴天]
        [ar:周杰伦]
        [00:01.00]故事的小黄花
        [00:05.50]从出生那年就飘着
        [00:10.00]童年的荡秋千
        [00:14.00]随记忆一直晃到现在
    """.trimIndent()

    // ===== LrcParser =====

    @Test
    fun parse_ignoresMetadataTags_andKeepsOrder() {
        val lines = LrcParser.parse(lrc)
        assertEquals(4, lines.size)
        assertEquals("故事的小黄花", lines[0].text)
        assertEquals(1000L, lines[0].timeMs)
        assertEquals(5500L, lines[1].timeMs)
    }

    @Test
    fun parse_appliesGlobalOffsetTag() {
        val shifted = "[offset:500]\n[00:01.00]第一句\n[00:05.00]第二句\n[00:09.00]第三句"
        val lines = LrcParser.parse(shifted)
        assertEquals(1500L, lines[0].timeMs)
        assertEquals(5500L, lines[1].timeMs)
    }

    @Test
    fun parse_negativeOffset_neverGoesBelowZero() {
        val shifted = "[offset:-2000]\n[00:01.00]第一句\n[00:05.00]第二句\n[00:09.00]第三句"
        val lines = LrcParser.parse(shifted)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(3000L, lines[1].timeMs)
    }

    @Test
    fun parse_translationMatchesWithinTolerance() {
        // 译文时间戳比正文晚 150ms（QRC 与 LRC 精度不同的真实情况），旧实现按精确相等会丢译文
        val trans = """
            [00:01.15]The little yellow flower
            [00:05.65]Floating since the day we were born
            [00:10.15]The swing of childhood
            [00:14.15]Swaying with memory till now
        """.trimIndent()
        val lines = LrcParser.parse(lrc, trans)
        assertEquals("The little yellow flower", lines[0].translation)
        assertEquals("The swing of childhood", lines[2].translation)
    }

    @Test
    fun parse_sameTimestamp_differentScriptBecomesTranslation() {
        val bilingual = "[00:01.00]故事的小黄花\n[00:01.00]The little yellow flower\n[00:05.00]从出生那年就飘着\n[00:09.00]童年的荡秋千"
        val lines = LrcParser.parse(bilingual)
        assertEquals(3, lines.size)
        assertEquals("The little yellow flower", lines[0].translation)
    }

    @Test
    fun parse_sameTimestamp_sameScriptKeepsBothLines() {
        // 完全相同的重复行不是译文（同一句被写了两遍），保持独立成行 —— 不吞歌词
        val dup = "[00:01.00]故事的小黄花\n[00:01.00]故事的小黄花\n[00:05.00]从出生那年就飘着\n[00:09.00]童年的荡秋千"
        val lines = LrcParser.parse(dup)
        assertEquals(4, lines.size)
        assertEquals("", lines[0].translation)
    }

    @Test
    fun parse_inlineBilingual_sameScript_pairsJapaneseWithChinese() {
        // 日文原词 + 中文译文：两边都是汉字，旧实现按「文字系统不同」判断会认不出，
        // 界面上就是原词、译文各占一行大字
        val jp = """
            [00:33.10]君の名前を呼ぶよ
            [00:33.10]呼唤你的名字
            [00:40.00]また会おう
            [00:45.00]再见
        """.trimIndent()
        val lines = LrcParser.parse(jp)
        assertEquals(3, lines.size)
        assertEquals("君の名前を呼ぶよ", lines[0].text)
        assertEquals("呼唤你的名字", lines[0].translation)
        // 相隔 5s 的两行不会被并成一对
        assertEquals("また会おう", lines[1].text)
        assertEquals("", lines[1].translation)
    }

    @Test
    fun parse_inlineBilingual_translationFirst_swapsByOrder() {
        // 英文歌的汉化词常把译文写在上方：默认（原词在前）会认反，切到「先翻译后原词」才对
        val en = """
            [00:01.00]The little yellow flower
            [00:01.00]故事的小黄花
            [00:05.00]Floating since the day we were born
            [00:09.00]童年的荡秋千
        """.trimIndent()

        val byDefault = LrcParser.parse(en)
        assertEquals("The little yellow flower", byDefault[0].text)
        assertEquals("故事的小黄花", byDefault[0].translation)

        val fixed = LrcParser.parse(en, order = LyricOrder.TRANSLATION_FIRST)
        assertEquals("故事的小黄花", fixed[0].text)
        assertEquals("The little yellow flower", fixed[0].translation)
    }

    @Test
    fun parse_inlineBilingual_wordTimingsFollowOriginal() {
        // 卡拉OK的逐字时间戳属于原词：译文在前时也必须跟着原词走，不能落到译文那一行
        val qrc = "[00:01.00]The story\n[00:01.00]<00:01.00>故<00:01.50>事"
        val lines = LrcParser.parse(qrc, order = LyricOrder.TRANSLATION_FIRST)
        assertEquals(1, lines.size)
        assertEquals("故事", lines[0].text)
        assertEquals("The story", lines[0].translation)
        assertEquals(2, lines[0].wordTimings.size)
    }

    @Test
    fun parse_inlineBilingual_crossScriptAllowsSmallGap() {
        // 一中一外不可能是同一句的重复，差几十毫秒也认作一对
        val gap = "[00:01.00]故事的小黄花\n[00:01.12]The little yellow flower"
        val lines = LrcParser.parse(gap)
        assertEquals(1, lines.size)
        assertEquals("The little yellow flower", lines[0].translation)
    }

    @Test
    fun parse_sameScriptSmallGap_keepsBothLines() {
        // 同一种文字的两行，差 120ms 是两句正常歌词，绝不能并成「原词 + 译文」
        val rap = "[00:01.00]第一句\n[00:01.12]第二句"
        val lines = LrcParser.parse(rap)
        assertEquals(2, lines.size)
        assertEquals("", lines[0].translation)
        assertEquals("第二句", lines[1].text)
    }

    @Test
    fun parse_inlineBilingual_extraTranslationJoinsSmallText() {
        // 同一时间点三行：原词 + 两份译文。多出来的跨语言行并入小字，而不是变成一行大字歌词
        val tri = "[00:01.00]原词\n[00:01.00]Translation A\n[00:01.00]Translation B"
        val lines = LrcParser.parse(tri)
        assertEquals(1, lines.size)
        assertEquals("原词", lines[0].text)
        assertEquals("Translation A\nTranslation B", lines[0].translation)
    }

    @Test
    fun parse_largeNegativeOffset_doesNotPairClampedLines() {
        // [offset:-10s] 会把开头几行全压到 0ms。夹取之后它们时间相同，
        // 但那是偏移造成的，不是「同一时间点的两行」，绝不能配成原词 + 译文
        val shifted = "[offset:-10000]\n[00:01.00]第一句\n[00:02.00]第二句\n[00:12.00]第三句"
        val lines = LrcParser.parse(shifted)
        assertEquals(3, lines.size)
        assertEquals(0L, lines[0].timeMs)
        assertEquals(0L, lines[1].timeMs)
        assertEquals("", lines[0].translation)
        assertEquals("第二句", lines[1].text)
    }

    // ===== LyricMatch =====

    @Test
    fun normTitle_stripsTrackPrefixAndVersionTags() {
        assertEquals("polaroid", LyricMatch.normTitle("08.Polaroid"))
        assertEquals("晴天", LyricMatch.normTitle("晴天 (Live)"))
        assertEquals("告白气球", LyricMatch.normTitle("告白气球【伴奏】"))
        // 「7 Rings」不能因为开头有数字就被削成「Rings」
        assertEquals("7rings", LyricMatch.normTitle("7 Rings"))
    }

    @Test
    fun titleForSearch_onlyStripsTrackPrefix() {
        assertEquals("Polaroid", LyricMatch.titleForSearch("08.Polaroid"))
        assertEquals("晴天 (Live)", LyricMatch.titleForSearch("晴天 (Live)"))
    }

    @Test
    fun similarity_prefersExactAndPenalizesUnrelated() {
        assertEquals(1.0, LyricMatch.similarity("晴天", "晴天"), 0.001)
        // 打分前两边都会先 normTitle，所以「晴天」对「晴天 (Live)」归一化后是 1.0
        assertEquals(
            1.0,
            LyricMatch.similarity(
                LyricMatch.normTitle("晴天"),
                LyricMatch.normTitle("晴天 (Live)"),
            ),
            0.001,
        )
        // 归一化前的包含关系只给「相似但不相同」的分数
        assertTrue(LyricMatch.similarity("晴天", "晴天 (Live)") in 0.7..0.99)
        assertTrue(LyricMatch.similarity("晴天", "七里香") < 0.3)
        assertEquals(1.0, LyricMatch.similarity("polaroid", "polaroid"), 0.001)
    }

    @Test
    fun normArtist_takesFirstArtistAndDropsPlaceholders() {
        assertEquals("周杰伦", LyricMatch.normArtist("周杰伦/费玉清"))
        assertEquals("", LyricMatch.normArtist("未知艺术家"))
        assertEquals("", LyricMatch.normArtist(""))
    }

    // ===== LyricQuality =====

    @Test
    fun quality_acceptsRealTimedLyric() {
        assertTrue(LyricQuality.isUsable(lrc))
    }

    @Test
    fun quality_rejectsPlaceholderText() {
        assertFalse(LyricQuality.isUsable("[00:00.00]纯音乐，请欣赏"))
        assertFalse(LyricQuality.isUsable("[00:00.00]此歌曲为没有填词的纯音乐，请您欣赏"))
    }

    @Test
    fun quality_rejectsUntimedPlainText() {
        // 无时间戳的纯文本解析后是空列表，界面一片空白，绝不能当成功结果缓存
        assertFalse(LyricQuality.isUsable("故事的小黄花\n从出生那年就飘着\n童年的荡秋千"))
    }
}
