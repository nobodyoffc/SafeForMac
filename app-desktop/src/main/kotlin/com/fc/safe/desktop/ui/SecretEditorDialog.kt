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
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import com.fc.safe.desktop.DesktopSecretType
import com.fc.safe.desktop.backup.Base32Shim
import utils.Hex
import java.security.SecureRandom

/**
 * Fields collected from the Create / Update secret dialog. Matches
 * the shape of [com.fc.safe.desktop.DesktopSecret] apart from `id`,
 * which is derived at save-time via `assignId(title, content)` for
 * new records (for updates the existing id is reused).
 */
data class SecretEditorInputs(
    val title: String,
    val type: String,
    val content: String,
    val memo: String?,
)

/**
 * Shared create/update dialog for secrets. `initial` is null for
 * create, non-null for update (in which case [existingId] should be
 * set so the caller can preserve the id instead of deriving a new
 * one on save). `prefilledContent` surfaces an already-decrypted
 * content string for update; on create it stays null.
 *
 * Sized-Surface Dialog + `dismissOnClickOutside=false` for the same
 * reason as Export/Import/KeyPicker — Compose Desktop dismisses too
 * aggressively on focus-change clicks.
 */
@Composable
fun SecretEditorDialog(
    title: String,
    existing: SecretEditorInputs?,
    busy: Boolean,
    onSubmit: (SecretEditorInputs) -> Unit,
    onDismiss: () -> Unit,
) {
    var titleText by remember { mutableStateOf(existing?.title.orEmpty()) }
    var type by remember { mutableStateOf(existing?.type ?: DesktopSecretType.PASSWORD.tag) }
    var content by remember { mutableStateOf(existing?.content.orEmpty()) }
    var memo by remember { mutableStateOf(existing?.memo.orEmpty()) }
    var typeMenuOpen by remember { mutableStateOf(false) }
    var randomMenuOpen by remember { mutableStateOf(false) }

    val canSubmit = !busy && titleText.isNotBlank() && content.isNotBlank() && type.isNotBlank()

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.requiredWidth(560.dp).height(520.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                // Type: text field with a dropdown anchor. Users can
                // either pick a canonical type or free-type their own.
                Box {
                    OutlinedTextField(
                        value = type,
                        onValueChange = { type = it },
                        label = { Text("Type") },
                        singleLine = true,
                        trailingIcon = {
                            androidx.compose.material.IconButton(
                                onClick = { typeMenuOpen = true },
                            ) {
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Pick type")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = typeMenuOpen,
                        onDismissRequest = { typeMenuOpen = false },
                    ) {
                        DesktopSecretType.entries.forEach { t ->
                            DropdownMenuItem(onClick = {
                                type = t.tag
                                typeMenuOpen = false
                            }) { Text(t.tag) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Row above the content field: "Create random" dropdown
                // with Hex (32 bytes → 64 hex chars) and Base32 (16 bytes
                // → ~26 Base32 chars) options. Overwrites whatever is in
                // the content field. Mirrors Android's
                // `CreateSecretActivity.generateRandomContent` but
                // exposes both encodings instead of just Base32.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Content", style = MaterialTheme.typography.caption)
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { randomMenuOpen = true }) {
                            Text("Create random")
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = randomMenuOpen,
                            onDismissRequest = { randomMenuOpen = false },
                        ) {
                            DropdownMenuItem(onClick = {
                                randomMenuOpen = false
                                content = randomHex32()
                            }) { Text("Hex (32 bytes)") }
                            DropdownMenuItem(onClick = {
                                randomMenuOpen = false
                                content = randomBase32Of(16)
                            }) { Text("Base32 (16 bytes)") }
                        }
                    }
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Content") },
                    textStyle = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    trailingIcon = {
                        QrScanTrailingIcon(
                            onDecoded = { content = it.trim() },
                            tooltip = "Scan the secret's content",
                        )
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = { Text("Memo (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = canSubmit,
                        onClick = {
                            onSubmit(
                                SecretEditorInputs(
                                    title = titleText.trim(),
                                    type = type.trim(),
                                    content = content,
                                    memo = memo.ifBlank { null },
                                )
                            )
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

/** 32 random bytes as 64 hex chars — a typical secp256k1 prikey / 256-bit symkey. */
private fun randomHex32(): String {
    val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
    return try { Hex.toHex(bytes) } finally { bytes.fill(0) }
}

/**
 * `n` random bytes as unpadded Base32 (RFC 4648 alphabet A-Z + 2-7).
 * Android's `CreateSecretActivity` uses 16 bytes; we keep the same
 * default so existing TOTP-friendly workflows are byte-compatible.
 */
private fun randomBase32Of(n: Int): String {
    val bytes = ByteArray(n).also(SecureRandom()::nextBytes)
    return try { Base32Shim.toBase32(bytes) } finally { bytes.fill(0) }
}
