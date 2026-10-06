package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * What the user is adding. Parameterises [AddKeyDialog]'s fields and the
 * call site's result handling — keeps the dialog itself data-driven so
 * we don't end up with five near-identical dialogs.
 */
enum class AddKeyMode(
    val title: String,
    val primaryLabel: String,
    val primaryHint: String,
    val primaryMultiline: Boolean,
    val primaryMonospace: Boolean,
    val requiresExternalPassword: Boolean,
) {
    PRIKEY(
        title = "Add key by private key",
        primaryLabel = "Private key",
        primaryHint = "64-char hex (or WIF)",
        primaryMultiline = false,
        primaryMonospace = true,
        requiresExternalPassword = false,
    ),
    PHRASE(
        title = "Add key by mnemonic phrase",
        primaryLabel = "Phrase",
        primaryHint = "Seed phrase — any whitespace pattern",
        primaryMultiline = true,
        primaryMonospace = false,
        requiresExternalPassword = false,
    ),
    PRIKEY_CIPHER(
        title = "Add key by prikey cipher JSON",
        primaryLabel = "Prikey cipher JSON",
        primaryHint = "CryptoDataStr JSON produced by another wallet",
        primaryMultiline = true,
        primaryMonospace = true,
        requiresExternalPassword = true,
    ),
    PUBKEY(
        title = "Add watch-only key by public key",
        primaryLabel = "Public key",
        primaryHint = "33-byte compressed (66 hex) or 65-byte (130 hex)",
        primaryMultiline = false,
        primaryMonospace = true,
        requiresExternalPassword = false,
    ),
    FID(
        title = "Add watch-only key by FID",
        primaryLabel = "FID",
        primaryHint = "Base58 FID (FCH address)",
        primaryMultiline = false,
        primaryMonospace = true,
        requiresExternalPassword = false,
    ),
}

/** All fields the dialog can collect. `password` is only populated when [AddKeyMode.requiresExternalPassword]. */
data class AddKeyInputs(
    val primary: String,
    val password: String,
    val label: String?,
)

@Composable
fun AddKeyDialog(
    mode: AddKeyMode,
    busy: Boolean,
    onSubmit: (AddKeyInputs) -> Unit,
    onDismiss: () -> Unit,
) {
    var primary by remember(mode) { mutableStateOf("") }
    var password by remember(mode) { mutableStateOf("") }
    var label by remember(mode) { mutableStateOf("") }

    val primaryOk = primary.isNotBlank()
    val passwordOk = !mode.requiresExternalPassword || password.isNotEmpty()
    val canSubmit = !busy && primaryOk && passwordOk

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(mode.title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ScanTextField(
                    value = primary,
                    onValueChange = { primary = it },
                    label = mode.primaryLabel,
                    placeholder = mode.primaryHint,
                    singleLine = !mode.primaryMultiline,
                    maxLines = if (mode.primaryMultiline) 6 else 1,
                    monospace = mode.primaryMonospace,
                    scanTooltip = "Scan ${mode.primaryLabel.lowercase()}",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (mode.requiresExternalPassword) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        placeholder = { Text("Password that encrypted the cipher") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onSubmit(
                        AddKeyInputs(
                            primary = primary.trim(),
                            password = password,
                            label = label.ifBlank { null },
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
        },
        modifier = Modifier.padding(8.dp),
    )
}
