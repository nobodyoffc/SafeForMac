package com.fc.safe.desktop.backup

/**
 * On-the-wire shape of a single secret entry. Mirrors the subset
 * of Android `com.fc.fc_ajdk.data.feipData.Secret` fields that
 * Safe actually populates on export.
 *
 * Field semantics — critical for correct round-trip:
 * - [id] — hex(sha256x2(title ++ content)) per `Secret.checkIdWithCreate`.
 * - [title] / [type] / [memo] — user-visible metadata.
 * - [saveTime] — always nulled on export (Android does the same
 *   via `secret.setSaveTime((String) null)` before serializing) so
 *   per-wallet timestamps don't leak to the receiver.
 * - [content] — plaintext content. Set only for the "don't encrypt"
 *   export mode and on the import side after decryption.
 * - [contentCipher] — always `null` on the wire. Our wallet stores
 *   a CryptoDataStr JSON in this field locally, but that's wrapped
 *   under the EXPORTING wallet's password-derived key and can't
 *   be decrypted by the receiver — so we strip it and send
 *   [content] plaintext instead, which the receiver re-encrypts
 *   under its own WalletSession at save time.
 */
internal class ExportedSecret {
    var id: String? = null
    var title: String? = null
    var type: String? = null
    var memo: String? = null
    var content: String? = null
    var contentCipher: String? = null
    var saveTime: String? = null
}
