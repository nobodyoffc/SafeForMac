package com.fc.safe.desktop

import com.fc.safe.desktop.fch.TxHandler
import com.fc.safe.platform.macos.WalletSession
import core.fch.RawTxInfo
import db.LocalDB

internal const val PENDING_TXS_DB_NAME = "pendingTxs"

enum class PendingFlavor { SINGLE, MULTISIG }

/**
 * Persist a broadcast-ready signed tx as a [DesktopPendingTx] so the
 * user can later reconcile the local cash DB via PendingTxsScreen.
 * Called from the two sign-screen exit points whose output is a
 * broadcast-ready hex: `SignTxScreen` (single-sig) and
 * `BuildMultisigTxScreen` (multisig aggregation).
 *
 * The envelope captures both halves of the information we need at
 * Apply time:
 * - [raw] JSON preserves the pre-sign inputs, which carry the
 *   `birthTxId + birthIndex` pairs that identify spent local UTXOs.
 *   The signed hex alone doesn't carry outpoint-to-Cash-id mapping
 *   in a form we can reuse cleanly.
 * - [signedHex] is the broadcast blob. We reparse it here to pull
 *   the canonical txid (record key — so re-saving the same tx
 *   overwrites) and to compute an accurate output summary that
 *   includes any auto-generated change output.
 *
 * Caller must invoke on an IO dispatcher — `db.commit()` blocks.
 */
internal fun savePendingTx(
    raw: RawTxInfo,
    signedHex: String,
    flavor: PendingFlavor,
): String {
    val parsed = TxHandler().parseTx(signedHex)
        ?: throw IllegalStateException("Could not parse signed hex to compute txid")
    val txid = parsed.id
        ?: throw IllegalStateException("Parsed tx has no id")

    val rec = DesktopPendingTx().apply {
        id = txid
        createdAt = System.currentTimeMillis()
        this.flavor = flavor.name
        this.rawJson = raw.toJson()
        this.signedHex = signedHex
        this.senderFid = raw.sender ?: raw.senderMultisig?.id
        this.inputCount = parsed.inputs?.size ?: 0
        this.outputCount = parsed.outputs?.size ?: 0
        this.totalOut = parsed.outputs?.sumOf { it.value ?: 0L } ?: 0L
    }

    val db: LocalDB<DesktopPendingTx> = WalletSession.openDb(
        PENDING_TXS_DB_NAME, DesktopPendingTx::class.java
    )
    db.put(txid, rec)
    db.commit()
    return txid
}
