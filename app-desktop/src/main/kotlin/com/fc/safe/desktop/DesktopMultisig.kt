package com.fc.safe.desktop

import data.fcData.FcEntity

/**
 * A saved M-of-N multisig group. Mirrors `data.fchData.Multisig` from
 * FC-JDK — same redeem script + FID shape — but extends [FcEntity] so
 * it slots into our SqliteDB cleanly and adds the desktop-only
 * `savedAt` / `label` fields that FC-JDK's `Multisig` lacks.
 *
 * - `id` (inherited) is the multisig FID, derived via
 *   `KeyTools.scriptToMultiAddr(redeemScript)`. Two groups with the
 *   same M, N, and ordered pubkey list therefore collapse on insert.
 * - `redeemScript` is the hex-encoded `OP_m <pubkey1> … <pubkeyN> OP_n
 *   OP_CHECKMULTISIG` script produced by
 *   `data.fchData.P2SH.makeMultisigRedeemScript`.
 * - `pubkeys` are the 33-byte compressed hex pubkeys, in the order
 *   they were baked into the redeem script. Order matters — changing
 *   it produces a different address.
 * - `fids` are the member FIDs (each `KeyTools.pubkeyToFchAddr`).
 *   Cached so the list UI can render member lines without re-deriving
 *   on every paint.
 *
 * Not an FC-JDK backport candidate: these fields are UI sugar, not
 * consensus-relevant data. The *crypto* fields (m, n, redeemScript,
 * pubkeys, fids) match FC-JDK exactly, so a row round-trips through
 * `Multisig.parseMultisigRedeemScript(redeemScript)` losslessly.
 */
class DesktopMultisig : FcEntity() {
    var m: Int = 0
    var n: Int = 0
    var redeemScript: String? = null
    var pubkeys: List<String> = emptyList()
    var fids: List<String> = emptyList()
    var label: String? = null
    var savedAt: Long = 0
}
