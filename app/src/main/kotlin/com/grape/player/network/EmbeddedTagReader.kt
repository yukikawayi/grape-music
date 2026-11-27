package com.grape.player.network

import android.content.Context
import com.grape.player.data.WebDavCredentials
import com.grape.player.util.TagReader
import com.grape.player.util.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 内嵌标签加载器 —— 按需拉取「元数据区」，而不是每首固定拉 8MB。
 *
 * 为什么可以很小：每个容器头部都自带「这段有多长」的声明，
 * 而元数据区大小完全由内嵌封面决定，与音频时长无关。
 *
 * 分级策略：
 *  1. 第一刀只拉 128KB（覆盖绝大多数 MP3 的 ID3v2、无图 FLAC）；
 *  2. 按魔数解析容器，算出元数据区真实结束偏移，只补拉缺的那一段：
 *     - MP3：ID3v2 头声明长度（10 字节 syncsafe）+ 256KB 音频帧余量
 *     - FLAC：顺着 block 头累加（每块 4 字节头里带 24 位长度），直到 last-block
 *     - MP4/M4A：走顶层 box 找 `moov`；moov 在尾部时用「稀疏写入」把尾部落到正确偏移
 *     - OGG / 未知容器：无长度声明，退回 1MB 头部
 *  3. 全程有上限（[MAX_META_BYTES]），超限就放弃内嵌、交给在线刮削，不影响功能。
 *
 * 典型场景从「每首 8MB」降到 32~256KB。
 *
 * 时长可靠性按格式区分（截断文件的估算时长不能写库，否则错值会被永久缓存）：
 *  - FLAC：STREAMINFO 里有总采样数与采样率，精确，可信；
 *  - MP4/M4A：能解析到 moov 就说明 mvhd 完整，可信；
 *  - MP3 / OGG / 其他：截断后只能按比特率估算，置 0 交给播放回填。
 */
object EmbeddedTagReader {

    /** 第一刀：绝大多数文件到这里就够了 */
    private const val HEAD_BYTES = 128L * 1024

    /** MP3 拉完 ID3v2 后再多带的音频帧长度（帧头解析需要一点数据） */
    private const val MP3_PAD_BYTES = 256L * 1024

    /** FLAC 拉完元数据块后再带一点音频帧 */
    private const val FLAC_PAD_BYTES = 64L * 1024

    /** 元数据区（含封面）允许的最大补拉范围，超了就放弃内嵌 */
    private const val MAX_META_BYTES = 12L * 1024 * 1024

    /** 单次请求的防御上限 */
    private const val MAX_BYTES = 100L * 1024 * 1024

    /** MP4 moov 在尾部时一次性拉尾部多大 */
    private const val MP4_TAIL_BYTES = 4L * 1024 * 1024

    /** OGG / 未知容器：没有长度声明，给个大一点的头部 */
    private const val FALLBACK_HEAD_BYTES = 1L * 1024 * 1024

    // 共享连接池（补全 4 并发的 Range 拉取与扫描/封面下载复用 keep-alive）
    private val client = HttpClients.base.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun read(context: Context, url: String): TagReader.Tags? =
        withContext(Dispatchers.IO) {
            if (url.isBlank() || !url.startsWith("http", true)) return@withContext null
            // 缓存文件必须带音频扩展名：jaudiotagger 按扩展名分发读取器，
            // 无扩展名的文件会抛 CannotReadException（曾被静默吞掉 → 内嵌读取 100% 失败）
            val ext = extFromUrl(url)
            val file = File(File(context.cacheDir, "embedded_tags"), md5(url) + (ext ?: ""))
            file.parentFile?.mkdirs()
            var parseFile = file
            try {
                val collected = collect(context, url, file) ?: return@withContext null
                if (ext == null) {
                    // URL 里推不出扩展名（如直链无后缀）：按魔数补一个再交给 TagReader
                    val magicExt = extFromMagic(collected.magic) ?: return@withContext null
                    parseFile = File(file.parentFile, file.name + magicExt)
                    if (!file.renameTo(parseFile)) parseFile = file
                }
                val tags = TagReader.read(parseFile) ?: return@withContext null
                if (collected.complete || durationReliable(collected.magic)) tags
                else tags.copy(durationMs = 0)   // 截断文件的估算时长不可信
            } catch (e: CancellationException) {
                // 应用退出/停止时立刻向上传播，不把取消当成「读标签失败」
                throw e
            } catch (_: Exception) {
                null
            } finally {
                // 读完即删：cacheDir 不长期占用。
                // 注意 renameTo 成功后原路径已不存在（file.delete 是 no-op），必须把改名后的
                // parseFile 也删掉，否则无扩展名直链每次读都泄漏一个最大 12MB 的缓存文件
                file.delete()
                if (parseFile != file) parseFile.delete()
            }
        }

    private class Collected(val magic: String, val complete: Boolean)

