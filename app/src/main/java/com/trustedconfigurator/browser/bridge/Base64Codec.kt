package com.trustedconfigurator.browser.bridge

/**
 * Standard (RFC 4648) base64 with padding.
 *
 * Hand-rolled rather than using [android.util.Base64] so the encoding used on
 * the bridge's hot path is exercised by plain JVM unit tests, and rather than
 * [java.util.Base64] so that minSdk can stay at 24 without desugaring.
 */
object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val DECODE_TABLE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val word = (data[i].toInt() and 0xFF shl 16) or
                (data[i + 1].toInt() and 0xFF shl 8) or
                (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[word ushr 18 and 0x3F])
            out.append(ALPHABET[word ushr 12 and 0x3F])
            out.append(ALPHABET[word ushr 6 and 0x3F])
            out.append(ALPHABET[word and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val word = data[i].toInt() and 0xFF shl 16
                out.append(ALPHABET[word ushr 18 and 0x3F])
                out.append(ALPHABET[word ushr 12 and 0x3F])
                out.append("==")
            }
            2 -> {
                val word = (data[i].toInt() and 0xFF shl 16) or (data[i + 1].toInt() and 0xFF shl 8)
                out.append(ALPHABET[word ushr 18 and 0x3F])
                out.append(ALPHABET[word ushr 12 and 0x3F])
                out.append(ALPHABET[word ushr 6 and 0x3F])
                out.append('=')
            }
        }
        return out.toString()
    }

    fun decode(text: String?): ByteArray {
        if (text.isNullOrEmpty()) return ByteArray(0)

        var accumulator = 0
        var bitsHeld = 0
        val out = ByteArray(text.length / 4 * 3 + 3)
        var written = 0

        for (c in text) {
            if (c == '=') break
            val code = c.code
            val value = if (code < 128) DECODE_TABLE[code] else -1
            if (value < 0) {
                // Skip whitespace and other transport padding rather than failing:
                // a malformed byte here would abort a firmware write mid-flash.
                continue
            }
            accumulator = (accumulator shl 6) or value
            bitsHeld += 6
            if (bitsHeld >= 8) {
                bitsHeld -= 8
                out[written++] = (accumulator ushr bitsHeld and 0xFF).toByte()
            }
        }
        return out.copyOf(written)
    }
}
