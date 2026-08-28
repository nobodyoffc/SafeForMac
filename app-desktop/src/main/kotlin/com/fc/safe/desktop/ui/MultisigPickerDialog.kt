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
import com.fc.safe.desktop.DesktopMultisig
import com.fc.safe.platform.macos.WalletSession
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single-select picker over the `multisigs` SqliteDB populated by
 * [com.fc.safe.desktop.screens.MyMultisigsScreen]. Returns the chosen
 * [DesktopMultisig] so the caller can read M-of-N + redeem script
 * without re-querying the DB.
 *
 * Used by CreateMultisigTxScreen to bind the sender to a saved group.
 */
@Composable
fun MultisigPickerDialog(
    onPicked: (DesktopMultisig) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups by produceState<List<DesktopMultisig>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val db: LocalDB<DesktopMultisig> =
                WalletSession.openDb("multisigs", DesktopMultisig::class.java)
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
            modifier = Modifier.requiredWidth(560.dp).height(460.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Pick multisig group", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))

                val list = groups
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
                            "No multisig groups saved. Create one in \"My Multisigs\" first.",
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(list, key = { it.id }) { g ->
                            MultisigPickerRow(group = g, onPick = { onPicked(g) })
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
private fun MultisigPickerRow(group: DesktopMultisig, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FidAvatar(fid = group.id, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                group.label?.ifBlank { null } ?: "(no label)",
                style = MaterialTheme.typography.body1,
                color = if (group.label.isNullOrBlank())
                    MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                else MaterialTheme.colors.onSurface,
            )
            Text(
                "${group.m}-of-${group.n}  ·  ${group.id}",
                style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
