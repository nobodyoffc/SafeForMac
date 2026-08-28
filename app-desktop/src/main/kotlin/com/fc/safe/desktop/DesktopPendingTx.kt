package com.fc.safe.desktop

import data.fcData.FcEntity

/**
 * Offline-signed transaction that the user chose to track locally for
 * later reconciliation with the cash DB. Because the wallet is
 * air-gapped, only the user can tell whether a signed TX was actually
 * broadcast and confirmed — so we keep a staging list that lets them
 * Apply (commit the UTXO diff), Discard (abandon), or Keep pending.
 *
 * - `id` (inherited) is the parsed txid of the signed hex — stable
 *   across saves of the same tx, so re-saving overwrites rather than
 *   duplicating.
 * - `rawJson` is the `RawTxInfo` JSON captured at save time. It's the
 *   source of truth for which UTXOs are spent (`inputs[*].birthTxId +
 *   birthIndex`) because the signed hex alone doesn't carry the
 *   outpoint-to-Cash-id mapping.
 * - `signedHex` is the broadcast-ready hex. On Apply we re-parse it
 *   via `TxHandler.getIssuedCashListForFid` to materialize born UTXOs
 *   for every owned FID (keys + multisigs).
 * - `flavor` = "SINGLE" or "MULTISIG" — display-only hint so the list
 *   can show where the record came from.
 * - `senderFid`, `inputCount`, `outputCount`, `totalOut` are cached
 *   summary fields so the list row can render without re-parsing the
 *   JSON on every paint.
 */
class DesktopPendingTx : FcEntity() {
    var createdAt: Long = 0
    var flavor: String? = null
    var rawJson: String? = null
    var signedHex: String? = null
    var senderFid: String? = null
    var inputCount: Int = 0
    var outputCount: Int = 0
    var totalOut: Long = 0
}
