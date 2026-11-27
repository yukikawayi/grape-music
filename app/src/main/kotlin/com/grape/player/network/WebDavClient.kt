package com.grape.player.network

import android.util.Xml
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** WebDAV 目录项。[path] 是 OpenList 侧路径（如 /quark/音乐/xx.flac），不带 /dav 前缀 */
data class DavEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: String = "",
)

/**
 * 纯 WebDAV 客户端 —— 直接对手机 OpenList 的 /dav 端点做 PROPFIND / GET。
 *
 * 两个关键点（旧实现踩过坑）：
 * 1. PROPFIND 必须带 XML 请求体，否则部分版本的 OpenList 返回 500；
 * 2. 响应里的 href 已经包含 /dav 前缀，不能再拼一次 baseUrl（旧代码拼成 /dav/dav/... 导致 404）。
 *
 * 播放直链 = [fileUrl]，形如 http://127.0.0.1:5244/dav/quark/a%20b/01.flac，
 * 不带签名、不会过期，认证由播放层统一注入。
 */
class WebDavClient(
    baseUrl: String,
    private val username: String = "",
    private val password: String = "",
) {
    /** 归一化后的 WebDAV 根，必定以 /dav 结尾 */
    val davBase: String = normalizeDavBase(baseUrl)

    // 共享连接池：扫描 / 补全 / 封面下载对同一台 OpenList 复用 keep-alive 连接
    private val client = HttpClients.base.newBuilder()
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val xmlMedia = "application/xml; charset=utf-8".toMediaType()

    private fun authHeader(): String? =
        if (username.isNotEmpty()) Credentials.basic(username, password) else null

    /** OpenList 路径 → 可播放直链 */
    fun fileUrl(path: String): String = davBase + encodePath(normalizePath(path))

    /**
     * 列一层目录（Depth: 1）。[retries] 为额外重试次数，扫描时用 1 次快速跳过坏目录。
     * 全程可取消：协程被取消时 OkHttp 的请求会被 call.cancel() 立刻中断。
     */
    suspend fun listDir(path: String, retries: Int = 2): List<DavEntry> {
        val dir = normalizePath(path)
        var lastError: Exception? = null
        repeat(retries.coerceAtLeast(1)) {
            try {
                return requestMultiStatus(dir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w(TAG, "listDir 失败 @ $dir: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        throw lastError as? WebDavException ?: WebDavException(lastError?.message ?: "列目录失败")
    }

    /**
     * 连通性自检：能列目录且不是认证错误。
     * OpenList 在刚跑完一次大批量枚举后会短暂变慢（云盘接口限速），所以这里退避重试 3 次，
     * 不要因为一次抖动就把整次扫描判死。
     */
    suspend fun verify(root: String) {
        if (davBase.isEmpty()) throw WebDavException("WebDAV 地址无效，需以 http:// 或 https:// 开头")
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                listDir(root)
                return
            } catch (e: Exception) {
                last = e
                if (attempt < 2) delay(1500L * (attempt + 1))
            }
        }
        val e = last
        throw if (e is WebDavException) e else WebDavException(
            "连接 WebDAV 超时（${e?.message ?: "无响应"}）：OpenList 可能正忙，稍等几秒再点扫描"
        )
    }

    /**
     * 递归枚举媒体文件（BFS，避免深目录递归爆栈）。
     * [onProgress] 回调 (已遍历目录数, 已发现文件数)。
     *
     * 每个目录前都检查一次协程是否已取消 —— 应用被划掉/退出时能立刻停下，
     * 不会在后台继续把几千个目录跑完。
     *
     * [shouldStop] 是「用户点了取消扫描」的软停止信号：命中后跳出枚举并**返回已找到的条目**，
     * 而不是抛异常丢掉结果 —— 调用方要靠这份半成品把已经扫到的歌存进库（详见 LibraryScanner.scan）。
     */
    suspend fun scanMedia(
        root: String,
        maxDepth: Int = 20,
        maxDirs: Int = 8000,
        onProgress: (dirs: Int, files: Int) -> Unit = { _, _ -> },
        shouldStop: () -> Boolean = { false },
    ): List<DavEntry> {
        val found = mutableListOf<DavEntry>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(normalizePath(root) to 0)
        var visited = 0
        var consecutiveFailures = 0

        while (queue.isNotEmpty() && visited < maxDirs) {
            // 取消点①：退出应用后这里立刻抛 CancellationException，后续目录不再请求
            currentCoroutineContext().ensureActive()
            // 取消点②：用户点「取消扫描」→ 停止枚举但保留已找到的结果
            if (shouldStop()) break
            val (dir, depth) = queue.removeFirst()
            visited++
            val entries = try {
                // 扫描阶段只给 1 次机会：手机版 OpenList 遇到个别异常目录会整进程挂住，
                // 快速跳过比反复重试更划算（重试也唤不醒它）
                listDir(dir, retries = 1)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                consecutiveFailures++
                // 连续失败就当作服务不可用，直接报错而不是让用户干等几千个目录超时
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    throw WebDavException(
                        "OpenList 无响应（连续 $consecutiveFailures 个目录请求失败）：${e.message ?: "timeout"}。" +
                            "请到后台完全关闭 OpenList 再重新打开，然后重试。"
                    )
                }
                onProgress(visited, found.size)
                continue
            }
            consecutiveFailures = 0
            for (e in entries) {
                if (e.isDirectory) {
                    if (depth < maxDepth) queue.add(e.path to depth + 1)
                } else if (isMedia(e.name)) {
                    found.add(e)
                }
            }
            onProgress(visited, found.size)
        }
        return found
    }

    /**
     * PROPFIND 用异步 enqueue 实现：协程取消时 invokeOnCancellation 里 call.cancel()，
     * 正在飞的网络请求会立即断开，而不是等 20 秒读超时。
     */
    private suspend fun requestMultiStatus(dir: String): List<DavEntry> = suspendCancellableCoroutine { cont ->
        val url = davBase + encodePath(dir)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(xmlMedia))
            .header("Depth", "1")
            .apply { authHeader()?.let { header("Authorization", it) } }
            .build()

        val startedAt = System.currentTimeMillis()
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                if (!cont.isActive) return
                android.util.Log.w(TAG, "PROPFIND 失败 @ $dir: ${e.javaClass.simpleName} ${e.message}")
                cont.resumeWithException(e)
            }

            override fun onResponse(c: Call, response: Response) {
                response.use { resp ->
                    if (!cont.isActive) return
                    try {
                        val cost = System.currentTimeMillis() - startedAt
                        when {
                            resp.code == 401 || resp.code == 403 -> {
                                android.util.Log.w(TAG, "认证失败 ${resp.code} @ $dir")
                                throw WebDavException("WebDAV 认证失败（HTTP ${resp.code}），请检查用户名/密码")
                            }

                            resp.code == 404 -> throw WebDavException("目录不存在：$dir")

                            !resp.isSuccessful -> {
                                android.util.Log.w(TAG, "PROPFIND ${resp.code} @ $dir (${cost}ms)")
                                throw WebDavException("PROPFIND 失败：HTTP ${resp.code} @ $dir")
                            }
                        }
                        val body = resp.body?.string().orEmpty()
                        if (cost > 3000) android.util.Log.w(TAG, "PROPFIND 慢响应 ${cost}ms @ $dir (${body.length}B)")
                        val list = parseMultiStatus(body).filter { normalizePath(it.path) != dir }
                        if (cont.isActive) cont.resume(list)
                    } catch (e: Throwable) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        })
    }

    /** 解析 207 Multi-Status，用 Android 自带 Pull 解析器，无需额外依赖 */
    private fun parseMultiStatus(xml: String): List<DavEntry> {
        val result = mutableListOf<DavEntry>()
        if (xml.isBlank()) return result

        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))

        var href = ""
        var size = 0L
        var modified = ""
        var isDir = false
        var inResponse = false
        var tag: String? = null
        val buf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name?.lowercase().orEmpty()
                    tag = name
                    buf.setLength(0)
                    if (name == "response") {
                        inResponse = true
                        href = ""
                        size = 0L
                        modified = ""
                        isDir = false
                    } else if (inResponse && name == "collection") {
                        isDir = true
                    }
                }

                XmlPullParser.TEXT -> if (inResponse && tag != null) buf.append(parser.text)

                XmlPullParser.END_TAG -> {
                    val name = parser.name?.lowercase().orEmpty()
                    if (inResponse) {
                        val text = buf.toString().trim()
                        when (name) {
                            "href" -> href = text
                            "getcontentlength" -> size = text.toLongOrNull() ?: 0L
                            "getlastmodified" -> modified = text
                            "response" -> {
                                inResponse = false
                                pathFromHref(href)?.let { p ->
                                    val path = normalizePath(p)
                                    if (path.isNotEmpty()) {
                                        result.add(
                                            DavEntry(
                                                path = path,
                                                name = path.substringAfterLast('/'),
                                                isDirectory = isDir,
                                                size = size,
                                                modified = modified,
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    tag = null
                    buf.setLength(0)
                }
            }
            event = parser.next()
        }
        return result
    }

    companion object {
        private const val TAG = "WebDavClient"

        /** 连续这么多个目录请求失败就认为 OpenList 已不可用 */
        private const val MAX_CONSECUTIVE_FAILURES = 4

        private val AUDIO_EXT = listOf(
            ".mp3", ".flac", ".m4a", ".aac", ".wav", ".ogg", ".opus",
            ".ape", ".wma", ".alac", ".dsf", ".dff", ".aiff", ".aif", ".wv",
        )
        private val VIDEO_EXT = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".webm", ".ts", ".flv", ".wmv")

        private const val PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/>" +
                "</d:prop></d:propfind>"

        fun isMedia(name: String): Boolean {
            val lower = name.lowercase()
            return AUDIO_EXT.any { lower.endsWith(it) } || VIDEO_EXT.any { lower.endsWith(it) }
        }

        fun isVideo(name: String): Boolean {
            val lower = name.lowercase()
            return VIDEO_EXT.any { lower.endsWith(it) }
        }

        fun normalizeDavBase(url: String): String {
            var base = url.trim()
            if (base.isEmpty()) return ""
            if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) return ""
            base = base.trimEnd('/')
            return if (base.endsWith("/dav", ignoreCase = true)) base else "$base/dav"
        }

        /** 统一成 “/a/b” 形式，去掉多余的尾部斜杠 */
        fun normalizePath(path: String): String {
            var p = path.trim()
            if (p.isEmpty()) return "/"
            if (p.contains('?')) p = p.substringBefore('?')
            if (!p.startsWith("/")) p = "/$p"
            // 编译成顶层常量：原来是 Regex("/{2,}") 每次调用都编译一次。
            // pathKeyOf 会为列表里每一首歌都调一次 normalizePath，大库下就是几千次正则编译
            p = p.replace(MULTI_SLASH, "/")
            if (p.length > 1) p = p.trimEnd('/')
            return if (p.isEmpty()) "/" else p
        }

        /** href 可能是 "/dav/quark/..." 或完整 URL，统一还原成 OpenList 路径 */
        fun pathFromHref(href: String): String? {
            var h = href.trim()
            if (h.isEmpty()) return null
            if (h.startsWith("http://", true) || h.startsWith("https://", true)) {
                h = try {
                    java.net.URI(h).rawPath ?: return null
                } catch (_: Exception) {
                    return null
                }
            }
            h = h.substringBefore('?').substringBefore('#')
            if (!h.startsWith("/")) h = "/$h"
            // 找到独立的 /dav 段后截掉（避免误伤 /dave 之类）
            val idx = segmentIndexOf(h, "dav")
            if (idx >= 0) h = h.substring(idx + 4).ifEmpty { "/" }
            if (!h.startsWith("/")) h = "/$h"
            return decodePath(h)
        }

        private fun segmentIndexOf(path: String, segment: String): Int {
            var from = 0
            while (true) {
                val i = path.indexOf("/$segment", from, ignoreCase = true)
                if (i < 0) return -1
                val next = i + 1 + segment.length
                if (next == path.length || path[next] == '/') return i
                from = next
            }
        }

        fun encodePath(path: String): String {
            val p = if (path.startsWith("/")) path else "/$path"
            return p.split('/').joinToString("/") {
                URLEncoder.encode(it, "UTF-8").replace("+", "%20")
            }
        }

        fun decodePath(path: String): String =
            path.split('/').joinToString("/") {
                try {
                    // 路径里的 '+' 是字面量，不能按表单编码解成空格
                    URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
                } catch (_: Exception) {
                    it
                }
            }

        /** 连续斜杠折叠用（normalizePath 里原来的内联 Regex 提升成常量，避免每次调用重新编译） */
        private val MULTI_SLASH = Regex("/{2,}")
    }
}

class WebDavException(message: String) : Exception(message)
