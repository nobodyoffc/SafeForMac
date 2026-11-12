package com.fc.safe.desktop

import com.fc.safe.desktop.backup.BackupCodec
import core.crypto.CryptoDataByte
import core.crypto.Decryptor
import core.crypto.EncryptType
import core.crypto.KeyTools
import java.io.ByteArrayInputStream
import java.util.Base64

/**
 * Tells what a typed, pasted or scanned key text is, so one import dialog can take
 * a private key, public key, FID, password cipher or key backup JSON. Ported from
 * Android Safe's `KeyInputDetector`.
 *
 * The order of the checks matters: hex, WIF and FID text are all valid Base64
 * too, so the checksummed and fixed-shape forms are tried before a Base64 cipher
 * bundle. A phrase is never guessed: any text could be one, and a mistyped key
 * must not quietly become a different key.
 */
internal object KeyInputDetector {

    enum class Kind {
        EMPTY,
        /** 64 hex chars, 0x-prefixed hex, or WIF. */
        PRIKEY,
        /** Compressed or uncompressed public key hex. Watch-only. */
        PUBKEY,
        /** Watch-only. */
        FID,
        /** One password-encrypted object, as JSON or a Base64 bundle. */
        CIPHER,
        /** Encrypted, but not with a password, so it can't be opened here. */
        NON_PASSWORD_CIPHER,
        /** Key JSON: KeyInfo, backup header/key lines, or several ciphers. */
        BACKUP,
        /** Shaped like a private key, but not a valid one. */
        BAD_PRIKEY,
        /** Shaped like a public key, but not a valid one. */
        BAD_PUBKEY,
        /** Shaped like an FID, but the checksum fails. */
        BAD_FID,
        /** Several items; only backup JSON may carry more than one key. */
        MULTIPLE,
        UNKNOWN,
    }

    private val BASE58 = Regex("[1-9A-HJ-NP-Za-km-z]+")
    private val ALPHANUMERIC = Regex("[0-9A-Za-z]+")
    private val WHITESPACE = Regex("\\s")

    fun detect(input: String?): Kind {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return Kind.EMPTY

        if (text.startsWith("{")) return detectJson(text)
        if (WHITESPACE.containsMatchIn(text)) return Kind.MULTIPLE

        toPrikey32(text)?.let { it.fill(0); return Kind.PRIKEY }
        if (isPubkey(text)) return Kind.PUBKEY
        if (isFid(text)) return Kind.FID

        parseBundle(text)?.let {
            return if (isPasswordCipher(it)) Kind.CIPHER else Kind.NON_PASSWORD_CIPHER
        }

        if (looksLikePrikey(text)) return Kind.BAD_PRIKEY
        if (looksLikePubkey(text)) return Kind.BAD_PUBKEY
        if (looksLikeFid(text)) return Kind.BAD_FID
        return Kind.UNKNOWN
    }

    /** The 32-byte private key the text encodes, or null. Never throws. */
    fun toPrikey32(text: String): ByteArray? {
        // FC-JDK v0.2, unlike FC-AJDK, does not take the 0x form itself.
        val key = if (text.length == 66 && text.startsWith("0x", ignoreCase = true)) text.substring(2) else text
        return runCatching { KeyTools.getPrikey32(key) }.getOrNull()
    }

    /**
     * A fresh parse of a [Kind.CIPHER] input, or null. Decryption fills in the
     * parsed object, so parse again for every attempt.
     */
    fun parseCipher(input: String?): CryptoDataByte? {
        val text = input?.trim().orEmpty()
        if (!text.startsWith("{")) return parseBundle(text)
        val objects = readJsonObjects(text)
        return if (objects.size == 1) parseCipherJson(objects[0]) else null
    }

    /**
     * The plaintext of a [Kind.CIPHER] input, or null when the password is wrong or
     * the cipher is damaged. Runs the cipher's KDF, so call it off the UI thread.
     * A legacy bundle names no KDF; FC-JDK tries Argon2id and then Sha256Iv (FTSP29).
     */
    fun openCipher(input: String, password: CharArray): ByteArray? {
        val text = input.trim()
        val opened = runCatching {
            if (text.startsWith("{")) Decryptor().decryptJsonByPassword(text, password)
            else Decryptor().decryptBundleByPassword(Base64.getDecoder().decode(text), password)
        }.getOrNull() ?: return null
        return if (opened.code == 0) opened.data else null
    }

    /**
     * The private key inside a decrypted cipher: the raw 32 bytes, or its hex or WIF
     * text (Android's prikey QR backup encrypts the WIF text). Null when the
     * plaintext is something else, such as a key JSON. The input is left untouched.
     */
    fun prikeyFromPlaintext(data: ByteArray?): ByteArray? {
        if (data == null) return null
        if (data.size == 32) return data.copyOf()
        return toPrikey32(String(data, Charsets.UTF_8).trim())
    }

    fun isJson(data: ByteArray?): Boolean =
        data != null && String(data, Charsets.UTF_8).trim().startsWith("{")

    private fun detectJson(text: String): Kind {
        val objects = readJsonObjects(text)
        if (objects.isEmpty()) return Kind.UNKNOWN
        if (objects.size == 1) {
            parseCipherJson(objects[0])?.let {
                return if (isPasswordCipher(it)) Kind.CIPHER else Kind.NON_PASSWORD_CIPHER
            }
        }
        return Kind.BACKUP
    }

    private fun readJsonObjects(text: String): List<String> {
        val objects = ArrayList<String>()
        runCatching {
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use { input ->
                while (true) {
                    val json = BackupCodec.readOneJsonFromInputStream(input) ?: break
                    objects += String(json, Charsets.UTF_8)
                }
            }
        }
        return objects
    }

    private fun parseCipherJson(json: String): CryptoDataByte? =
        runCatching { withCipher(CryptoDataByte.fromJson(json)) }.getOrNull()

    private fun parseBundle(text: String): CryptoDataByte? =
        runCatching { withCipher(CryptoDataByte.fromBase64(text)) }.getOrNull()

    private fun withCipher(c: CryptoDataByte?): CryptoDataByte? =
        if (c == null || c.cipher == null || c.iv == null) null else c

    /** A JSON cipher without a type was always treated as password-encrypted before. */
    private fun isPasswordCipher(c: CryptoDataByte): Boolean =
        c.type == null || c.type == EncryptType.Password

    private fun isPubkey(text: String): Boolean =
        runCatching { KeyTools.isPubkey(text) }.getOrDefault(false)

    private fun isFid(text: String): Boolean =
        text.length in 26..35 && BASE58.matches(text) &&
            runCatching { KeyTools.isGoodFid(text) }.getOrDefault(false)

    private fun looksLikePrikey(text: String): Boolean {
        val len = text.length
        if (BASE58.matches(text)) {
            if (len == 52 && (text.startsWith("K") || text.startsWith("L"))) return true
            if (len == 51 && text.startsWith("5")) return true
        }
        if (len == 66 && (text.startsWith("0x") || text.startsWith("0X"))) {
            return ALPHANUMERIC.matches(text.substring(2))
        }
        return len == 64 && ALPHANUMERIC.matches(text)
    }

    private fun looksLikePubkey(text: String): Boolean =
        (text.length == 66 || text.length == 130) && ALPHANUMERIC.matches(text)

    private fun looksLikeFid(text: String): Boolean =
        (text.length == 33 || text.length == 34) &&
            (text.startsWith("F") || text.startsWith("3")) && BASE58.matches(text)
}
