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
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fc.safe.platform.macos.WalletSession
import data.fchData.Cash
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import utils.FchUtils

/**
 * Multi-select UTXO picker reading from the `cashes` SqliteDB.
 * Returns the chosen [Cash] list to the caller. Optional
 * [ownerFilter] — when supplied, only UTXOs with that owner FID
 * appear, which CreateTxScreen uses to ensure all picked inputs
 * belong to the same key (single-sig requirement).
 *
 * Sorted by value DESC so the largest UTXO is the obvious first
 * pick, mirroring the same default as MyCashScreen.
 */
@Composable
fun CashPickerDialog(
    ownerFilter: String? = null,
    onPicked: (List<Cash>) -> Unit,
    onDismiss: () -> Unit,
) {
    val cashes by produceState<List<Cash>?>(initialValue = null, ownerFilter) {
        value = withContext(Dispatchers.IO) {
            val db: LocalDB<Cash> = WalletSession.openDb("cashes", Cash::class.java)
            db.all.values.toList()
                .filter { ownerFilter == null || it.owner == ownerFilter }
                .sortedByDescending { it.value ?: 0L }
        }
    }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val selectedTotal = cashes?.filter { it.id in selectedIds }?.sumOf { it.value ?: 0L } ?: 0L

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
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Pick UTXOs", style = MaterialTheme.typography.h6)
                if (ownerFilter != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Filtered to owner: $ownerFilter",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.height(12.dp))

                val list = cashes
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
                            if (ownerFilter == null)
                                "No UTXOs in the wallet. Use \"My Cash → Add cash\" first."
                            else
                                "No UTXOs with owner \"$ownerFilter\". " +
                                "Either pick a different sender or import matching cash.",
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        items(list, key = { it.id }) { c ->
                            CashPickerRow(
                                cash = c,
                                selected = c.id in selectedIds,
                                onToggle = {
                                    selectedIds = if (c.id in selectedIds) selectedIds - c.id
                                    else selectedIds + c.id
                                },
                            )
                            Divider()
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (selectedIds.isEmpty()) " "
                        else "${selectedIds.size} selected · " +
                            "${FchUtils.satoshiToCoin(selectedTotal)} FCH",
                        style = MaterialTheme.typography.body2,
                    )
                    Row {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = {
                                onPicked(list.orEmpty().filter { it.id in selectedIds })
                            },
                        ) { Text("Add to inputs") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CashPickerRow(cash: Cash, selected: Boolean, onToggle: () -> Unit) {
    val txId = cash.birthTxId
    val short = if (txId != null && txId.length > 12)
        "${txId.take(8)}…${txId.takeLast(4)}"
    else (txId ?: "?")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(4.dp))
        cash.owner?.takeIf { it.isNotBlank() }?.let { owner ->
            FidAvatar(fid = owner, size = 32.dp)
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "$short:${cash.birthIndex ?: -1}",
                style = MaterialTheme.typography.body2.copy(
                    fontFamily = FontFamily.Monospace,
                ),
            )
            Text(
                cash.owner ?: "(no owner)",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
        }
        Text(
            "${FchUtils.satoshiToCoin(cash.value ?: 0L)} FCH",
            style = MaterialTheme.typography.body2,
        )
    }
}
