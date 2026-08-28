package com.fc.safe.desktop

import com.fc.safe.platform.macos.WalletSession
import constants.Constants as FcConstants
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