    /**
     * 按容器把「元数据区」补齐到本地缓存文件。
     * 本地文件里的字节偏移与远端保持一一对应（尾部区间也写在自己的偏移上），
     * 这样 jaudiotagger 解析 box / block 链时算出来的绝对偏移仍然是对的。
     */
    private suspend fun collect(context: Context, url: String, file: File): Collected? {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)

            var avail = 0L      // 已可靠持有的连续前缀长度
            var eof = false
            var total = -1L

            suspend fun pull(from: Long, to: Long): Long {
                currentCoroutineContext().ensureActive()
                val f = fetch(context, url, raf, from, to)
                if (f.bytes > 0) avail = maxOf(avail, f.start + f.bytes)
                if (f.eof) eof = true
                if (f.total > 0) {
                    total = f.total
                    if (avail >= f.total) eof = true
                }
                return avail
            }

            pull(0, HEAD_BYTES - 1)
            if (avail <= 0) return null

            // 文件本身很小（短音频、片头曲、试听）：直接拉完。
            // 代价最多 2MB，换来「完整文件 → 时长可信」，跟老实现在这类文件上的行为一致。
            if (!eof && total in 1..(2L * 1024 * 1024)) {
                pull(avail, total - 1)
            }

            val head = readAt(raf, 0, 12)
            val magic = String(head, Charsets.ISO_8859_1)

            when {
                // ---------- MP3：ID3v2 头里直接声明了标签长度 ----------
                magic.startsWith("ID3") -> {
                    val need = id3v2Size(head) + 10 + MP3_PAD_BYTES
                    if (!eof && need > avail && need <= MAX_META_BYTES) {
                        pull(avail, need - 1)
                    }
                }

                // ---------- FLAC：沿 block 链走到 last-block ----------
                magic.startsWith("fLaC") -> {
                    var offset = 4L
                    while (!eof) {
                        if (offset + 4 > avail) {
                            val need = minOf(offset + FLAC_PAD_BYTES + 4, MAX_META_BYTES)
                            if (need <= avail) break
                            pull(avail, need - 1)
                            if (offset + 4 > avail) break
                        }
                        val hdr = readAt(raf, offset, 4)
                        if (hdr.size < 4) break
                        val last = (hdr[0].toInt() and 0x80) != 0
                        val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                            ((hdr[2].toInt() and 0xFF) shl 8) or
                            (hdr[3].toInt() and 0xFF)
                        offset += 4 + len
                        if (last) break
                        if (offset > MAX_META_BYTES) break
                    }
                    val need = offset + FLAC_PAD_BYTES
                    if (!eof && need > avail && need <= MAX_META_BYTES) {
                        pull(avail, need - 1)
                    }
                }

                // ---------- MP4 / M4A：走顶层 box 找 moov ----------
                isMp4(magic) -> {
                    var offset = 0L
                    var moovEnd = -1L
                    var needTail = false
                    while (offset + 8 <= avail) {
                        val hdr = readAt(raf, offset, 16)
                        if (hdr.size < 8) break
                        var size = readU32(hdr, 0)
                        val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
                        if (size == 1L) {
                            if (hdr.size < 16) break
                            size = readU64(hdr, 8)
                        }
                        if (size <= 0L) break                 // size=0：延伸到文件末尾
                        if (type == "moov") {
                            moovEnd = offset + size
                            break
                        }
                        if (offset + size > avail) {          // 中间那个大 box（通常是 mdat）没下完
                            needTail = true
                            break
                        }
                        offset += size
                    }

                    if (moovEnd > 0 && !eof && moovEnd > avail && moovEnd <= MAX_META_BYTES) {
                        pull(avail, moovEnd - 1)
                    } else if (moovEnd <= 0 && needTail && total > 0) {
                        // moov 在尾部：直接拉尾部，并把文件长度补到 total，
                        // 中间空洞不下载（稀疏文件），box 的绝对偏移因此依然正确
                        val tailStart = maxOf(0L, total - MP4_TAIL_BYTES)
                        pull(tailStart, total - 1)
                        if (avail >= total) {
                            if (raf.length() < total) raf.setLength(total)
                            eof = true
                        }
                    }
                }

                // ---------- OGG / 未知容器：没有长度声明，退回 1MB ----------
                else -> {
                    if (!eof && avail < FALLBACK_HEAD_BYTES) {
                        pull(avail, FALLBACK_HEAD_BYTES - 1)
                    }
                }
            }

