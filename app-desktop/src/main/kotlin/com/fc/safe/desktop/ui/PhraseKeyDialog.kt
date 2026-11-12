package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Makes a key from a phrase. As on Android Safe, the phrase is stretched with
 * Argon2id unless the user asks for plain SHA-256, which is kept only to
 * recreate a key that was made that way. This is Safe's own scheme, not BIP39.
 */
@Composable
fun PhraseKeyDialog(
    busy: Boolean,
    error: String?,
    onSubmit: (phrase: String, useArgon2id: Boolean, label: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var useArgon2id by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Input phrase") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ScanTextField(
                    value = phrase,
                    onValueChange = { phrase = it },
                    label = "Phrase",
                    placeholder = "The same phrase always makes the same key",
                    singleLine = false,
                    maxLines = 6,
                    scanTooltip = "Scan phrase",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Text("Key derivation", style = MaterialTheme.typography.subtitle2)
                KdfChoice("Argon2id (recommended)", selected = useArgon2id, enabled = !busy) { useArgon2id = true }
                KdfChoice("SHA256", selected = !useArgon2id, enabled = !busy) { useArgon2id = false }
                Text(
                    if (useArgon2id)
                        "Argon2id is slow and memory-hard, making it much harder to brute-force the phrase. " +
                            "Keys made from a phrase by Safe for macOS 1.1.1 or earlier used SHA256."
                    else
                        "Plain SHA256 gives no protection against brute-force or dictionary attacks: a " +
                            "low-entropy phrase can be recovered easily. Use it only to recreate a key " +
                            "that was made with SHA256.",
                    color = if (useArgon2id) MaterialTheme.colors.onSurface else MaterialTheme.colors.error,
                    style = MaterialTheme.typography.caption,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && phrase.isNotBlank(),
                onClick = { onSubmit(phrase.trim(), useArgon2id, label.trim().ifEmpty { null }) },
            ) { Text(if (busy) "Working…" else "Save") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
        modifier = Modifier.padding(8.dp),
    )
}

@Composable
private fun KdfChoice(text: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(text, style = MaterialTheme.typography.body2)
    }
}
