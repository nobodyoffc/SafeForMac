package com.fc.safe.desktop

import data.fcData.FcEntity

/**
 * Per-wallet record of a cryptographic key.
 *
 * - `id` (inherited) is the FID — this is also the card's title.
 * - `pubkey` is the 33-byte compressed public key as hex.
 * - `prikeyCipher` is a [core.crypto.CryptoDataStr]-shaped JSON string.
 *   Its `data` field is null; its cipher/iv/sum/alg/kdf fields are
 *   populated by [core.crypto.Encryptor.encryptByPassword] under the
 *   wallet's password. Decrypt via
 *   [com.fc.safe.platform.macos.WalletSession.decryptFromJson].
 * - `label` is user-editable.
 * - `btcAddr`/`ethAddr`/`trxAddr`/`bchAddr`/`dogeAddr` are cached at key
 *   creation time via [core.crypto.KeyTools.pubkeyToAddresses] so the
 *   card can render them without re-deriving on every paint.
 *
 * The whole row is additionally encrypted at rest by SqliteDB's
 * value-layer AES-GCM, so prikeyCipher is encrypted twice: once with
 * the wallet password (portable, exportable) and once with the DB's
 * Argon2-derived symkey (at-rest protection, specific to this machine).
 */
class DesktopKeyInfo : FcEntity() {
    var pubkey: String? = null           // 33-byte compressed, hex
    var prikeyCipher: String? = null     // CryptoDataStr JSON; null for watch-only
    var label: String? = null
    var watchOnly: Boolean = false
    var savedAt: Long = 0
    var btcAddr: String? = null
    var ethAddr: String? = null
    var trxAddr: String? = null
    var bchAddr: String? = null
    var dogeAddr: String? = null
}
