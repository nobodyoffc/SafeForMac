package com.fc.safe.desktop.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fc.safe.desktop.DesktopFid
import com.fc.safe.platform.macos.WalletSession
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single-select picker over the address-book stored in the `fids`
 * SqliteDB. Returns the chosen FID (just the string — caller will
 * pair it with an amount via [AddTxOutputDialog] or similar).
 *
 * Click-row picks immediately rather than requiring a separate "OK"
 * — single-select with a small list, the extra confirm step would
 * just be friction.
 */
@Composable
fun FidPickerDialog(
    onPicked: (fid: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val fids by produceState<List<DesktopFid>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val db: LocalDB<DesktopFid> =
                WalletSession.openDb("fids", DesktopFid::class.java)
            db.all.values.toList()
                .sortedBy { it.label?.lowercase() ?: it.id.lowercase() }
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
            modifier = Modifier.requiredWidth(520.dp).height(440.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Pick FID from address book", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))

                val list = fids
                when {
                    list == null -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) { Text("Loading…") }
                    list.isEmpty() -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Address book is empty. Open \"Address book (FIDs)\" from the home menu first.",
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(list, key = { it.id }) { f ->
                            FidPickerRow(fid = f, onPick = { onPicked(f.id) })
                            Divider()
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun FidPickerRow(fid: DesktopFid, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FidAvatar(fid = fid.id, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                fid.label?.ifBlank { null } ?: "(no label)",
                style = MaterialTheme.typography.body1,
                color = if (fid.label.isNullOrBlank())
                    MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                else MaterialTheme.colors.onSurface,
            )
            Text(
                fid.id,
                style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
