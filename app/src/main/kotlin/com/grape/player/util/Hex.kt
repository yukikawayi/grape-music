package com.grape.player.util

private val HEX_CHARS = "0123456789abcdef".toCharArray()

/**
 * 字节数组转小写 hex（MD5 摘要 → 设置键 / 文件名等）。
 *
 * 查表拼接替代 `"%02x".format(it)`：后者每个字节走一次 java.util.Formatter，
 * 扫描几千个文件时（EmbeddedTagReader / CoverStore 每个摘要 16~32 字节）分配量是实打实的。
 */
fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
    }
    return sb.toString()
}
