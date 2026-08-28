package com.fc.safe.desktop.backup

import com.fc.safe.desktop.DesktopMultisig
import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Produces a concatenated-JSON backup of one or more multisig
 * groups. Shape:
 *
 * ```
 * {BackupHeader tClass:"Multisign" items:N}
 * {ExportedMultisig #1}
 * {ExportedMultisig #2}
 * …
 * ```
 *
 * No encryption mode — multisig groups are public by construction
 * (the redeem script and member pubkeys get published on-chain on
 * first spend), so wrapping them in a password is security
 * theatre. If a future Android release adds encrypted multisig
 * export, [MultisigImporter] is structured to tolerate both shapes.
 *
 * Mirrors [KeyExporter]'s concatenated-JSON layout and reuses
 * [BackupCodec.makeJsonListString] + [BackupHeader] so the parser
 * can walk the stream uniformly across Keys / Secrets / Multisigs.
 */
internal object MultisigExporter {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    fun export(groups: List<DesktopMultisig>): ExportResult {
        if (groups.isEmpty()) {
            throw IllegalStateException("No multisig groups to export")
        }

        val now = System.currentTimeMillis()
        val timeStr = dateFmt.format(Date(now))
        val header = BackupHeader().apply {
            time = timeStr
            items = groups.size
            tClass = "Multisign"
        }

        val jsonList = ArrayList<String>()
        jsonList += gson.toJson(header)
        for (group in groups) {
            jsonList += gson.toJson(exportedFromDesktop(group))
        }

        return ExportResult(BackupCodec.makeJsonListString(jsonList), randomPassword = null)
    }

    private fun exportedFromDesktop(group: DesktopMultisig): ExportedMultisig =
        ExportedMultisig().apply {
            id = group.id
            m = group.m
            n = group.n
            pubkeys = group.pubkeys
            fids = group.fids
            redeemScript = group.redeemScript
            label = group.label
            saveTime = if (group.savedAt > 0) dateFmt.format(Date(group.savedAt)) else null
        }
}
