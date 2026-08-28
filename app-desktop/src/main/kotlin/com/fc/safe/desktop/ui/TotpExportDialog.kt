package com.fc.safe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fc.safe.desktop.totp.TotpExportFormat

/**
 * Two-phase: pick format → preview + Copy. Same sized-Surface
 * Dialog + `dismissOnClickOutside=false` pattern as the key
 * export dialog, but without the encryption radio row — TOTP seeds
 * export plaintext by design (see [TotpExportFormat] docs).
 */
@Composable
fun TotpExportDialog(
    selectedCount: Int,
    result: String?,
    onSubmit: (TotpExportFormat) -> Unit,
    onDismiss: () -> Unit,
) {
    var format by remember { mutableStateOf(TotpExportFormat.OTPAUTH) }
    val clipboard = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.requiredWidth(560.dp)
                .height(if (result == null) 320.dp else 520.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text(
                    if (result == null) "Export $selectedCount TOTP code(s)"
                    else "Export complete",
                    style = MaterialTheme.typography.h6,
                )
                Spacer(Modifier.height(16.dp))

                if (result == null) {
                    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Text(
                            "Pick a format. TOTP seeds export plaintext so other apps can import them.",
                            style = MaterialTheme.typography.body2,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = format == TotpExportFormat.OTPAUTH,
                                onClick = { format = TotpExportFormat.OTPAUTH },
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text("otpauth:// URIs", style = MaterialTheme.typography.body1)
                                Text(
                                    "One per line. Google Authenticator / 1Password / Authy all import this.",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = format == TotpExportFormat.JSON,
                                onClick = { format = TotpExportFormat.JSON },
                            )
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text("JSON array", style = MaterialTheme.typography.body1)
                                Text(
                                    "`[{\"secret\":..., \"label\":...}]` — same shape Safe Android imports.",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                } else {
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
                            )
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text = result,
                                style = MaterialTheme.typography.body2.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (result == null) "Cancel" else "Done")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (result == null) {
                        TextButton(onClick = { onSubmit(format) }) { Text("Export") }
                    } else {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(result)) },
                        ) { Text("Copy") }
                    }
                }
            }
        }
    }
}