            return Collected(magic, eof)
        }
    }

    private class Fetch(
        /** 这批字节在文件里的起始偏移（服务器忽略 Range 时是 0） */
        val start: Long,
        val bytes: Long,
        val eof: Boolean,
        /** 远端文件总大小，未知为 -1 */
        val total: Long,
    )

    /**
     * Range 下载 [from]..[to]（含），按远端偏移写入本地文件。
     * 服务器不支持 Range 会返回 200 全量 —— 这时从 0 覆盖写，并标记 eof。
     */
    private suspend fun fetch(
        context: Context,
        url: String,
        raf: RandomAccessFile,
        from: Long,
        to: Long,
    ): Fetch {
        if (to < from) return Fetch(from, 0, false, -1)
        val builder = Request.Builder().url(url).header("Range", "bytes=$from-$to")
        WebDavCredentials.authHeader()?.let { builder.header("Authorization", it) }

        return try {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return Fetch(from, 0, false, -1)
                val ranged = resp.code == 206
                val total = if (ranged) parseTotal(resp.header("Content-Range")) else -1L
                val body = resp.body ?: return Fetch(from, 0, false, total)
                val writeAt = if (ranged) from else 0L

                raf.seek(writeAt)
                val buf = ByteArray(64 * 1024)
                var written = 0L
                body.byteStream().use { src ->
                    while (true) {
                        // 边下边检查取消：退出应用后不会把这一首剩余的几十 MB 下完
                        currentCoroutineContext().ensureActive()
                        val n = src.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        written += n
                        if (written > MAX_BYTES) break
                    }
                }
                // 请求区间没拉满 = 已到文件末尾（或服务器提前收尾）
                val eof = !ranged || written < (to - from + 1)
                Fetch(writeAt, written, eof, if (ranged) total else written)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Fetch(from, 0, false, -1)
        }
    }

    /** 从 "bytes 0-131071/1234567" 里取总大小 */
    private fun parseTotal(contentRange: String?): Long {
        val raw = contentRange?.substringAfterLast('/', "")?.trim().orEmpty()
        return raw.toLongOrNull() ?: -1L
    }

    /** 读取文件 [offset] 起的最多 [len] 字节（不足则返回实际读到的） */
    private fun readAt(raf: RandomAccessFile, offset: Long, len: Int): ByteArray {
        val fileLen = raf.length()
        if (offset >= fileLen) return ByteArray(0)
        val want = minOf(len.toLong(), fileLen - offset).toInt()
        val buf = ByteArray(want)
        raf.seek(offset)
        var off = 0
        while (off < want) {
            val n = raf.read(buf, off, want - off)
            if (n < 0) break
            off += n
        }
        return if (off == want) buf else buf.copyOf(off)
    }

    private fun isMp4(magic: String): Boolean =
        magic.length >= 8 && magic.regionMatches(4, "ftyp", 0, 4)

    /** FLAC 元数据全在头部，STREAMINFO 时长精确；MP4 解析成功即 moov 完整，mvhd 时长精确 */
    private fun durationReliable(magic: String): Boolean =
        magic.startsWith("fLaC") || isMp4(magic)

    /** ID3v2 标签区大小（bytes 6..9 是 syncsafe int），不含 10 字节头；head 不足 10 字节返回 0 */
    private fun id3v2Size(head: ByteArray): Long {
        if (head.size < 10 || !String(head, 0, 3, Charsets.ISO_8859_1).startsWith("ID3")) return 0
        return ((head[6].toInt() and 0x7F).toLong() shl 21) or
            ((head[7].toInt() and 0x7F).toLong() shl 14) or
            ((head[8].toInt() and 0x7F).toLong() shl 7) or
            (head[9].toInt() and 0x7F).toLong()
    }

    private fun readU32(b: ByteArray, at: Int): Long {
        if (b.size < at + 4) return -1
        return ((b[at].toLong() and 0xFF) shl 24) or
            ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or
            (b[at + 3].toLong() and 0xFF)
    }

    private fun readU64(b: ByteArray, at: Int): Long {
        if (b.size < at + 8) return -1
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray()).toHex()

    /** jaudiotagger 2.0.1 支持的音频扩展名集合 */
    private val SUPPORTED_EXTS = setOf(
        "mp3", "flac", "m4a", "m4b", "m4p", "mp4", "ogg", "oga", "wav", "aif", "aiff",
    )

    /**
     * 从 URL 取音频扩展名（带点），如 ".flac"。
     * URL 是百分号编码的，先解码再取最后一段的后缀；不在支持列表里返回 null。
     */
    private fun extFromUrl(url: String): String? {
        val decoded = try {
            java.net.URLDecoder.decode(url.substringBefore('?'), "UTF-8")
        } catch (_: Exception) {
            url
        }
        val ext = decoded.substringAfterLast('.', "").lowercase()
        if (ext.length !in 3..4 || ext !in SUPPORTED_EXTS) return null
        return ".$ext"
    }

    /** 按容器魔数推断扩展名（URL 无后缀时的兜底） */
    private fun extFromMagic(magic: String): String? = when {
        magic.startsWith("fLaC") -> ".flac"
        magic.startsWith("ID3") -> ".mp3"
        isMp4(magic) -> ".m4a"
        magic.startsWith("OggS") -> ".ogg"
        magic.startsWith("RIFF") -> ".wav"
        else -> null
    }
}
