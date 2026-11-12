package com.fc.safe.desktop.backup

import com.fc.safe.desktop.DesktopSecret
import com.fc.safe.desktop.i18n.tr
import com.fc.safe.platform.macos.WalletSession
import com.google.gson.GsonBuilder
import core.crypto.CryptoDataStr
import core.crypto.Encryptor
import data.fcData.AlgorithmId

/**
 * Secrets export — byte-compatible with Android
 * `ExportSecretActivity.generateExportResult`:
 *
 * - For **encrypted** modes, each per-secret record is a full
 *   CryptoDataStr JSON wrapping the entire `ExportedSecret` body
 *   (NOT just the contentCipher field, which is how keys work).
 *   The receiver walks the stream, recognises the cipher wrapper,
 *   decrypts it, and gets back the inner secret JSON.
 * - For **no-encrypt**, each per-secret record is a plain
 *   `ExportedSecret` JSON.
 * - Per-secret content is always the decrypted plaintext — our
 *   on-disk `contentCipher` is bound to the EXPORTING wallet's
 *   password-derived key and can't be decrypted by the receiver,
 *   so we strip it before serialising.
 *
 * Uses `FC_AesGcm256_No1_NrC7` — same AEAD-by-default the rest of
 * the app uses. BackupHeader carries the alg name for the receiver
 * to display.
 */
internal object SecretExporter {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun export(
        secrets: List<DesktopSecret>,
        mode: ExportMode,
        enteredPassword: CharArray?,
    ): ExportResult {
        // Prepare plaintext payloads up front so the same loop can
        // emit encrypted or plain on a per-secret basis without
        // re-deciding. Decryption happens once here (Argon2 is
        // expensive; we don't want to re-decrypt on each mode
        // switch if the user toggles before hitting Export).
        val plaintext = secrets.map { sec ->
            val cipher = sec.contentCipher
            val content = if (cipher.isNullOrBlank()) ""
            else {
                val raw = WalletSession.decryptFromJson(cipher)
                try {
                    String(raw, Charsets.UTF_8)
                } finally {
                    raw.fill(0)
                }
            }
            ExportedSecret().apply {
                id = sec.id
                title = sec.title
                type = sec.type
                memo = sec.memo
                this.content = content
                contentCipher = null
                saveTime = null
            }
        }

        val header = BackupHeader().apply {
            time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(java.util.Date())
            items = secrets.size
            tClass = "Secret"
            alg = AlgorithmId.FC_AesGcm256_No1_NrC7.getDisplayName()
        }

        val pwdForKeys: CharArray
        var randomPassword: String? = null
        when (mode) {
            ExportMode.CURRENT_PASSWORD -> {
                requireNotNull(enteredPassword) { tr("Password required for current-password export") }
                require(enteredPassword.isNotEmpty()) { tr("Password is empty") }
                pwdForKeys = enteredPassword
            }
            ExportMode.RANDOM_PASSWORD -> {
                randomPassword = BackupKey.makeRandomPassword()
                pwdForKeys = randomPassword.toCharArray()
            }
            ExportMode.NONE -> {
                pwdForKeys = CharArray(0)
            }
        }

        val jsonList = ArrayList<String>()
        if (mode != ExportMode.NONE) {
            val backupKey = BackupKey().apply {
                time = header.time
                // The random password is handed over apart from the export, never inside it.
                hint = when (mode) {
                    ExportMode.RANDOM_PASSWORD -> BackupKey.RANDOM_PASSWORD_HINT
                    else -> BackupKey.APP_PASSWORD_HINT
                }
            }
            jsonList += gson.toJson(backupKey)
            jsonList += gson.toJson(header)
        } else {
            // Per Android's shape: the header goes first even in
            // no-encrypt mode so the receiver can report "found N
            // Secret items" before parsing individual entries.
            jsonList += gson.toJson(header)
        }

        try {
            for (sec in plaintext) {
                val body = gson.toJson(sec)
                val wire = when (mode) {
                    ExportMode.CURRENT_PASSWORD,
                    ExportMode.RANDOM_PASSWORD -> {
                        val cdb = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
                            .encryptByPassword(body.toByteArray(Charsets.UTF_8), pwdForKeys)
                        // FVEP8: a Password cipher names no key — the derived key
                        // changes with every salt. FC-JDK v0.2 still sets one.
                        cdb.keyName = null
                        val cds = CryptoDataStr.fromCryptoDataByte(cdb)
                        // Same convention as WalletSession.encryptToJson:
                        // null the plaintext data field so it can't leak
                        // into the wire copy.
                        cds.data = null
                        cds.toNiceJson()
                    }
                    ExportMode.NONE -> body
                }
                jsonList += wire
            }
        } finally {
            if (mode == ExportMode.RANDOM_PASSWORD) pwdForKeys.fill(Char.MIN_VALUE)
            // CURRENT_PASSWORD: caller retains ownership of its own array.
        }

        return ExportResult(BackupCodec.makeJsonListString(jsonList), randomPassword)
    }
}
