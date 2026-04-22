package com.fc.safe.desktop

import data.fcData.FcEntity

/**
 * Per-wallet record of a cryptographic key. Minimal shape for the Phase 2
 * first-screen port; intentionally narrower than Safe Android's
 * `com.fc.fc_ajdk.data.fcData.KeyInfo` (which extends `Cid`/`Freer` and
 * carries on-chain bells like history, avatar, balances). We'll grow into
 * that surface as later screens need it.
 *
 * Sensitive fields ([prikey]) sit in the row value which SqliteDB encrypts
 * at rest with the wallet's Argon2-derived symkey. A separate field-level
 * encryption layer (`prikeyCipher` in Safe) is deferred until the
 * import/export flow needs portable encrypted key material.
 *
 * `id` (inherited from FcEntity) is the FID address.
 */
class DesktopKeyInfo : FcEntity() {
    var prikey: String? = null    // hex-encoded 32-byte private key; null for watch-only
    var pubkey: String? = null    // hex-encoded 33-byte compressed public key
    var label: String? = null
    var watchOnly: Boolean = false
    var savedAt: Long = 0
}
