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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.fc.safe.desktop.backup.ExportMode
import com.fc.safe.desktop.backup.ExportResult
import com.fc.safe.platform.macos.WalletSession

/**
 * Two-phase export dialog.
 *
 * Phase 1 (result == null): pick an encryption mode. CURRENT_PASSWORD
 * reuses the already-unlocked [WalletSession] password, so no extra
 * prompt — the wallet is authenticated enough to act on the user's
 * behalf. RANDOM_PASSWORD generates an 8-byte Base32 string that's
 * surfaced in the result view — the user must copy it out alongside
 * the cipher text, because without it the export is unrecoverable.
 * NONE emits hex prikeys — plainly readable, safe only for
 * same-person, same-machine transfers.
 *
 * Phase 2 (result != null): show the concatenated JSON + Copy. If the
 * mode was RANDOM_PASSWORD, the random password is shown separately
 * so it doesn't get lost in the JSON paste.
 */
@Composable
fun ExportKeysDialog(
    selectedCount: Int,
    busy: Boolean,
    result: ExportResult?,
    onSubmit: (ExportMode, CharArray?) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(ExportMode.CURRENT_PASSWORD) }
    val clipboard: ClipboardManager = LocalClipboardManager.current

    // Plain Dialog with an explicitly sized Surface — Material's
    // AlertDialog auto-sizes its content area from its children's
    // intrinsic height, so with ~50KB of JSON it overflows the window
    // on macOS and action buttons end up overlapping the text. A
    // size-bounded Card gives us the predictable layout: fixed width,
    // capped height, one internal scroll region for the JSON body.
    // `DialogProperties(dismissOnClickOutside = false)` — on Compose
    // Desktop the click-outside detection also fires for in-content
    // clicks that briefly change window focus (e.g. clicking a radio
    // button that was previously out-of-focus), which spuriously
    // closes the dialog. Dismiss is only via Cancel/Done.
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
                    text = if (result == null) "Export $selectedCount key(s)" else "Export complete",
                    style = MaterialTheme.typography.h6,
                )
                Spacer(Modifier.height(16.dp))

                if (result == null) {
                    // Phase 1 body — picker only. Tight content, no need to scroll.
                    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        Text(
                            "Choose how to protect the exported private keys.",
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
                                                "No encryption. Raw private keys in hex. Use only for trusted transfer."
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
                    // Phase 2 body — result view. Header fields above,
                    // JSON viewer takes the remaining vertical space
                    // (weight(1f)) and owns the single scroll region.
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
                        "Exported JSON (paste into the Android app's Import Keys screen):",
                        style = MaterialTheme.typography.body2,
                    )
                    Spacer(Modifier.height(4.dp))
                    val jsonScroll = rememberScrollState()
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
                                .verticalScroll(jsonScroll)
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
                            adapter = rememberScrollbarAdapter(jsonScroll),
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
                            title = "Exported keys",
                        )
                    }
                }
            }
        }
    }
}
