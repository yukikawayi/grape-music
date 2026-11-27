package com.grape.player.util

/**
 * 媒体路径工具 —— 从 OpenList 路径推导标题 / 文件夹名。
 * 原 STRM 解析器已随 strm 方案一起下线，这里只保留纯路径处理。
 */
object MediaPathUtil {

    /** /quark/音乐/A/BELOVED/08.Polaroid.flac → "08.Polaroid" */
    fun titleFromPath(path: String): String {
        val name = path.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return ""
        return try {
            java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            name
        }.substringBeforeLast('.', name)
            .replace('_', ' ')
            .trim()
    }

    /** /quark/音乐/A/BELOVED/08.Polaroid.flac → "BELOVED" */
    fun folderFromPath(path: String): String {
        val normalized = path.trimEnd('/')
        val parent = normalized.substringBeforeLast('/', "")
        val name = parent.substringAfterLast('/')
        return try {
            java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            name
        }.ifEmpty { "/" }
    }
}
