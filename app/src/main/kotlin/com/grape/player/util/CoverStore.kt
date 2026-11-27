package com.grape.player.util

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 本地封面存储 —— 按来源分目录保存在应用私有数据目录下：
 * - files/EmbeddedCovers/：文件内嵌封面（Embedded_）；
 * - files/OnlineCovers/：在线刮削 / 手动编辑下载的封面（只被 Using_ 的 coverUrl 引用，无快照列）；
 * - files/cover/：旧版目录，只读保留（老数据的 coverUrl 仍指向这里），不再写入。
 *
 * 设计要点：
 * - 文件名取内容/URL 的 MD5，同一专辑多首歌只落盘一份；
 * - 成功后返回 file:// URI（Coil 与 Media3 通知栏封面都原生支持）；
 * - 下载失败返回 null，调用方退回远程 URL，界面不受影响；
 * - 已存在同名文件直接复用，不重复下载。
 */
object CoverStore {

    private const val LEGACY_DIR_NAME = "cover"
    private const val EMBEDDED_DIR_NAME = "EmbeddedCovers"
    private const val ONLINE_DIR_NAME = "OnlineCovers"
    private const val MAX_COVER_BYTES = 5L * 1024 * 1024

    /**
     * 外部数据目录（用户在设置里选的「数据存放位置」）。
     * null = 用应用私有 files/。只在 Application 启动时由 DataDirManager 设置一次
     * （迁移伴随进程重启，运行中不会变），所以各处直接读即可。
     */
    @Volatile
    var externalBase: File? = null

    /** 封面根目录：外部数据目录或应用私有 files/ */
    fun baseDir(context: Context): File = externalBase ?: context.filesDir

    // 共享连接池（补全 4 并发的封面下载与扫描复用连接）；封面是小响应，超时调短
    private val client = com.grape.player.network.HttpClients.base.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 旧版封面目录（只读兼容，不再写入） */
    fun dir(context: Context): File =
        File(baseDir(context), LEGACY_DIR_NAME).apply { mkdirs() }

    /** 内嵌封面目录：<base>/EmbeddedCovers */
    fun embeddedDir(context: Context): File =
        File(baseDir(context), EMBEDDED_DIR_NAME).apply { mkdirs() }

    /** 在线封面目录：<base>/OnlineCovers */
    fun onlineDir(context: Context): File =
        File(baseDir(context), ONLINE_DIR_NAME).apply { mkdirs() }

    /** 是否已是本地路径（file:// URI 或绝对路径） */
    fun isLocal(url: String): Boolean =
        url.startsWith("file:") || url.startsWith("/")

