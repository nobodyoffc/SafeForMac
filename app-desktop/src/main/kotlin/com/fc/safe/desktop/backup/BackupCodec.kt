package com.fc.safe.desktop.backup

import core.crypto.CryptoDataByte
import core.crypto.Kdf
import java.io.InputStream
import java.util.Base64

/**
 * Shims for FC-AJDK-only helpers that never made it back to FC-JDK.
 * See migration plan §2.2 and §5. Drop these once FC-JDK exposes them.
 *
 * - [readOneJsonFromInputStream] — port of Android's brace-counter JSON
 *   splitter that lets us walk a concatenated backup blob one object at
 *   a time. FC-JDK has only `readOneJsonFromFile(FileInputStream)`.
 * - [makeJsonListString] — joins JSON objects with `\n\n` (matches
 *   Android's wire format).
 * - [toBase64] / [fromBase64] on [CryptoDataByte] — Android's one-line
 *   portable cipher encoding. FC-JDK has `toBundle()` / `fromBundle()`
 *   but not the base64 wrappers.
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

/** Android parity — `new CryptoDataByte().toBase64()` equivalent. */
internal fun CryptoDataByte.toBase64OrNull(): String? {
    val bundle = toBundle() ?: return null
    return Base64.getEncoder().encodeToString(bundle)
}

/** Android parity — `CryptoDataByte.fromBase64(s)` equivalent. */
internal fun cryptoDataByteFromBase64(base64: String): CryptoDataByte? = runCatching {
    CryptoDataByte.fromBundle(Base64.getDecoder().decode(base64))
}.getOrNull()

/**
 * Known KDFs, in the order [decryptBundleByPassword] tries them.
 *
 * The bundle format doesn't include a KDF marker, so a password-
 * encrypted cipher can't tell the reader which KDF to run. Android's
 * FC-AJDK ships `Decryptor.decryptBundleByPassword` which tries
 * Argon2id (the modern default) first and falls back to Sha256Iv
 * (the legacy KDF older exports used). FC-JDK doesn't have that
 * helper yet — we reproduce the fallback locally.
 */
internal val KDF_FALLBACK_ORDER: List<Kdf> = listOf(
    Kdf.Argon2id_No1_NrC7,
    Kdf.Sha256Iv_No1_NrC7,
)
