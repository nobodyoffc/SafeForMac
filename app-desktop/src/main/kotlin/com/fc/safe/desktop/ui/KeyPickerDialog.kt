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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fc.safe.desktop.DesktopKeyInfo
import com.fc.safe.platform.macos.WalletSession
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What kind of key material the caller needs. Filters the picker list so
 * users don't pick a key that can't serve the operation.
 *
 * - [WithPrikey] — sign, decrypt-asymmetric. Requires a non-null
 *   [DesktopKeyInfo.prikeyCipher].
 * - [WithPubkey] — encrypt-asymmetric, verify. Requires
 *   [DesktopKeyInfo.pubkey]. Watch-only keys qualify.
 */
enum class KeyPickerFilter {
    WithPrikey,
    WithPubkey,
}

/**
 * Dialog that lists wallet keys and calls [onPicked] with the chosen
 * one. Matches Android's `ChooseKeyInfoActivity`.
 */
@Composable
fun KeyPickerDialog(
    filter: KeyPickerFilter,
    onPicked: (DesktopKeyInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val keys by produceState<List<DesktopKeyInfo>?>(initialValue = null, filter) {
        value = withContext(Dispatchers.IO) {
            val db: LocalDB<DesktopKeyInfo> =
                WalletSession.openDb("keys", DesktopKeyInfo::class.java)
            db.all.values.toList()
                .filter {
                    when (filter) {
                        KeyPickerFilter.WithPrikey -> !it.prikeyCipher.isNullOrBlank()
                        KeyPickerFilter.WithPubkey -> !it.pubkey.isNullOrBlank()
                    }
                }
                .sortedByDescending { it.savedAt }
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
            modifier = Modifier.requiredWidth(520.dp).height(460.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    text = when (filter) {
                        KeyPickerFilter.WithPrikey -> "Pick a signing key"
                        KeyPickerFilter.WithPubkey -> "Pick a public key"
                    },
                    style = MaterialTheme.typography.h6,
                )
                Spacer(Modifier.height(12.dp))

                val list = keys
                when {
                    list == null -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) { Text("Loading…") }
                    list.isEmpty() -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        Text(
                            when (filter) {
                                KeyPickerFilter.WithPrikey ->
                                    "No keys with a private key. Add one first."
                                KeyPickerFilter.WithPubkey ->
                                    "No keys with a public key. Add one first."
                            },
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    ) {
                        items(list, key = { it.id }) { k ->
                            KeyPickerRow(k) { onPicked(k) }
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
private fun KeyPickerRow(key: DesktopKeyInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        FidAvatar(fid = key.id, size = 36.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key.id,
                style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = key.label?.ifBlank { null } ?: "(no label)",
                style = MaterialTheme.typography.caption,
                color = if (key.label.isNullOrBlank())
                    MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                else MaterialTheme.colors.onSurface,
            )
            if (key.watchOnly) {
                Text(
                    "watch-only",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary,
                )
            }
        }
    }
}
