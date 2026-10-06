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
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fc.safe.desktop.backup.ExportResult

/**
 * Single-phase export dialog — multisig groups are public, so there
 * are no modes to pick. Caller generates the [ExportResult] up front
 * and this just shows it with Copy + Done.
 *
 * While [result] is null we render a brief "generating…" spinner;
 * in practice the export is effectively instantaneous (no Argon2)
 * so the user rarely sees it.
 */
@Composable
fun ExportMultisigsDialog(
    selectedCount: Int,
    result: ExportResult?,
    onDismiss: () -> Unit,
) {
    val clipboard: ClipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
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
                Text(
                    text = if (result == null) "Exporting $selectedCount group(s)…"
                    else "Export complete",
                    style = MaterialTheme.typography.h6,
                )
                Spacer(Modifier.height(16.dp))

                if (result == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                } else {
                    Text(
                        "Multisig groups are public data. Paste the JSON into another " +
                            "wallet's \"Import multisig\" box to share them.",
                        style = MaterialTheme.typography.body2,
                    )
                    Spacer(Modifier.height(8.dp))
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
                    TextButton(onClick = onDismiss) {
                        Text(if (result == null) "Cancel" else "Done")
                    }
                    Spacer(Modifier.width(8.dp))
                    if (result != null) {
                        TextButton(
                            onClick = { clipboard.setText(AnnotatedString(result.text)) },
                        ) { Text("Copy JSON") }
                        ShowQrTextButton(
                            text = result.text,
                            title = "Exported multisigs",
                        )
                    }
                }
            }
        }
    }
}
