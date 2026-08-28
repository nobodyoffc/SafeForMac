package com.fc.safe.desktop.backup

import com.fc.safe.desktop.DesktopMultisig
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import core.crypto.KeyTools
import data.fchData.P2SH
import utils.Hex
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Consumes a [MultisigExporter] blob (or equivalent Android output)
 * and produces [DesktopMultisig] records. Parse flow mirrors
 * [KeyImporter]:
 *
 * 1. Walk with [BackupCodec.readOneJsonFromInputStream].
 * 2. Classify each JSON as [BackupHeader] / [BackupKey] /
 *    [ExportedMultisig]. Unknown blobs are skipped silently — if
 *    Android later adds encrypted multisig export we'll extend the
 *    classifier rather than break on the new shape.
 * 3. Materialise each [ExportedMultisig] into a [DesktopMultisig].
 *
 * Validation rule: for each imported group we re-derive the redeem
 * script + id from the pubkey list and compare. Mismatch → reject
 * (almost always a tampered/reordered pubkey list). This is cheap
 * (O(n) per group) and protects the user from silently saving a
 * group whose on-chain behaviour differs from what the JSON claims.
 */
internal object MultisigImporter {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val gson = Gson()

    fun importText(text: String): List<DesktopMultisig> =
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)).use {
            importStream(it)
        }

    private fun importStream(input: InputStream): List<DesktopMultisig> {
        val headers = ArrayList<BackupHeader>()
        val backupKeys = ArrayList<BackupKey>()
        val entries = ArrayList<ExportedMultisig>()

        while (true) {
            val jsonBytes = BackupCodec.readOneJsonFromInputStream(input) ?: break
            val json = String(jsonBytes, Charsets.UTF_8).trim()
            if (json.isEmpty()) continue
            classify(json, headers, backupKeys, entries)
        }

        if (entries.isEmpty()) {
            throw IllegalArgumentException("No multisig entries found in input")
        }

        return entries.mapNotNull { toDesktopMultisig(it) }
    }

    private fun classify(
        json: String,
        headers: MutableList<BackupHeader>,
        backupKeys: MutableList<BackupKey>,
        entries: MutableList<ExportedMultisig>,
    ) {
        tryParse<BackupKey>(json)?.let {
            if (it.password != null || it.symkey != null) {
                backupKeys += it
                return
            }
        }
        tryParse<BackupHeader>(json)?.let {
            if (it.items != null && it.time != null && it.tClass != null) {
                headers += it
                return
            }
        }
        tryParse<ExportedMultisig>(json)?.let {
            val hasCore = it.id != null && it.m != null && it.n != null &&
                !it.pubkeys.isNullOrEmpty()
            if (hasCore) entries += it
        }
    }

    private inline fun <reified T> tryParse(json: String): T? = try {
        gson.fromJson(json, T::class.java)
    } catch (_: JsonSyntaxException) {
        null
    } catch (_: Exception) {
        null
    }

    /**
     * Materialise a [DesktopMultisig] from a wire entry. Re-derives
     * `redeemScript` and `fids` from `pubkeys` + m/n so a tampered
     * JSON (e.g. pubkey-list reorder) can't smuggle in a group whose
     * claimed id wouldn't match what the chain computes.
     */
    private fun toDesktopMultisig(entry: ExportedMultisig): DesktopMultisig? {
        val pubkeys = entry.pubkeys.orEmpty()
        val m = entry.m ?: return null
        val n = entry.n ?: return null
        if (pubkeys.size != n) return null
        if (pubkeys.any { !KeyTools.isPubkey(it) }) return null

        val script = P2SH.makeMultisigRedeemScript(pubkeys, m, n)
        val derivedHex = Hex.toHex(script.program)
        val derivedFid = KeyTools.scriptToMultiAddr(derivedHex) ?: return null

        // Reject if the envelope's claimed id doesn't match what the
        // crypto actually produces — a silent mismatch would let an
        // attacker feed the user an unspendable group.
        if (entry.id != null && entry.id != derivedFid) return null
        // Belt-and-braces: if redeemScript was also supplied, it must match.
        if (!entry.redeemScript.isNullOrBlank() &&
            !entry.redeemScript.equals(derivedHex, ignoreCase = true)
        ) return null

        val derivedMemberFids = pubkeys.map { pk ->
            KeyTools.pubkeyToFchAddr(pk) ?: return null
        }

        return DesktopMultisig().apply {
            setId(derivedFid)
            this.m = m
            this.n = n
            this.pubkeys = pubkeys
            this.fids = derivedMemberFids
            this.redeemScript = derivedHex
            this.label = entry.label
            this.savedAt = parseSaveTime(entry.saveTime) ?: System.currentTimeMillis()
        }
    }

    private fun parseSaveTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { dateFmt.parse(s)?.time }.getOrNull()
    }
}
