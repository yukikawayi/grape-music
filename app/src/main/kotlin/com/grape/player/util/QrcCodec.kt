package com.grape.player.util

/**
 * QRC 歌词解密 —— 逐行移植 Web 版 qrc-decoder 的 custom_des 实现。
 *
 * 警告：这不是标准 DES！QQ 音乐的算法在结构上与 DES 相同（S 盒 / P 盒 / E 盒 /
 * PC1 / PC2 / 循环移位都一致），但 IP 与逆 IP 置换表是私有的自定义表，
 * 因此不能用 javax.crypto 的 DESede 代替，必须用本实现。
 *
 * 流程：十六进制密文 → 分块（每块 8 字节）走三轮单重 DES
 * （K3 解密 → K2 加密 → K1 解密）→ zlib inflate → 去 UTF-8 BOM → 明文 LRC。
 */
object QrcCodec {

    // ===== 常量表（与 qrc-decoder 逐值一致） =====

    private val S_BOXES = arrayOf(
        intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8, 4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
        intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5, 0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
        intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1, 13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
        intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9, 10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
        intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6, 4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
        intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8, 9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
        intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6, 1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
        intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2, 7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11),
    )

    private val P_BOX = intArrayOf(16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10, 2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25)

    private val E_BOX = intArrayOf(32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11, 12, 13, 12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21, 20, 21, 22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1)

    private val KEY_PERM_C = intArrayOf(56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35)

    private val KEY_PERM_D = intArrayOf(62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3)

    private val KEY_COMPRESSION = intArrayOf(13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18, 11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36, 46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33, 52, 45, 41, 49, 35, 28, 31)

    private val KEY_RND_SHIFT = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

    /** 自定义 IP 表（与标准 DES 不同！），1-based、从 64 位数据的最高位起数 */
    private val IP_RULE = intArrayOf(34, 42, 50, 58, 2, 10, 18, 26, 36, 44, 52, 60, 4, 12, 20, 28, 38, 46, 54, 62, 6, 14, 22, 30, 40, 48, 56, 64, 8, 16, 24, 32, 33, 41, 49, 57, 1, 9, 17, 25, 35, 43, 51, 59, 3, 11, 19, 27, 37, 45, 53, 61, 5, 13, 21, 29, 39, 47, 55, 63, 7, 15, 23, 31)

    private val INV_IP_RULE = intArrayOf(37, 5, 45, 13, 53, 21, 61, 29, 38, 6, 46, 14, 54, 22, 62, 30, 39, 7, 47, 15, 55, 23, 63, 31, 40, 8, 48, 16, 56, 24, 64, 32, 33, 1, 41, 9, 49, 17, 57, 25, 34, 2, 42, 10, 50, 18, 58, 26, 35, 3, 43, 11, 51, 19, 59, 27, 36, 4, 44, 12, 52, 20, 60, 28)

    // ===== 查找表 =====

    private val IP_LEFT = IntArray(2048)
    private val IP_RIGHT = IntArray(2048)
    private val INV_IP_LEFT = IntArray(2048)
    private val INV_IP_RIGHT = IntArray(2048)
    private val SP_TABLE = IntArray(512)
    private val EBOX_HIGH = IntArray(1024)
    private val EBOX_LOW = IntArray(1024)

    private const val BITS_28_MASK = 0xFFFFFFF0L

    private val K1 = "!@#)(*$%".toByteArray(Charsets.ISO_8859_1)
    private val K2 = "123ZXC!@".toByteArray(Charsets.ISO_8859_1)
    private val K3 = "!@#)(NHL".toByteArray(Charsets.ISO_8859_1)

    /** 解密轮序：K3 解密 → K2 加密 → K1 解密（对应 qrc-decoder 的 decryptSchedule） */
    private val ROUND_K3 = keySchedule(K3, decrypt = true)
    private val ROUND_K2 = keySchedule(K2, decrypt = false)
    private val ROUND_K1 = keySchedule(K1, decrypt = true)

    init {
        // IP / 逆 IP 查找表：单字节输入对各位置的影响
        for (bytePos in 0 until 8) {
            for (byteVal in 0 until 256) {
                val idx = (bytePos shl 8) or byteVal
                val pIp = applyPerm(byteVal.toLong() shl (56 - bytePos * 8), IP_RULE)
                IP_LEFT[idx] = (pIp ushr 32).toInt()
                IP_RIGHT[idx] = pIp.toInt()
                val pInv = applyPerm(byteVal.toLong() shl (56 - bytePos * 8), INV_IP_RULE)
                INV_IP_LEFT[idx] = (pInv ushr 32).toInt()
                INV_IP_RIGHT[idx] = pInv.toInt()
            }
        }
        // S-P 合并表
        for (s in 0 until 8) {
            for (input in 0 until 64) {
                val sboxIndex = input and 32 or ((input and 31) shr 1) or ((input and 1) shl 4)
                val preP = S_BOXES[s][sboxIndex] shl (28 - s * 4)
                SP_TABLE[(s shl 6) or input] = applyQqPbox(preP)
            }
        }
        // E 扩展查找表（按输入字节位置分四片）
        for (chunk in 0 until 4) {
            val shiftIn32 = (3 - chunk) * 8
            for (byteVal in 0 until 256) {
                val input = byteVal shl shiftIn32
                var high24 = 0
                var low24 = 0
                for (i in 0 until 24) {
                    if ((input ushr (32 - E_BOX[i])) and 1 == 1) high24 = high24 or (1 shl (23 - i))
                }
                for (i in 24 until 48) {
                    if ((input ushr (32 - E_BOX[i])) and 1 == 1) low24 = low24 or (1 shl (47 - i))
                }
                val t = (chunk shl 8) or byteVal
                EBOX_HIGH[t] = high24
                EBOX_LOW[t] = low24
            }
        }
    }

    private fun applyPerm(input: Long, rule: IntArray): Long {
        var out = 0L
        for (i in 0 until 64) {
            if ((input shr (64 - rule[i])) and 1L == 1L) out = out or (1L shl (63 - i))
        }
        return out
    }

    private fun applyQqPbox(input: Int): Int {
        var out = 0
        for (i in 0 until 32) {
            if ((input and (1 shl (32 - P_BOX[i]))) != 0) out = out or (1 shl (31 - i))
        }
        return out
    }

    /** 密钥位提取：把 8 字节视作两个小端 32 位整数拼接（QQ 特有约定） */
    private fun permuteFromKeyBytes(key: ByteArray, table: IntArray): Long {
        var out = 0L
        var mask = 1L shl (table.size - 1)
        for (pos in table) {
            val wordIndex = pos shr 5
            val bitInWord = pos and 31
            val byteInWord = bitInWord shr 3
            val bitInByte = bitInWord and 7
            val b = key[wordIndex * 4 + 3 - byteInWord].toInt() and 0xFF
            if ((b shr (7 - bitInByte)) and 1 == 1) out = out or mask
            mask = mask shr 1
        }
        return out
    }

    private fun rotLeft28(value: Long, amount: Int): Long {
        val v = value and BITS_28_MASK
        return ((v shl amount) or (v shr (28 - amount))) and BITS_28_MASK
    }

    private fun keySchedule(key: ByteArray, decrypt: Boolean): IntArray {
        val schedule = IntArray(32)
        val c0 = permuteFromKeyBytes(key, KEY_PERM_C)
        val d0 = permuteFromKeyBytes(key, KEY_PERM_D)
        var c = c0 shl 4
        var d = d0 shl 4
        for (i in 0 until 16) {
            val shift = KEY_RND_SHIFT[i]
            c = rotLeft28(c, shift)
            d = rotLeft28(d, shift)
            val toGen = if (decrypt) 15 - i else i
            var subkey = 0L
            for (k in 0 until 48) {
                val pos = KEY_COMPRESSION[k]
                val bit = if (pos < 28) (c shr (31 - pos)) and 1L else (d shr (31 - (pos - 27))) and 1L
                if (bit == 1L) subkey = subkey or (1L shl (47 - k))
            }
            val b5 = ((subkey shr 40) and 0xFF).toInt()
            val b4 = ((subkey shr 32) and 0xFF).toInt()
            val b3 = ((subkey shr 24) and 0xFF).toInt()
            val b2 = ((subkey shr 16) and 0xFF).toInt()
            val b1 = ((subkey shr 8) and 0xFF).toInt()
            val b0 = (subkey and 0xFF).toInt()
            schedule[toGen * 2] = (b5 shl 16) or (b4 shl 8) or b3
            schedule[toGen * 2 + 1] = (b2 shl 16) or (b1 shl 8) or b0
        }
        return schedule
    }

    private fun fFunction(state: Int, keyHigh24: Int, keyLow24: Int): Int {
        val b0 = (state ushr 24) and 0xFF
        val b1 = (state ushr 16) and 0xFF
        val b2 = (state ushr 8) and 0xFF
        val b3 = state and 0xFF
        val eHigh = EBOX_HIGH[b0] or EBOX_HIGH[256 or b1] or EBOX_HIGH[512 or b2] or EBOX_HIGH[768 or b3]
        val eLow = EBOX_LOW[b0] or EBOX_LOW[256 or b1] or EBOX_LOW[512 or b2] or EBOX_LOW[768 or b3]
        val xh = eHigh xor keyHigh24
        val xl = eLow xor keyLow24
        return SP_TABLE[(xh ushr 18) and 63] or
            SP_TABLE[64 or ((xh ushr 12) and 63)] or
            SP_TABLE[128 or ((xh ushr 6) and 63)] or
            SP_TABLE[192 or (xh and 63)] or
            SP_TABLE[256 or ((xl ushr 18) and 63)] or
            SP_TABLE[320 or ((xl ushr 12) and 63)] or
            SP_TABLE[384 or ((xl ushr 6) and 63)] or
            SP_TABLE[448 or (xl and 63)]
    }

    private fun desCrypt(input: ByteArray, inOff: Int, output: ByteArray, outOff: Int, schedule: IntArray) {
        var left = 0
        var right = 0
        for (i in 0 until 8) {
            val idx = (i shl 8) or (input[inOff + i].toInt() and 0xFF)
            left = left or IP_LEFT[idx]
            right = right or IP_RIGHT[idx]
        }
        for (i in 0 until 15) {
            val temp = right
            right = left xor fFunction(right, schedule[i * 2], schedule[i * 2 + 1])
            left = temp
        }
        left = left xor fFunction(right, schedule[30], schedule[31])
        var outLeft = 0
        var outRight = 0
        for (i in 0 until 4) {
            val idxL = (i shl 8) or ((left ushr (24 - i * 8)) and 0xFF)
            outLeft = outLeft or INV_IP_LEFT[idxL]
            outRight = outRight or INV_IP_RIGHT[idxL]
            val idxR = ((i + 4) shl 8) or ((right ushr (24 - i * 8)) and 0xFF)
            outLeft = outLeft or INV_IP_LEFT[idxR]
            outRight = outRight or INV_IP_RIGHT[idxR]
        }
        output[outOff] = (outLeft ushr 24).toByte()
        output[outOff + 1] = (outLeft ushr 16).toByte()
        output[outOff + 2] = (outLeft ushr 8).toByte()
        output[outOff + 3] = outLeft.toByte()
        output[outOff + 4] = (outRight ushr 24).toByte()
        output[outOff + 5] = (outRight ushr 16).toByte()
        output[outOff + 6] = (outRight ushr 8).toByte()
        output[outOff + 7] = outRight.toByte()
    }

    /**
     * 解密十六进制格式的 QRC 数据。
     * 译文（GetPlayLyricInfo 的 trans 字段）解出来直接是标准 LRC 文本。
     */
    fun decryptQrc(hex: String): String {
        val clean = if (hex.length % 2 == 1) hex.dropLast(1) else hex
        val bytes = ByteArray(clean.length / 2)
        for (i in bytes.indices) {
            bytes[i] = ((Character.digit(clean[i * 2], 16) shl 4)
                or Character.digit(clean[i * 2 + 1], 16)).toByte()
        }
        val t1 = ByteArray(bytes.size)
        val t2 = ByteArray(bytes.size)
        val out = ByteArray(bytes.size)
        var off = 0
        while (off + 8 <= bytes.size) {
            desCrypt(bytes, off, t1, off, ROUND_K3)
            desCrypt(t1, off, t2, off, ROUND_K2)
            desCrypt(t2, off, out, off, ROUND_K1)
            off += 8
        }

        val inflater = java.util.zip.Inflater()
        inflater.setInput(out)
        val result = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                result.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        var text = String(result.toByteArray(), Charsets.UTF_8)
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        return text
    }
}
