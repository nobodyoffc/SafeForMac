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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import core.crypto.KeyTools
import data.fchData.P2SH
import utils.Hex

/**
 * Collected inputs from the create-multisig dialog. The caller is
 * responsible for deriving the redeem script + FID — the dialog does
 * the preview for user feedback but the source of truth for
 * persistence is the caller's own derivation.
 */
data class CreateMultisigInputs(
    val m: Int,
    val n: Int,
    val pubkeys: List<String>,
    val label: String?,
)

/**
 * Collects M-of-N threshold + pubkey list for a new multisig group.
 *
 * Member pubkeys can be typed/pasted directly or picked from the
 * wallet's own keys (via [KeyPickerDialog] with
 * [KeyPickerFilter.WithPubkey]). The dialog shows a live preview of
 * the derived redeem script hex + multisig FID so the user can
 * sanity-check the result before committing.
 *
 * Bounds: 1 ≤ m ≤ n ≤ 15. The ceiling is BitcoinJ's
 * `OP_CHECKMULTISIG` limit — pushing past it produces scripts most
 * nodes reject.
 */
@Composable
fun CreateMultisigDialog(
    busy: Boolean,
    onSubmit: (CreateMultisigInputs) -> Unit,
    onDismiss: () -> Unit,
) {
    var n by remember { mutableStateOf(3) }
    var m by remember { mutableStateOf(2) }
    var label by remember { mutableStateOf("") }
    var pubkeys by remember { mutableStateOf(List(3) { "" }) }
    var showPickerFor by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun setN(newN: Int) {
        val clamped = newN.coerceIn(1, 15)
        n = clamped
        if (m > clamped) m = clamped
        pubkeys = if (pubkeys.size >= clamped) pubkeys.take(clamped)
        else pubkeys + List(clamped - pubkeys.size) { "" }
        error = null
    }

    fun setM(newM: Int) {
        m = newM.coerceIn(1, n)
        error = null
    }

    fun attempt() {
        val trimmed = pubkeys.map { it.trim() }
        val bad = trimmed.withIndex().firstOrNull { (_, pk) ->
            pk.isBlank() || !KeyTools.isPubkey(pk)
        }
        if (bad != null) {
            error = "Member ${bad.index + 1} is not a valid public key"
            return
        }
        if (trimmed.toSet().size != trimmed.size) {
            error = "Duplicate pubkey — each member must be unique"
            return
        }
        onSubmit(CreateMultisigInputs(
            m = m,
            n = n,
            pubkeys = trimmed,
            label = label.trim().ifBlank { null },
        ))
    }

    // Live preview of redeem script + FID. Any parse failure is
    // silent — the preview is only useful once all N slots are valid.
    val preview = remember(m, n, pubkeys) {
        runCatching {
            val trimmed = pubkeys.map { it.trim() }
            if (trimmed.any { it.isBlank() || !KeyTools.isPubkey(it) }) null
            else {
                val script = P2SH.makeMultisigRedeemScript(trimmed, m, n)
                val hex = Hex.toHex(script.program)
                val fid = KeyTools.scriptToMultiAddr(hex)
                hex to fid
            }
        }.getOrNull()
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
            modifier = Modifier.requiredWidth(600.dp).height(640.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Create multisig group", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))

                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NumberStepper(label = "Required (M)", value = m, onInc = { setM(m + 1) }, onDec = { setM(m - 1) })
                        Spacer(Modifier.width(24.dp))
                        NumberStepper(label = "Members (N)", value = n, onInc = { setN(n + 1) }, onDec = { setN(n - 1) })
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Any $m of $n members must sign to spend.",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Spacer(Modifier.height(12.dp))
                    Text("Member public keys", style = MaterialTheme.typography.subtitle2)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Order affects the derived FID — keep the same order when re-creating a group.",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))

                    for (i in 0 until n) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = pubkeys.getOrElse(i) { "" },
                                onValueChange = { v ->
                                    pubkeys = pubkeys.mapIndexed { idx, old -> if (idx == i) v else old }
                                    error = null
                                },
                                label = { Text("#${i + 1}") },
                                singleLine = true,
                                enabled = !busy,
                                modifier = Modifier.weight(1f),
                            )
                            QrScanIconButton(
                                onDecoded = { scanned ->
                                    pubkeys = pubkeys.mapIndexed { idx, old ->
                                        if (idx == i) scanned.trim() else old
                                    }
                                    error = null
                                },
                                onError = { error = it },
                                enabled = !busy,
                                tooltip = "Scan member #${i + 1}'s public key",
                            )
                            IconButton(
                                onClick = { showPickerFor = i },
                                enabled = !busy,
                            ) {
                                Icon(Icons.Filled.Person, contentDescription = "Pick from keys")
                            }
                            IconButton(
                                onClick = {
                                    pubkeys = pubkeys.mapIndexed { idx, old -> if (idx == i) "" else old }
                                    error = null
                                },
                                enabled = !busy,
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Clear")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    Text("Preview", style = MaterialTheme.typography.subtitle2)
                    Spacer(Modifier.height(4.dp))
                    if (preview != null) {
                        val (hex, fid) = preview
                        Text(
                            "FID",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            fid,
                            style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Redeem script",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        )
                        Text(
                            hex,
                            style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                        )
                    } else {
                        Text(
                            "Fill in all $n valid pubkeys to see the derived FID.",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        )
                    }

                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    SafeButton(
                        onClick = ::attempt,
                        enabled = !busy && preview != null,
                    ) { Text("Create") }
                }
            }
        }
    }

    showPickerFor?.let { slot ->
        KeyPickerDialog(
            filter = KeyPickerFilter.WithPubkey,
            onPicked = { picked ->
                val pk = picked.pubkey
                if (!pk.isNullOrBlank()) {
                    pubkeys = pubkeys.mapIndexed { idx, old -> if (idx == slot) pk else old }
                    error = null
                }
                showPickerFor = null
            },
            onDismiss = { showPickerFor = null },
        )
    }
}

@Composable
private fun NumberStepper(
    label: String,
    value: Int,
    onInc: () -> Unit,
    onDec: () -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.caption)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDec) { Text("−", style = MaterialTheme.typography.h6) }
            Box(
                modifier = Modifier.width(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    value.toString(),
                    style = MaterialTheme.typography.subtitle1,
                )
            }
            IconButton(onClick = onInc) { Text("+", style = MaterialTheme.typography.h6) }
        }
    }
}
