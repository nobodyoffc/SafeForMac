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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import constants.Constants
import data.fchData.Cash
import utils.Hex

/**
 * Constructs a [Cash] input from three user-typed fields. Ported
 * from Android `AddTxInputDialog` — no QR scan (desktop flow pastes)
 * and no "My Cash" picker (requires the Cash DB feature, deferred).
 *
 * Validation mirrors Android:
 * - `txId` must be 32-byte hex (64 chars)
 * - `index` must be a non-negative int
 * - `amount` must be in [Constants.MIN_AMOUNT, Constants.MAX_AMOUNT]
 */
@Composable
fun AddTxInputDialog(
    onDone: (Cash) -> Unit,
    onDismiss: () -> Unit,
) {
    var txId by remember { mutableStateOf("") }
    var indexStr by remember { mutableStateOf("") }
    var amountStr by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        error = null
        val idx = indexStr.toIntOrNull()
        val amount = amountStr.toDoubleOrNull()
        when {
            txId.isBlank() || indexStr.isBlank() || amountStr.isBlank() ->
                error = "Fill all three fields"
            !Hex.isHex32(txId) ->
                error = "txId must be 64 hex chars (32 bytes)"
            idx == null || idx < 0 ->
                error = "Index must be a non-negative integer"
            amount == null || amount < Constants.MIN_AMOUNT || amount > Constants.MAX_AMOUNT ->
                error = "Amount must be between ${Constants.MIN_AMOUNT} and ${Constants.MAX_AMOUNT} FCH"
            else -> {
                onDone(Cash(txId, idx, amount))
            }
        }
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
            modifier = Modifier.requiredWidth(520.dp).height(340.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Add tx input", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = txId,
                    onValueChange = { txId = it; error = null },
                    label = { Text("Tx ID (64 hex)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = indexStr,
                    onValueChange = { indexStr = it.filter { c -> c.isDigit() }; error = null },
                    label = { Text("Output index") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it; error = null },
                    label = { Text("Amount (FCH)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = ::submit) { Text("Add") }
                }
            }
        }
    }
}
