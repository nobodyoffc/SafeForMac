package com.fc.safe.desktop.backup

import com.fc.safe.desktop.DesktopKeyInfo
import com.fc.safe.platform.macos.WalletSession
import com.google.gson.GsonBuilder
import core.crypto.Encryptor
import data.fcData.AlgorithmId
import utils.BytesUtils
import utils.Hex
import utils.IdNameUtils
import com.fc.safe.desktop.backup.Base32Shim
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * Export mode — mirrors the three radio options in Android's
 * `ExportKeysActivity`. String values are the same so logs line up
 * between the two apps.
 */
enum class ExportMode(val displayName: String) {
    CURRENT_PASSWORD("Current Password"),
    RANDOM_PASSWORD("Random Password"),
    NONE("Don't encrypt"),
}

/** Result of an export operation — the concatenated backup text plus (if any) the random password the user must remember. */
data class ExportResult(
    val text: String,
    val randomPassword: String?,
)

/**
 * Produces a backup blob byte-for-byte compatible with Android's
 * `BackupKeysActivity.generateExportResult` so the Android app can
 * round-trip our exports.
 *
 * For encrypted modes:
 *   [BackupKey] JSON, [BackupHeader] JSON, then one password-encrypted
 *   [ExportedKeyInfo] JSON per signed key. Watch-only keys are skipped
 *   (parity with Android — nothing secret to back up).
 *
 * For [ExportMode.NONE]:
 *   No header blocks. Each signed key's `prikey` is emitted as hex.
 *
 * Runs decryption + re-encryption per key via [WalletSession] + FC-JDK
 * [Encryptor]; Argon2id is ~500ms/call, so call this off the UI thread.
 */
internal object KeyExporter {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun export(
        keys: List<DesktopKeyInfo>,
        mode: ExportMode,
        enteredPassword: CharArray?,
    ): ExportResult {
        val signedKeys = keys.filter { !it.prikeyCipher.isNullOrBlank() }
        if (signedKeys.isEmpty()) {
            throw IllegalStateException(
                "No signed keys to export — watch-only keys have nothing secret to back up"
            )
        }

        val now = System.currentTimeMillis()
        val timeStr = dateFmt.format(Date(now))
        val header = BackupHeader().apply {
            time = timeStr
            items = signedKeys.size
            tClass = "KeyInfo"
            alg = AlgorithmId.FC_AesGcm256_No1_NrC7.getDisplayName()
        }

        val pwdForKeys: CharArray
        var randomPassword: String? = null
        when (mode) {
            ExportMode.CURRENT_PASSWORD -> {
                requireNotNull(enteredPassword) { "Password required for current-password export" }
                require(enteredPassword.isNotEmpty()) { "Password is empty" }
                header.keyName = IdNameUtils.makeKeyName(
                    BytesUtils.utf8CharArrayToByteArray(enteredPassword)
                )
                pwdForKeys = enteredPassword
            }
            ExportMode.RANDOM_PASSWORD -> {
                val randomBytes = ByteArray(8).also(SecureRandom()::nextBytes)
                randomPassword = Base32Shim.toBase32(randomBytes)
                header.keyName = IdNameUtils.makeKeyName(randomPassword.toByteArray())
                pwdForKeys = randomPassword.toCharArray()
            }
            ExportMode.NONE -> {
                pwdForKeys = CharArray(0)
            }
        }

        val jsonList = ArrayList<String>()
        if (mode != ExportMode.NONE) {
            val backupKey = BackupKey().apply {
                time = timeStr
                keyName = header.keyName
                when (mode) {
                    ExportMode.RANDOM_PASSWORD -> password = randomPassword
                    ExportMode.CURRENT_PASSWORD -> hint =
                        "App password cannot be shown — keep it carefully."
                    else -> {}
                }
            }
            jsonList += gson.toJson(backupKey)
            jsonList += gson.toJson(header)
        }

        try {
            for (key in signedKeys) {
                val json = buildKeyJson(key, mode, pwdForKeys) ?: continue
                jsonList += json
            }
        } finally {
            if (mode == ExportMode.RANDOM_PASSWORD) pwdForKeys.fill(Char.MIN_VALUE)
            // CURRENT_PASSWORD: caller still owns the array; don't wipe theirs.
        }

        return ExportResult(BackupCodec.makeJsonListString(jsonList), randomPassword)
    }

    /**
     * Per-key JSON: decrypt our stored cipher to raw prikey bytes via
     * [WalletSession], then re-encrypt (or hex-encode) under the
     * requested export mode. Matches Android's `addKeyInfoJson`.
     */
    private fun buildKeyJson(
        key: DesktopKeyInfo,
        mode: ExportMode,
        exportPassword: CharArray,
    ): String? {
        val cipher = key.prikeyCipher ?: return null
        val rawPrikey = WalletSession.decryptFromJson(cipher)
        try {
            val export = ExportedKeyInfo().apply {
                id = key.id
                label = key.label
                saveTime = if (key.savedAt > 0) dateFmt.format(Date(key.savedAt)) else null
                // Android nulls pubkey for signed-key export; importer re-derives from prikey.
                pubkey = null
            }
            when (mode) {
                ExportMode.CURRENT_PASSWORD,
                ExportMode.RANDOM_PASSWORD -> {
                    // FC_AesGcm256: AEAD with built-in auth tag. GCM's
                    // 12-byte IV + 16-byte tag means the bundle layout
                    // differs from CBC (no 4-byte sum field), but
                    // fromBundle/decryptBundleByPassword on the Android
                    // side handle both. The BackupHeader.alg marker
                    // matches this choice.
                    val cdb = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
                        .encryptByPassword(rawPrikey, exportPassword)
                    val base64 = cdb.toBase64()
                        ?: throw IllegalStateException("Failed to encode cipher")
                    export.prikey = null
                    export.prikeyCipher = base64
                }
                ExportMode.NONE -> {
                    export.prikey = Hex.toHex(rawPrikey)
                    export.prikeyCipher = null
                }
            }
            return gson.toJson(export)
        } finally {
            rawPrikey.fill(0)
        }
    }
}
