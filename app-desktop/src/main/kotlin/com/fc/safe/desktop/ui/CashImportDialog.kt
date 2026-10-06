package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fc.safe.desktop.backup.BackupCodec
import data.fchData.Cash
import utils.JsonUtils
import java.io.ByteArrayInputStream

/**
 * Paste a Cash JSON or a JSON array of Cash entries. Tolerant —
 * accepts either single-object or array shapes, dispatches based on
 * the first non-whitespace char (`{` vs `[`). Each parsed Cash is
 * checked for required fields (`birthTxId` + `birthIndex`); rows
 * that fail validation are quietly dropped and counted in the error
 * surface.
 *
 * Mirrors Android's `ImportCashActivity` paste path. File picker is
 * deferred to the same future task as Secrets/Keys file pickers.
 */
@Composable
fun CashImportDialog(
    onDone: (List<Cash>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            error = "Paste a Cash JSON or array first"
            return
        }
        val parsed = parseCashes(trimmed)
        if (parsed.isEmpty()) {
            error = "No valid Cash records found"
            return
        }
        error = null
        onDone(parsed)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.requiredWidth(560.dp).height(420.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Import cash", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Paste a Cash JSON or an array of Cash records.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("Cash JSON") },
                    textStyle = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    trailingIcon = {
                        QrScanTrailingIcon(
                            onDecoded = { text = it.trim(); error = null },
                            onError = { error = it },
                            tooltip = "Scan a cash QR code",
                        )
                    },
                )

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = ::submit) { Text("Import") }
                }
            }
        }
    }
}

/**
 * Parse paste content into a list of Cash. Pre-flight check on the
 * first non-whitespace char picks the array vs object branch. Any
 * record missing `birthTxId`/`birthIndex` is skipped — those are
 * the canonical-id ingredients and a record without them couldn't
 * be looked up later.
 */
private fun parseCashes(json: String): List<Cash> {
    val first = json.firstOrNull { !it.isWhitespace() } ?: return emptyList()
    val raw: List<Cash> = when (first) {
        // Standard JSON array shape — `[ {...}, {...} ]`.
        '[' -> runCatching { JsonUtils.listFromJson(json, Cash::class.java) }
            .getOrNull() ?: emptyList()
        // Object shape. Could be one Cash, OR Android's
        // multi-object-with-no-array form `{...}{...}{...}` (the
        // same wire format we already parse for keys via
        // BackupCodec). Walk balanced braces and parse each chunk
        // individually — this handles 1 and N equally well.
        '{' -> {
            val out = ArrayList<Cash>()
            ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)).use { input ->
                while (true) {
                    val bytes = BackupCodec.readOneJsonFromInputStream(input) ?: break
                    val chunk = String(bytes, Charsets.UTF_8).trim()
                    if (chunk.isEmpty()) continue
                    runCatching { JsonUtils.fromJson(chunk, Cash::class.java) }
                        .getOrNull()?.let(out::add)
                }
            }
            out
        }
        else -> emptyList()
    }
    return raw.filter {
        !it.birthTxId.isNullOrBlank() && it.birthIndex != null && it.birthIndex >= 0
    }.onEach { c ->
        // Force-set id from canonical (birthTxId, birthIndex) so
        // SqliteDB has a primary key — matches the same call
        // CashManager.addCash makes on insert in Android.
        if (c.id.isNullOrBlank()) c.makeId(c.birthTxId, c.birthIndex)
    }
}
