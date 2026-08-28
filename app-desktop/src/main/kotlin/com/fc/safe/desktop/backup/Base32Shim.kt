package com.fc.safe.desktop.backup

/**
 * Unpadded Base32 using RFC 4648 alphabet (A-Z + 2-7). Ported from
 * `com.fc.fc_ajdk.utils.Base32` (Android) — FC-JDK doesn't have it.
 * [toBase32] is used for random-password generation; [fromBase32] is
 * used by TOTP to decode the seed at code-generation time.
 */
internal object Base32Shim {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val REVERSE = IntArray(128) { -1 }.also {
        for ((idx, ch) in ALPHABET.withIndex()) it[ch.code] = idx
    }

    fun toBase32(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var i = 0
        var index = 0
        while (i < bytes.size) {
            val curr = bytes[i].toInt() and 0xFF
            val digit: Int
            if (index > 3) {
                val next = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
                var d = curr and (0xFF shr index)
                index = (index + 5) % 8
                d = d shl index
                d = d or (next shr (8 - index))
                digit = d
                i++
            } else {
                digit = (curr shr (8 - (index + 5))) and 0x1F
                index = (index + 5) % 8
                if (index == 0) i++
            }
            sb.append(ALPHABET[digit])
        }
        return sb.toString()
    }

    /**
     * Decode a Base32 string. Tolerant of whitespace and `=` padding
     * (both common in hand-pasted TOTP seeds). Case-insensitive.
     * Throws [IllegalArgumentException] on an unknown character.
     */
    fun fromBase32(s: String): ByteArray {
        val cleaned = s.uppercase().replace("=", "").filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return ByteArray(0)
        val out = ByteArray(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var count = 0
        for (c in cleaned) {
            val v = if (c.code < REVERSE.size) REVERSE[c.code] else -1
            require(v >= 0) { "Invalid Base32 character: $c" }
            buffer = (buffer shl 5) or v
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[count++] = ((buffer shr (bitsLeft - 8)) and 0xFF).toByte()
                bitsLeft -= 8
            }
        }
        return if (count == out.size) out else out.copyOf(count)
    }
}
