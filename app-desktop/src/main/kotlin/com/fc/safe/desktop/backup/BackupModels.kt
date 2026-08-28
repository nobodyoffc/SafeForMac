package com.fc.safe.desktop.backup

/**
 * Mirrors `com.fc.safe.models.BackupHeader`. Field names match 1:1 so
 * Android and macOS can parse each other's exports via Gson.
 *
 * Android's `BackupHeader` has a `tClass` field accessed via
 * `gettClass` / `settClass`; Gson serialises it as `"tClass"`, so we
 * keep the property name as-is. `time` is a formatted string
 * ("yyyy-MM-dd HH:mm:ss") per `BackupHeader.setTime(long)`.
 */
internal class BackupHeader {
    var time: String? = null
    var items: Int? = null
    var qrCodes: Int? = null
    var keyName: String? = null
    var alg: String? = null
    var tClass: String? = null
}

/**
 * Mirrors `com.fc.safe.models.BackupKey`. Carries the per-export
 * random password (or the symkey string) so the receiver can decrypt
 * the subsequent per-key `prikeyCipher` blobs. Gson field order
 * matches Android.
 */
internal class BackupKey {
    var password: String? = null
    var symkey: String? = null
    var time: String? = null
    var keyName: String? = null
    var hint: String? = null
}

/**
 * On-the-wire shape of a single key entry. Matches Android
 * `com.fc.fc_ajdk.data.fcData.KeyInfo` for the fields Safe actually
 * populates on export. Unused Cid-inherited fields are omitted — Gson
 * reading a superset JSON will silently drop unknown fields.
 *
 * Field semantics:
 * - [id] — FID (FCH address). Always present on export.
 * - [pubkey] — hex, 33-byte compressed, or 130-char uncompressed.
 * - [prikey] — hex, set only for unencrypted exports. Post-import is
 *   also populated by the decrypt step before we derive a
 *   [com.fc.safe.desktop.DesktopKeyInfo].
 * - [prikeyCipher] — base64 of `CryptoDataByte.toBundle()` (the
 *   compact one-line encoding — NOT the JSON `CryptoDataStr` form
 *   that FC-JDK stores internally).
 * - [saveTime] — formatted "yyyy-MM-dd HH:mm:ss" string, NOT epoch ms.
 * - Address fields are cached derivations; safe to omit on import.
 */
internal class ExportedKeyInfo {
    var id: String? = null
    var pubkey: String? = null
    var prikey: String? = null
    var prikeyCipher: String? = null
    var label: String? = null
    var watchOnly: Boolean? = null
    var saveTime: String? = null
    var btcAddr: String? = null
    var ethAddr: String? = null
    var bchAddr: String? = null
    var ltcAddr: String? = null
    var dogeAddr: String? = null
    var trxAddr: String? = null
}

/**
 * On-the-wire shape of a single multisig group entry. Matches
 * FC-JDK's `data.fchData.Multisig` field names (lowercase `pubkeys`
 * per that class's getters/setters) so a Gson parse of the raw JSON
 * also works with Android's `Multisig.fromJson` downstream.
 *
 * Multisig groups carry no secret material — `redeemScript` and
 * member `pubkeys` are already published when the group's FID
 * appears on-chain — so export is plain JSON. `label` and
 * `saveTime` are desktop-only conveniences; Gson on the Android
 * side silently drops unknown fields so round-tripping is safe.
 */
internal class ExportedMultisig {
    var id: String? = null
    var m: Int? = null
    var n: Int? = null
    var pubkeys: List<String>? = null
    var fids: List<String>? = null
    var redeemScript: String? = null
    var label: String? = null
    var saveTime: String? = null
}
