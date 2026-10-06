package com.fc.safe.desktop.backup

import java.io.InputStream

/**
 * Shims for FC-AJDK-only helpers that never made it back to FC-JDK.
 * See migration plan §2.2 and §5. Drop these once FC-JDK exposes them.
 *
 * - [readOneJsonFromInputStream] — port of Android's brace-counter JSON
 *   splitter that lets us walk a concatenated backup blob one object at
 *   a time. FC-JDK has only `readOneJsonFromFile(FileInputStream)`.
 * - [makeJsonListString] — joins JSON objects with `\n\n` (matches
 *   Android's wire format).
 */
internal object BackupCodec {

    /**
     * Reads the next balanced `{…}` JSON object from [input]. Mirrors
     * Android's `JsonUtils.readOneJsonFromInputStream` brace counter:
     * every byte is appended to the buffer; `\` is passed through
     * without affecting depth so escape sequences inside strings do
     * not break counting. Returns null at EOF.
     */
    fun readOneJsonFromInputStream(input: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        var depth = 0
        var counting = false

        while (true) {
            val b = input.read()
            if (b < 0) return null
            val ch = b.toChar()

            if (ch == '\\') {
                buf.write(b)
                continue
            }

            if (ch == '{') {
                counting = true
                depth++
            } else if (ch == '}' && counting) {
                depth--
            }

            buf.write(b)

            if (counting && depth == 0) return buf.toByteArray()
        }
    }

    /** Joins JSONs with a blank-line separator, matching Android. */
    fun makeJsonListString(jsonList: List<String>): String =
        jsonList.joinToString("\n\n")
}

