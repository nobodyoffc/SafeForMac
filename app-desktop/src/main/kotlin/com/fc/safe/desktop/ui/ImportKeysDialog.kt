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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Paste-and-import dialog. Works in two modes:
 *
 * - Initial: large monospace text area for the concatenated backup
 *   JSON, plus a blank password field (optional — only used if the
 *   export was encrypted but its `BackupKey` didn't carry the random
 *   password, e.g. the "current password" mode on Android).
 *
 * - `awaitingPassword = true`: the importer already tried once and
 *   bounced because it needs a password. The text area is locked
 *   (read-only) so the user focuses on typing the password, and the
 *   caller keeps the parsed blob in its own state to retry with.
 *
 * Uses a plain [Dialog] + explicitly-sized [Surface] for the same
 * reason [ExportKeysDialog] does — Material's [AlertDialog] grows to
 * fit content which overflows for long backups and pushes action
 * buttons into the viewport center.
 */
@Composable
fun ImportKeysDialog(
    busy: Boolean,
    awaitingPassword: Boolean,
    onSubmit: (text: String, password: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    val canSubmit = !busy && (awaitingPassword || text.isNotBlank())

    // See ExportKeysDialog — click-outside-dismiss is too eager on
    // Compose Desktop and can close the dialog mid-interaction.
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .requiredWidth(560.dp)
                .height(480.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = if (awaitingPassword) "Password required"
                    else "Import keys from backup",
                    style = MaterialTheme.typography.h6,
                )
                Spacer(Modifier.height(16.dp))

                Text(
                    text = if (awaitingPassword)
                        "This backup is encrypted. Enter the password that was used to export it."
                    else
                        "Paste the JSON produced by Android \"Export Keys\" (or this app).",
                    style = MaterialTheme.typography.body2,
                )
                Spacer(Modifier.height(8.dp))

                // JSON field takes the remaining vertical space; its
                // own internal scroll handles long pastes.
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Backup JSON") },
                    readOnly = awaitingPassword,
                    textStyle = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = {
                        Text(if (awaitingPassword) "Password" else "Password (if encrypted)")
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = canSubmit,
                        onClick = { onSubmit(text, password.ifBlank { null }) },
                    ) {
                        Text(if (awaitingPassword) "Decrypt" else "Import")
                    }
                }
            }
        }
    }
}
