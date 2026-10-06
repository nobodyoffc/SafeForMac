package com.fc.safe.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fc.safe.desktop.backup.ExportMode
import com.fc.safe.desktop.backup.ExportResult
import com.fc.safe.platform.macos.WalletSession

/**
 * Export dialog for secrets. Mirrors [ExportKeysDialog] layout
 * exactly — same two phases, same encryption-mode picker — but
 * labeled for secrets and backed by [SecretExporter] on the caller
 * side. Kept as a separate composable (rather than genericised) so
 * the copy reads naturally ("Export N secret(s)") and so any
 * future secret-specific option (e.g. "strip memos") can be added
 * without disturbing the key flow.
 */
@Composable
fun ExportSecretsDialog(
    selectedCount: Int,
    busy: Boolean,
    result: ExportResult?,
    onSubmit: (ExportMode, CharArray?) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(ExportMode.CURRENT_PASSWORD) }
    val clipboard: ClipboardManager = LocalClipboardManager.current

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
                .height(if (result == null) 360.dp else 560.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    text = if (result == null) "Export $selectedCount secret(s)" else "Export complete",
                    style = MaterialTheme.typography.h6,
                )
                Spacer(Modifier.height(16.dp))

                if (result == null) {
                    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Text(
                            "Choose how to protect the exported secrets.",
                            style = MaterialTheme.typography.body2,
                        )
                        Spacer(Modifier.height(12.dp))
                        ExportMode.entries.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = mode == m,
                                    onClick = { mode = m },
                                )
                                Spacer(Modifier.width(4.dp))
                                Column {
                                    Text(m.displayName, style = MaterialTheme.typography.body1)
                                    Text(
                                        when (m) {
                                            ExportMode.CURRENT_PASSWORD ->
                                                "Encrypted under this wallet's password. The recipient needs the same password."
                                            ExportMode.RANDOM_PASSWORD ->
                                                "Encrypted with a fresh password generated just for this export."
                                            ExportMode.NONE ->
                                                "No encryption. Plaintext contents visible to anyone with the file."
                                        },
                                        style = MaterialTheme.typography.caption,
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                } else {
                    if (result.randomPassword != null) {
                        Text(
                            "Random password — copy this separately:",
                            style = MaterialTheme.typography.body2,
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = result.randomPassword,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.body1.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            // The password is the key to the JSON above and
                            // travels separately — on an offline machine that
                            // means a second QR, not a second clipboard hop.
                            trailingIcon = {
                                MakeQrIconButton(
                                    text = result.randomPassword,
                                    title = "Export password",
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        "Exported JSON (paste into the Android app's Import Secrets screen):",
                        style = MaterialTheme.typography.body2,
                    )
                    Spacer(Modifier.height(4.dp))
                    val resultScroll = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .border(
                                1.dp,
                                MaterialTheme.colors.onSurface.copy(alpha = 0.3f),
                                RoundedCornerShape(4.dp),
                            )
                            .background(
                                MaterialTheme.colors.surface,
                                RoundedCornerShape(4.dp),
                            ),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(resultScroll)
                                .padding(12.dp),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = result.text,
                                    style = MaterialTheme.typography.body2.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                )
                            }
                        }
                        VerticalScrollbar(
                            adapter = rememberScrollbarAdapter(resultScroll),
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .fillMaxHeight()
                                .padding(vertical = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(enabled = !busy, onClick = onDismiss) {
                        Text(if (result == null) "Cancel" else "Done")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (result == null) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                val pwdChars = if (mode == ExportMode.CURRENT_PASSWORD) {
                                    WalletSession.currentPasswordCopy()
                                } else null
                                onSubmit(mode, pwdChars)
                            },
                        ) { Text("Export") }
                    } else {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(result.text)) },
                        ) { Text("Copy JSON") }
                        ShowQrTextButton(
                            text = result.text,
                            title = "Exported secrets",
                        )
                    }
                }
            }
        }
    }
}
