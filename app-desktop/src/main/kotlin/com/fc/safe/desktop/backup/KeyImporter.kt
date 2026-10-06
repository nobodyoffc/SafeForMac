package com.fc.safe.desktop.backup

import com.fc.safe.desktop.DesktopKeyInfo
import com.fc.safe.desktop.buildKeyFromPrikey32
import com.fc.safe.desktop.buildWatchOnlyFromFid
import com.fc.safe.desktop.buildWatchOnlyFromPubkey
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import core.crypto.Decryptor
import core.crypto.KeyTools
import utils.Hex
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Consumes Android-shaped export blobs and produces
 * [DesktopKeyInfo] records ready for local persistence.
 *
 * Parse flow (matches Android `FcEntityImporter` + `BackupUtils.readBackup`):
 *   1. Walk the stream with [BackupCodec.readOneJsonFromInputStream].
 *   2. Classify each JSON as [BackupKey] / [BackupHeader] /
 *      [ExportedKeyInfo]. (CryptoDataByte-shaped blobs — used by the
 *      Android Secret backup format — are ignored here; key exports
 *      don't emit them.)
 *   3. If a [BackupKey] carried a random `password`, adopt it as the
 *      per-key decryption password and don't prompt.
 *   4. Otherwise if any [ExportedKeyInfo] has a `prikeyCipher` set,
 *      throw [PasswordRequired] so the UI can collect the user's
 *      password and retry.
 */
internal object KeyImporter {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val gson = Gson()

    class PasswordRequired(val pendingBlob: String) :
        RuntimeException("Encrypted export — password required to decrypt prikeyCipher")

    fun importText(text: String, password: String?): List<DesktopKeyInfo> =
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use {
            importStream(it, password, textBlobForRetry = text)
        }

    private fun importStream(
        input: InputStream,
        password: String?,
        textBlobForRetry: String,
    ): List<DesktopKeyInfo> {
        val backupKeys = ArrayList<BackupKey>()
        val headers = ArrayList<BackupHeader>()
        val keyEntries = ArrayList<ExportedKeyInfo>()

        while (true) {
            val jsonBytes = BackupCodec.readOneJsonFromInputStream(input) ?: break
            val json = String(jsonBytes, Charsets.UTF_8).trim()
            if (json.isEmpty()) continue
            classify(json, backupKeys, headers, keyEntries)
        }

        if (keyEntries.isEmpty()) {
            throw IllegalArgumentException("No KeyInfo entries found in input")
        }

        // Keyname consistency check mirrors Android.
        val bk = backupKeys.firstOrNull()
        val bh = headers.firstOrNull()
        if (bk != null && bh != null && bk.keyName != null && bh.keyName != null &&
            bk.keyName != bh.keyName
        ) {
            throw IllegalArgumentException("Backup key and header key names do not match")
        }

        val decryptPwd: String? = bk?.password ?: password

        val needsPassword = keyEntries.any {
            !it.prikeyCipher.isNullOrBlank() && it.prikey.isNullOrBlank()
        }
        if (needsPassword && decryptPwd.isNullOrEmpty()) {
            throw PasswordRequired(textBlobForRetry)
        }

        return keyEntries.mapNotNull { toDesktopKeyInfo(it, decryptPwd) }
    }

    private fun classify(
        json: String,
        backupKeys: MutableList<BackupKey>,
        headers: MutableList<BackupHeader>,
        keyEntries: MutableList<ExportedKeyInfo>,
    ) {
        // BackupKey has a password or symkey field populated.
        tryParse<BackupKey>(json)?.let {
            if (it.password != null || it.symkey != null) {
                backupKeys += it
                return
            }
        }
        tryParse<BackupHeader>(json)?.let {
            if (it.items != null && it.time != null && it.tClass != null) {
                headers += it
                return
            }
        }
        tryParse<ExportedKeyInfo>(json)?.let {
            // Must have id AND at least one of prikey/prikeyCipher/pubkey.
            if (it.id.isNullOrBlank()) return
            val hasMaterial = !it.prikey.isNullOrBlank() ||
                !it.prikeyCipher.isNullOrBlank() ||
                !it.pubkey.isNullOrBlank()
            if (hasMaterial || KeyTools.isGoodFid(it.id!!)) keyEntries += it
        }
    }

    private inline fun <reified T> tryParse(json: String): T? = try {
        gson.fromJson(json, T::class.java)
    } catch (_: JsonSyntaxException) {
        null
    } catch (_: Exception) {
        null
    }

    /**
     * Materialise a [DesktopKeyInfo]:
     * - If `prikey` is set → use hex → [buildKeyFromPrikey32].
     * - Else if `prikeyCipher` is set with a password → decrypt via
     *   FC-JDK [Decryptor] → [buildKeyFromPrikey32].
     * - Else if `pubkey` is set → watch-only.
     * - Else if `id` is a valid FID → watch-only from FID.
     *
     * Preserves the original [ExportedKeyInfo.label] and [ExportedKeyInfo.saveTime]
     * so round-tripping through Android doesn't reset them.
     */
    private fun toDesktopKeyInfo(entry: ExportedKeyInfo, password: String?): DesktopKeyInfo? {
        val label = entry.label
        val savedAt = parseSaveTime(entry.saveTime) ?: System.currentTimeMillis()

        // Unencrypted prikey hex
        val hex = entry.prikey
        if (!hex.isNullOrBlank()) {
            val bytes = KeyTools.getPrikey32(hex) ?: return null
            return checkedKey(entry, bytes, label, savedAt)
        }

        // Password-encrypted cipher: a Base64 bundle (FTSP30). FC-JDK runs the
        // KDF a type-4 bundle names, or for a legacy type-3 bundle tries
        // Argon2id and then Sha256Iv (FTSP29).
        val cipher = entry.prikeyCipher
        if (!cipher.isNullOrBlank()) {
            if (password.isNullOrEmpty()) return null
            val bundle = runCatching { Base64.getDecoder().decode(cipher.trim()) }.getOrNull() ?: return null
            val pwdChars = password.toCharArray()
            try {
                val decrypted = Decryptor().decryptBundleByPassword(bundle, pwdChars)
                if (decrypted.code != 0) return null
                val raw = decrypted.data ?: return null
                try {
                    val prikey32 = KeyTools.getPrikey32(raw) ?: return null
                    return checkedKey(entry, prikey32, label, savedAt)
                } finally {
                    raw.fill(0)
                }
            } finally {
                pwdChars.fill(Char.MIN_VALUE)
            }
        }

        // Watch-only by pubkey
        val pub = entry.pubkey
        if (!pub.isNullOrBlank() && KeyTools.isPubkey(pub)) {
            return buildWatchOnlyFromPubkey(pub, label, savedAt)
        }

        // Watch-only by FID
        val fid = entry.id
        if (!fid.isNullOrBlank() && KeyTools.isGoodFid(fid)) {
            return buildWatchOnlyFromFid(fid, label, savedAt)
        }

        return null
    }

    /**
     * The key, once its prikey is shown to be the entry's: FTSP31 refuses a list whose
     * prikey belongs to another FID than the `id` it is listed under (or whose `pubkey`
     * is another key's), rather than adding it under the FID the prikey makes.
     */
    private fun checkedKey(entry: ExportedKeyInfo, prikey32: ByteArray, label: String?, savedAt: Long): DesktopKeyInfo {
        val fid = KeyTools.prikeyToFid(prikey32)
        val claimed = entry.id
        if (!claimed.isNullOrBlank() && claimed != fid) {
            throw IllegalArgumentException("The entry says $claimed, but its prikey belongs to $fid")
        }
        val pub = entry.pubkey
        if (!pub.isNullOrBlank() && !pub.equals(Hex.toHex(KeyTools.prikeyToPubkey(prikey32)), ignoreCase = true)) {
            throw IllegalArgumentException("The entry's pubkey is not the pubkey of its prikey ($fid)")
        }
        return buildKeyFromPrikey32(prikey32, label, savedAt)
    }

    private fun parseSaveTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { dateFmt.parse(s)?.time }.getOrNull()
    }
}
