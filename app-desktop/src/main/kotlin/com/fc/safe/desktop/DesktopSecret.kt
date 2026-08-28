package com.fc.safe.desktop

import core.crypto.Hash
import data.fcData.FcEntity
import utils.Hex

/**
 * Local-only secret record. FC-JDK's `data.feipData.Secret` is built
 * for the on-chain Feip flow (owner / birthHeight / active / Cipher
 * server-side field), which the macOS wallet doesn't use. This lean
 * subclass keeps just what the vault needs and matches the storage
 * contract already used by [DesktopKeyInfo]:
 *
 * - `id` (inherited) is `sha256x2(title||content)` hex, stable across
 *   edits that keep title+content identical — parity with Android's
 *   `Secret.checkIdWithCreate`.
 * - [contentCipher] is a [core.crypto.CryptoDataStr]-shaped JSON
 *   produced by `WalletSession.encryptToJson`. Decrypt via the
 *   session's `decryptFromJson`. At rest the SqliteDB value-layer
 *   wraps this whole row again in AES-GCM under the wallet's
 *   Argon2-derived symkey, so contentCipher is effectively
 *   double-encrypted.
 * - [type] is a short tag (`password`, `text`, `TOTP`, etc.); the
 *   TOTP screen consumes this string to pick out codes.
 * - [savedAt] is epoch ms, sorted DESC for the list by default.
 */
class DesktopSecret : FcEntity() {
    var title: String? = null
    var type: String? = null
    var memo: String? = null
    var contentCipher: String? = null     // CryptoDataStr JSON; null only during a transient "in-progress" state
    var savedAt: Long = 0

    /**
     * Compute and set `id` from the secret's title+content. Called
     * when constructing a brand-new secret. Keeping this deterministic
     * means duplicate creates on the same (title, content) pair land
     * on the same row — the UI then asks the user whether to replace.
     */
    fun assignId(title: String, content: String) {
        val bytes = (title + content).toByteArray(Charsets.UTF_8)
        setId(Hex.toHex(Hash.sha256x2(bytes)))
    }
}

/**
 * Canonical type tags used for the secret "kind" column. Mirrors
 * Android's `Secret.Type` enum so export/import round-trips work.
 */
enum class DesktopSecretType(val tag: String) {
    PASSWORD("password"),
    TEXT("text"),
    PRIKEY("prikey"),
    SYMKEY("symkey"),
    TOTP("TOTP"),
    SECRET("secret"),
    ;

    companion object {
        /** Loose match — accepts tag, enum name, or any case. */
        fun fromAny(s: String?): DesktopSecretType? {
            if (s.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.tag.equals(s, ignoreCase = true) || it.name.equals(s, ignoreCase = true)
            }
        }
    }
}
