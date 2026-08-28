package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fc.safe.desktop.fch.TxHandler
import core.crypto.KeyTools
import data.fchData.Cash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Parse a signed tx hex and extract its outputs that belong to a
 * chosen FID (sender's change, or a recipient we also own). Closes
 * the multisig-flow loop — after Build + broadcast, users can
 * immediately register the resulting UTXOs without manual
 * bookkeeping.
 *
 * Relies on [TxHandler.getIssuedCashListForFid] which walks the
 * deserialized tx, matches each output's scriptPubKey-derived
 * address against the filter FID, and returns pre-populated [Cash]
 * records (`birthTxId`, `birthIndex`, `owner`, `value`, `id`).
 * Non-standard outputs (OP_RETURN, unreadable scripts) are skipped
 * silently.
 *
 * FID picker offers three sources so the user doesn't have to
 * retype: their own signing keys, their saved multisig groups, or
 * the address book — matching the three FID-owning DBs in the app.
 */
@Composable
fun ImportCashFromTxDialog(
    initialTxHex: String = "",
    onDone: (List<Cash>) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var txHex by remember { mutableStateOf(initialTxHex) }
    var fid by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var showKeyPicker by remember { mutableStateOf(false) }
    var showMultisigPicker by remember { mutableStateOf(false) }
    var showFidBookPicker by remember { mutableStateOf(false) }
    var showPickerMenu by remember { mutableStateOf(false) }

    fun submit() {
        error = null
        val hex = txHex.trim()
        if (hex.isEmpty()) { error = "Paste a signed tx hex first"; return }
        if (fid.isBlank()) { error = "Pick an owner FID to filter outputs"; return }
        if (!KeyTools.isGoodFid(fid)) { error = "Owner is not a valid FID"; return }
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                runCatching { TxHandler.getIssuedCashListForFid(hex, fid) }
            }
            busy = false
            outcome.onSuccess { list ->
                if (list.isEmpty()) {
                    error = "No outputs for $fid in this tx"
                } else {
                    onDone(list)
                }
            }
            outcome.onFailure { error = "Parse failed: ${it.message}" }
        }
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.requiredWidth(560.dp).height(460.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Import UTXOs from signed tx", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Paste the signed tx hex (e.g. the output of Build multisig TX) " +
                        "and pick the owner FID — only outputs paying that FID are imported.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = txHex,
                    onValueChange = { txHex = it; error = null },
                    label = { Text("Signed tx hex") },
                    textStyle = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = fid,
                        onValueChange = { fid = it; error = null },
                        label = { Text("Owner FID") },
                        singleLine = true,
                        enabled = !busy,
                        textStyle = MaterialTheme.typography.body2.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(
                            enabled = !busy,
                            onClick = { showPickerMenu = true },
                        ) {
                            Icon(Icons.Filled.Person, contentDescription = "Pick FID")
                        }
                        DropdownMenu(
                            expanded = showPickerMenu,
                            onDismissRequest = { showPickerMenu = false },
                        ) {
                            DropdownMenuItem(onClick = {
                                showPickerMenu = false
                                showKeyPicker = true
                            }) { Text("From My Keys") }
                            DropdownMenuItem(onClick = {
                                showPickerMenu = false
                                showMultisigPicker = true
                            }) { Text("From My Multisigs") }
                            DropdownMenuItem(onClick = {
                                showPickerMenu = false
                                showFidBookPicker = true
                            }) { Text("From Address Book") }
                        }
                    }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(18.dp).padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = !busy && txHex.isNotBlank() && fid.isNotBlank(),
                        onClick = ::submit,
                    ) { Text("Import") }
                }
            }
        }
    }

    if (showKeyPicker) {
        KeyPickerDialog(
            filter = KeyPickerFilter.WithPubkey,
            onPicked = { fid = it.id; showKeyPicker = false; error = null },
            onDismiss = { showKeyPicker = false },
        )
    }
    if (showMultisigPicker) {
        MultisigPickerDialog(
            onPicked = { fid = it.id; showMultisigPicker = false; error = null },
            onDismiss = { showMultisigPicker = false },
        )
    }
    if (showFidBookPicker) {
        FidPickerDialog(
            onPicked = { fid = it; showFidBookPicker = false; error = null },
            onDismiss = { showFidBookPicker = false },
        )
    }
}
