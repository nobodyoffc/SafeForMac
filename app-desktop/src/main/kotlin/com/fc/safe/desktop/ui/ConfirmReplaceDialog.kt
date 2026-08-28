package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

enum class ConfirmReplaceResult { REPLACE, SKIP, CANCEL }

/**
 * Confirmation shown before a save would silently overwrite existing
 * rows. Android's wallet asks per-record; here we batch the conflict
 * list so the user can make one informed decision for the whole
 * operation.
 *
 * When [showSkip] is true, a third "Skip existing" option is offered —
 * useful for import flows where new (non-conflicting) rows should be
 * saved even if the user doesn't want to overwrite duplicates. For
 * single-item saves, leave [showSkip] false so the user sees only
 * Replace/Cancel.
 */
@Composable
fun ConfirmReplaceDialog(
    title: String,
    conflictIds: List<String>,
    message: String? = null,
    showSkip: Boolean = false,
    onResult: (ConfirmReplaceResult) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onResult(ConfirmReplaceResult.CANCEL) },
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.widthIn(min = 360.dp)) {
                Text(
                    message ?: "The following ID(s) already exist. Replacing will " +
                        "overwrite the stored record — this cannot be undone.",
                    style = MaterialTheme.typography.body2,
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    conflictIds.forEach { id ->
                        Text(
                            id,
                            style = MaterialTheme.typography.caption.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
        },
        confirmButton = {
            SafeButton(onClick = { onResult(ConfirmReplaceResult.REPLACE) }) {
                Text(if (conflictIds.size > 1) "Replace all" else "Replace")
            }
        },
        dismissButton = {
            if (showSkip) {
                TextButton(onClick = { onResult(ConfirmReplaceResult.SKIP) }) {
                    Text("Skip existing")
                }
                TextButton(onClick = { onResult(ConfirmReplaceResult.CANCEL) }) {
                    Text("Cancel")
                }
            } else {
                TextButton(onClick = { onResult(ConfirmReplaceResult.CANCEL) }) {
                    Text("Cancel")
                }
            }
        },
    )
}
