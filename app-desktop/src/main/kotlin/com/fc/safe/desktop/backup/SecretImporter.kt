package com.fc.safe.desktop.backup

import com.fc.safe.desktop.DesktopSecret
import com.fc.safe.platform.macos.WalletSession
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import core.crypto.CryptoDataByte
import core.crypto.Decryptor
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Consumes Android-shaped secret export blobs and produces
 * [DesktopSecret] records ready for local persistence.
 *
 * Parse flow (matches Android `FcEntityImporter` + `BackupUtils.readBackup`):
 *   1. Walk the stream with [BackupCodec.readOneJsonFromInputStream].
 *   2. Classify each JSON as [BackupKey] / [BackupHeader] /
 *      encrypted [CryptoDataByte] blob (whole-secret wrapper) /
 *      plain [ExportedSecret].
 *   3. If a [BackupKey] carried a random `password`, adopt it as
 *      the per-secret decryption password and don't prompt.
 *   4. If there are encrypted blobs and no password yet, throw
 *      [PasswordRequired] so the UI can collect the user's
 *      password and retry.
 *   5. Decrypt each CryptoDataByte blob → the plaintext JSON is
 *      an [ExportedSecret]; parse it and fold into the list.
 *   6. Re-encrypt each secret's `content` field under the current
 *      [WalletSession] password and store as [DesktopSecret].
 */
internal object SecretImporter {
    private val gson = Gson()

    class PasswordRequired(val pendingBlob: String) :
        RuntimeException("Encrypted export — password required to decrypt secret body")

    fun importText(text: String, password: String?): List<DesktopSecret> =
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use {
            importStream(it, password, textBlobForRetry = text)
        }

    private fun importStream(
        input: InputStream,
        password: String?,
        textBlobForRetry: String,
    ): List<DesktopSecret> {
        val backupKeys = ArrayList<BackupKey>()
        val headers = ArrayList<BackupHeader>()
        val cipherBlobs = ArrayList<String>() // raw CryptoDataStr JSONs awaiting decrypt
        val plainExported = ArrayList<ExportedSecret>()

        while (true) {
            val jsonBytes = BackupCodec.readOneJsonFromInputStream(input) ?: break
            val json = String(jsonBytes, Charsets.UTF_8).trim()
            if (json.isEmpty()) continue
            classify(json, backupKeys, headers, cipherBlobs, plainExported)
        }

        val bk = backupKeys.firstOrNull()
        val bh = headers.firstOrNull()
        if (bk != null && bh != null && bk.keyName != null && bh.keyName != null &&
            bk.keyName != bh.keyName
        ) {
            throw IllegalArgumentException("Backup key and header key names do not match")
        }

        val decryptPwd: String? = bk?.password ?: password

        if (cipherBlobs.isNotEmpty() && decryptPwd.isNullOrEmpty()) {
            throw PasswordRequired(textBlobForRetry)
        }

        // Decrypt each cipher blob and deserialize to ExportedSecret.
        if (cipherBlobs.isNotEmpty()) {
            val decryptor = Decryptor()
            val pwdChars = decryptPwd!!.toCharArray()
            try {
                for (blob in cipherBlobs) {
                    val decrypted = decryptor.decryptJsonByPassword(blob, pwdChars.copyOf())
                    if (decrypted.code != 0) {
                        // One bad blob (wrong password or corrupted)
                        // — skip it and keep going. The UI surfaces
                        // "N of M secrets imported" so the user sees
                        // partial progress.
                        continue
                    }
                    val payload = decrypted.data?.let { String(it, Charsets.UTF_8) } ?: continue
                    runCatching { gson.fromJson(payload, ExportedSecret::class.java) }
                        .getOrNull()
                        ?.let(plainExported::add)
                }
            } finally {
                pwdChars.fill(Char.MIN_VALUE)
            }
        }

        if (plainExported.isEmpty()) {
            throw IllegalArgumentException("No secret records found in input")
        }

        // Re-encrypt each secret's plaintext content under the
        // current WalletSession password and build DesktopSecret
        // records. If a record has no content, we skip it — the
        // secret would render as empty and is probably corrupt.
        val out = ArrayList<DesktopSecret>()
        for (es in plainExported) {
            val content = es.content ?: continue
            if (content.isEmpty()) continue
            val title = es.title ?: "(no title)"
            val cipher = WalletSession.encryptToJson(content.toByteArray(Charsets.UTF_8))
            out += DesktopSecret().apply {
                // Prefer the exporter's id so the same secret
                // round-trips to the same row (Android uses
                // sha256x2(title || content) hex; we compute
                // the same hash via `assignId` as a fallback).
                es.id?.let { setId(it) } ?: assignId(title, content)
                this.title = title
                this.type = es.type
                this.memo = es.memo
                this.contentCipher = cipher
                this.savedAt = System.currentTimeMillis()
            }
        }
        return out
    }

    private fun classify(
        json: String,
        backupKeys: MutableList<BackupKey>,
        headers: MutableList<BackupHeader>,
        cipherBlobs: MutableList<String>,
        plainExported: MutableList<ExportedSecret>,
    ) {
        // BackupKey: has `password` or `symkey` populated.
        tryParse<BackupKey>(json)?.let {
            if (it.password != null || it.symkey != null) {
                backupKeys += it
                return
            }
        }
        // BackupHeader: has `items` + `tClass`.
        tryParse<BackupHeader>(json)?.let {
            if (it.items != null && it.tClass != null) {
                headers += it
                return
            }
        }
        // CryptoDataStr (whole-secret cipher): parse via
        // CryptoDataByte.fromJson which reads the CryptoDataStr
        // wire shape. A cipher entry has `cipher` + `iv` at minimum.
        runCatching { CryptoDataByte.fromJson(json) }.getOrNull()?.let { cdb ->
            if (cdb.cipher != null && cdb.iv != null) {
                cipherBlobs += json
                return
            }
        }
        // Plain ExportedSecret: has id (or at least title) AND
        // content (or contentCipher). We require content since
        // our import re-encrypts from plaintext.
        tryParse<ExportedSecret>(json)?.let {
            if (!it.id.isNullOrBlank() || !it.title.isNullOrBlank()) {
                plainExported += it
            }
        }
    }

    private inline fun <reified T> tryParse(json: String): T? = try {
        gson.fromJson(json, T::class.java)
    } catch (_: JsonSyntaxException) {
        null
    } catch (_: Exception) {
        null
    }
}
