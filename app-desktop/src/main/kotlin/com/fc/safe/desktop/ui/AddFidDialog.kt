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
import core.crypto.KeyTools

/**
 * Add a remote FID to the address book. Validates with
 * `KeyTools.isGoodFid` so users can't paste the wrong shape and end
 * up with a "ghost" recipient that the tx layer would reject anyway.
 */
@Composable
fun AddFidDialog(
    onDone: (fid: String, label: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var fid by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        when {
            fid.isBlank() -> error = "Enter an FID"
            !KeyTools.isGoodFid(fid.trim()) -> error = "Not a valid FID"
            else -> {
                error = null
                onDone(fid.trim(), label.ifBlank { null })
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
            modifier = Modifier.requiredWidth(520.dp).height(280.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Add FID to address book", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = fid,
                    onValueChange = { fid = it; error = null },
                    label = { Text("FID") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
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