    /**
     * 把在线封面下载到 OnlineCovers/，返回 file:// URI 字符串。
     * 空 / 已是本地路径 → 原样返回；下载或校验失败 → null。
     */
    suspend fun downloadToLocal(context: Context, remoteUrl: String): String? =
        withContext(Dispatchers.IO) {
            val url = remoteUrl.trim()
            if (url.isEmpty() || isLocal(url)) return@withContext url
            // 临时文件清理引用：成功时已改名（delete 为 no-op），失败/异常时删掉半截文件
            var tmpCleanup: File? = null
            try {
                val file = fileFor(context, url)
                if (file.exists() && file.length() > 0) {
                    return@withContext Uri.fromFile(file).toString()
                }

                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                // 流式落盘 + 边读边计数：contentLength 不可信（-1 或不实）时，
                // 整图读进内存会被异常响应撑爆（OOM），超限立即中断
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmpCleanup = tmp
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val len = resp.body?.contentLength() ?: -1
                    if (len > MAX_COVER_BYTES) return@withContext null
                    val body = resp.body ?: return@withContext null
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        var total = 0L
                        body.byteStream().use { src ->
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_COVER_BYTES) return@withContext null
                                out.write(buf, 0, n)
                            }
                        }
                        if (total == 0L) return@withContext null
                    }
                }
                // 魔数校验（不是图片就丢弃）：从落盘文件读头部，不把整图读进内存
                val head = tmp.inputStream().use { ins ->
                    val h = ByteArray(12)
                    val n = ins.read(h)
                    if (n <= 0) ByteArray(0) else h.copyOf(n)
                }
                if (extOf(head) == null) return@withContext null

                // 原子重命名，避免半截文件被复用
                if (!tmp.renameTo(file)) {
                    return@withContext null
                }
                Uri.fromFile(file).toString()
            } catch (_: Exception) {
                null
            } finally {
                // 成功时 tmp 已改名不存在（delete 为 no-op）；失败/异常时清掉半截文件，
                // 防止残留的 .tmp 干扰后续下载与孤儿清理
                tmpCleanup?.delete()
            }
        }

    /**
     * 内嵌封面字节直接落盘到 EmbeddedCovers/（不经网络）。
     * 文件名取字节 MD5：同一专辑多首歌的内嵌图相同，只落盘一份。
     * 返回 file:// URI；字节不是图片或写失败返回 null。
     *
     * 并发安全：补全 4 个 worker 会并行处理同一张专辑的不同曲目，也就是同一份字节
     * 会被同时写多次。这里先写自己的 .tmp 再原子改名，读取方（Coil / 通知栏）
     * 只会看到「文件不存在」或「完整文件」，不会读到别人写到一半的截断图。
     */
    suspend fun saveBytes(context: Context, bytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            try {
                if (bytes.isEmpty()) return@withContext null
                val ext = extOf(bytes) ?: return@withContext null
                val digest = MessageDigest.getInstance("MD5").digest(bytes)
                val name = digest.toHex()
                val file = File(embeddedDir(context), "$name.$ext")
                // 已存在且大小对得上才复用：同名但大小不符说明上次写到一半被打断，重写一次
                if (file.length() != bytes.size.toLong()) {
                    val tmp = File(
                        file.parentFile,
                        "$name.${Thread.currentThread().id}.${System.nanoTime()}.tmp",
                    )
                    try {
                        tmp.writeBytes(bytes)
                        // 改名失败（极少）但目标此时已完整存在就复用它，不必报错
                        if (!tmp.renameTo(file) && file.length() != bytes.size.toLong()) {
                            return@withContext null
                        }
                    } finally {
                        tmp.delete()
                    }
                }
                Uri.fromFile(file).toString()
            } catch (_: Exception) {
                null
            }
        }

    /** 在线封面 URL → OnlineCovers/ 下的固定文件名，同一 URL 天然去重 */
    private fun fileFor(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("MD5").digest(url.toByteArray())
        val name = digest.toHex()
        return File(onlineDir(context), "$name.jpg")
    }

    /** 清理结果：删掉的文件数与释放的字节数 */
    data class CleanResult(val deleted: Int, val freedBytes: Long)

    /**
     * 清理无效封面 —— 只清 OnlineCovers/ 里**当前不再被使用**的封面。
     * 「在用」= 被任何曲目的 Using 封面（coverUrl）或 Embedded_ 快照封面（embCoverUrl）引用；
     * 引用集之外的文件即为无效（未使用且不属于内嵌数据）。
     *
     * EmbeddedCovers/（内嵌数据）**永不在此清理**——内嵌数据只在「清除数据库」时整体删除；
     * 旧版 cover/ 目录也一并跳过（里面混有历史内嵌封面，无法区分）。
     */
    suspend fun cleanOrphans(context: Context, referencedUrls: Collection<String>): CleanResult =
        withContext(Dispatchers.IO) {
            try {
                val referenced = referencedUrls.mapNotNull { fileNameOf(it) }.toHashSet()
                var deleted = 0
                var bytes = 0L
                onlineDir(context).listFiles()?.forEach { file ->
                    if (!file.isFile) return@forEach
                    if (file.name in referenced && !file.name.endsWith(".tmp")) return@forEach
                    val len = file.length()
                    if (file.delete()) {
                        deleted++
                        bytes += len
                    }
                }
                CleanResult(deleted, bytes)
            } catch (_: Exception) {
                CleanResult(0, 0)
            }
        }

    /**
     * 「删除所有在线数据」专用：删除所有**非内嵌来源**的封面图片。
     * OnlineCovers/（在线/手动搜索下载的封面）与旧版 cover/（历史在线封面）整体清空；
     * EmbeddedCovers/ 内嵌封面不动。调用前应先把 Using_ 重置为内嵌值（coverUrl=embCoverUrl），
     * 这样清完之后没有任何引用指向这两个目录。
     */
    suspend fun deleteAllNonEmbedded(context: Context): CleanResult =
        withContext(Dispatchers.IO) {
            var deleted = 0
            var bytes = 0L
            try {
                listOf(onlineDir(context), dir(context)).forEach { d ->
                    d.listFiles()?.forEach { file ->
                        if (!file.isFile) return@forEach
                        val len = file.length()
                        if (file.delete()) {
                            deleted++
                            bytes += len
                        }
                    }
                }
            } catch (_: Exception) {
            }
            CleanResult(deleted, bytes)
        }

    /**
     * 清除数据库专用：三个封面目录（旧 cover/ + EmbeddedCovers/ + OnlineCovers/）整体清空。
     * 内嵌封面也在这里删除 —— 「内嵌数据的清除仅在清除数据库时触发」。
     */
    suspend fun wipeAll(context: Context): CleanResult =
        withContext(Dispatchers.IO) {
            var deleted = 0
            var bytes = 0L
            try {
                listOf(dir(context), embeddedDir(context), onlineDir(context)).forEach { d ->
                    d.listFiles()?.forEach { file ->
                        if (!file.isFile) return@forEach
                        val len = file.length()
                        if (file.delete()) {
                            deleted++
                            bytes += len
                        }
                    }
                }
            } catch (_: Exception) {
            }
            CleanResult(deleted, bytes)
        }

    /** 从 file:// 或绝对路径里取文件名（URL 编码的中文/空格要解码后再比） */
    private fun fileNameOf(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        val name = trimmed.substringAfterLast('/')
        if (name.isEmpty()) return null
        return runCatching { java.net.URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
    }

    /** 按魔数判断图片类型；不认识的格式返回 null */
    private fun extOf(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() -> "png"
        bytes.size >= 12 && bytes[0] == 'R'.code.toByte() && bytes[8] == 'W'.code.toByte() -> "webp"
        bytes.size >= 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
        else -> null
    }
}
