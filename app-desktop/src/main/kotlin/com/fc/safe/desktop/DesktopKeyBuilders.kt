package com.fc.safe.desktop

import com.fc.safe.platform.macos.WalletSession
import constants.Constants as FcConstants
import core.crypto.Hash
import core.crypto.Kdf
import core.crypto.KeyTools
import utils.Hex

/**
 * Core builder: prikey32 → full [DesktopKeyInfo] with encrypted prikey +
 * cached addresses. Wipes [prikey32] before returning.
 *
 * Must run inside an unlocked [WalletSession] — the prikey is encrypted
 * to a [core.crypto.CryptoDataStr]-shaped JSON under the session
 * password (Argon2id, ~500ms per call) before storage.
 *
 * Used by:
 * - `MyKeysScreen.createRandomKey()` (Random menu entry)
 * - [buildKeyFromInputs] for PRIKEY / PHRASE / PRIKEY_CIPHER paths
 * - `KeyImporter` when materialising an imported key record
 */
internal fun buildKeyFromPrikey32(
    prikey32: ByteArray,
    label: String?,
    savedAt: Long = System.currentTimeMillis(),
): DesktopKeyInfo {
    try {
        val pubkeyBytes = KeyTools.prikeyToPubkey(prikey32)
        val pubkeyHex = Hex.toHex(pubkeyBytes)
        val fid = KeyTools.prikeyToFid(prikey32)
        val prikeyCipher = WalletSession.encryptToJson(prikey32)
        val addrs = KeyTools.pubkeyToAddresses(pubkeyHex) ?: emptyMap<String, String>()

        return DesktopKeyInfo().apply {
            setId(fid)
            this.pubkey = pubkeyHex
            this.prikeyCipher = prikeyCipher
            this.watchOnly = false
            this.label = label
            this.savedAt = savedAt
            this.btcAddr = addrs[FcConstants.BTC_ADDR]
            this.ethAddr = addrs[FcConstants.ETH_ADDR]
            this.trxAddr = addrs[FcConstants.TRX_ADDR]
            this.bchAddr = addrs[FcConstants.BCH_ADDR]
            this.dogeAddr = addrs[FcConstants.DOGE_ADDR]
        }
    } finally {
        prikey32.fill(0)
    }
}

/** Watch-only key from a pubkey. FID derived; no prikeyCipher. */
internal fun buildWatchOnlyFromPubkey(
    pubkeyHex: String,
    label: String?,
    savedAt: Long = System.currentTimeMillis(),
): DesktopKeyInfo {
    val fid = KeyTools.pubkeyToFchAddr(pubkeyHex)
    val addrs = KeyTools.pubkeyToAddresses(pubkeyHex) ?: emptyMap<String, String>()
    return DesktopKeyInfo().apply {
        setId(fid)
        this.pubkey = pubkeyHex
        this.prikeyCipher = null
        this.watchOnly = true
        this.label = label
        this.savedAt = savedAt
        this.btcAddr = addrs[FcConstants.BTC_ADDR]
        this.ethAddr = addrs[FcConstants.ETH_ADDR]
        this.trxAddr = addrs[FcConstants.TRX_ADDR]
        this.bchAddr = addrs[FcConstants.BCH_ADDR]
        this.dogeAddr = addrs[FcConstants.DOGE_ADDR]
    }
}

/** Watch-only key from an FID string. No pubkey, no derived addresses. */
internal fun buildWatchOnlyFromFid(
    fid: String,
    label: String?,
    savedAt: Long = System.currentTimeMillis(),
): DesktopKeyInfo =
    DesktopKeyInfo().apply {
        setId(fid)
        this.pubkey = null
        this.prikeyCipher = null
        this.watchOnly = true
        this.label = label
        this.savedAt = savedAt
    }

/**
 * The private key a phrase stands for, as Android Safe derives it. Argon2id runs
 * with an empty salt so the same phrase always gives the same key; SHA-256 is the
 * older scheme, kept only to recreate keys that were made with it.
 */
internal fun phraseToPrikey32(phrase: String, useArgon2id: Boolean): ByteArray {
    if (!useArgon2id) return Hash.sha256(phrase.toByteArray(Charsets.UTF_8))
    val chars = phrase.toCharArray()
    try {
        return Kdf.Argon2id_No1_NrC7.deriveSymkey(chars, ByteArray(0))
    } finally {
        chars.fill(Char.MIN_VALUE)
    }
}

/** What an import input turned out to hold. */
internal sealed interface KeyImportOutcome {
    class One(val key: DesktopKeyInfo) : KeyImportOutcome
    /** Key backup JSON for [com.fc.safe.desktop.backup.KeyImporter], with the password given. */
    class Backup(val json: String, val password: String?) : KeyImportOutcome
}

/**
 * Builds the key a detected import input stands for. Throws
 * [IllegalArgumentException] with a message for the user when it can't.
 * Runs KDFs, so call it off the UI thread.
 */
internal fun resolveKeyImport(
    kind: KeyInputDetector.Kind,
    input: String,
    password: String,
    label: String?,
): KeyImportOutcome {
    val text = input.trim()
    return when (kind) {
        KeyInputDetector.Kind.PRIKEY -> {
            val prikey32 = KeyInputDetector.toPrikey32(text)
                ?: throw IllegalArgumentException("Invalid private key")
            KeyImportOutcome.One(buildKeyFromPrikey32(prikey32, label))
        }
        KeyInputDetector.Kind.PUBKEY -> KeyImportOutcome.One(buildWatchOnlyFromPubkey(text, label))
        KeyInputDetector.Kind.FID -> KeyImportOutcome.One(buildWatchOnlyFromFid(text, label))
        KeyInputDetector.Kind.CIPHER -> {
            if (password.isEmpty()) throw IllegalArgumentException("Password is empty")
            val pwdChars = password.toCharArray()
            val plaintext = try {
                KeyInputDetector.openCipher(text, pwdChars)
            } finally {
                pwdChars.fill(Char.MIN_VALUE)
            } ?: throw IllegalArgumentException("Wrong password or damaged cipher")
            try {
                val prikey32 = KeyInputDetector.prikeyFromPlaintext(plaintext)
                when {
                    prikey32 != null -> KeyImportOutcome.One(buildKeyFromPrikey32(prikey32, label))
                    // An encrypted backup rather than a bare key: its entries
                    // are opened with the same password.
                    KeyInputDetector.isJson(plaintext) ->
                        KeyImportOutcome.Backup(String(plaintext, Charsets.UTF_8), password)
                    else -> throw IllegalArgumentException("The cipher does not hold a private key")
                }
            } finally {
                plaintext.fill(0)
            }
        }
        KeyInputDetector.Kind.BACKUP -> KeyImportOutcome.Backup(text, password.ifEmpty { null })
        else -> throw IllegalArgumentException("Not a key that can be imported")
    }
}
