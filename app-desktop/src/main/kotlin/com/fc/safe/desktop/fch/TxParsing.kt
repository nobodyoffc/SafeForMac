package com.fc.safe.desktop.fch

import core.fch.RawTxInfo

/**
 * Two-format tolerant parser for the offline-signing and preview
 * flows. Mirrors Android's `ImportTxInfoActivity.importButton` dance:
 * try `RawTxInfo.fromJson` first (the canonical shape this app
 * produces) and fall back to `RawTxInfo.fromRawTxForCs` for the
 * CashScript-v1 shape Android occasionally receives from third
 * parties.
 *
 * Returns null on both parse failures — caller shows a friendly
 * "invalid tx JSON" error. Never throws; guards every branch with
 * `runCatching` so malformed payloads don't crash the compose
 * runtime.
 */
internal fun parseRawTxInfo(json: String): RawTxInfo? {
    val trimmed = json.trim()
    if (trimmed.isEmpty()) return null

    runCatching {
        val direct = RawTxInfo.fromJson(trimmed, RawTxInfo::class.java)
        if (direct != null && direct.inputs != null) return direct
    }

    runCatching {
        val csShape = RawTxInfo.fromRawTxForCs(trimmed)
        if (csShape != null) return csShape
    }

    return null
}
