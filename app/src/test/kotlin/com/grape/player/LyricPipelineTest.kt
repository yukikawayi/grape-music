package com.grape.player

import com.grape.player.util.LrcParser
import com.grape.player.util.LyricMatch
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
        // 同语言的重复行不该被当成译文吞掉
        val dup = "[00:01.00]故事的小黄花\n[00:01.00]故事的小黄花\n[00:05.00]从出生那年就飘着\n[00:09.00]童年的荡秋千"
        val lines = LrcParser.parse(dup)
        assertEquals(4, lines.size)
        assertEquals("", lines[0].translation)
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
