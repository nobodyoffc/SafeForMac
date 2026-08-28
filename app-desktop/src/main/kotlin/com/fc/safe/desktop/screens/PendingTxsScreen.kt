package com.fc.safe.desktop.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.DesktopPendingTx
import com.fc.safe.desktop.PENDING_TXS_DB_NAME
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.platform.macos.WalletSession
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import utils.FchUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Air-gapped signing produces a broadcast-ready hex that only reaches
 * the chain via a separate online device. Only the user knows whether
 * the tx actually confirmed — so instead of mutating the cash DB at
 * sign time, we stage each signed tx here and let the user decide
 * later: **Apply** (commit the UTXO diff), **Discard** (drop it —
 * broadcast failed or was abandoned), or **Keep pending** (come back
 * later). See [PendingTxDetailScreen] for the per-record actions.
 *
 * Records are keyed by canonical txid (parsed from the signed hex at
 * save time), so re-saving the same tx overwrites rather than
 * duplicating. Ordering is newest-first by `createdAt`.
 */
class PendingTxsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()

        var records by remember { mutableStateOf<List<DesktopPendingTx>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var reloadKey by remember { mutableStateOf(0) }

        LaunchedEffect(reloadKey) {
            loading = true
            val loaded = withContext(Dispatchers.IO) {
                val db: LocalDB<DesktopPendingTx> = WalletSession.openDb(
                    PENDING_TXS_DB_NAME, DesktopPendingTx::class.java
                )
                db.all.values.toList().sortedByDescending { it.createdAt }
            }
            records = loaded
            loading = false
        }

        AppShell(
            title = "Pending Broadcasts",
            scaffoldState = scaffoldState,
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (loading) "Loading…" else "${records.size} pending tx(s)",
                        style = MaterialTheme.typography.subtitle1,
                    )
                }
                Text(
                    "Signed but not yet reconciled. Tap a record to Apply " +
                        "(update cash list) or Discard.",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(8.dp))
                Divider()

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    records.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No pending transactions.", style = MaterialTheme.typography.subtitle1)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "After signing a TX, use \"Save to Pending Broadcasts\" to track it here.",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                    else -> {
                        val listState = rememberLazyListState()
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                items(records, key = { it.id ?: it.createdAt.toString() }) { rec ->
                                    PendingTxCard(
                                        rec = rec,
                                        onOpen = {
                                            navigator.push(
                                                PendingTxDetailScreen(
                                                    txid = rec.id ?: return@PendingTxCard,
                                                    onChanged = { reloadKey += 1 },
                                                )
                                            )
                                        },
                                    )
                                }
                            }
                            VerticalScrollbar(
                                adapter = rememberScrollbarAdapter(listState),
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .fillMaxHeight()
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val DATE_FMT = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
private fun PendingTxCard(
    rec: DesktopPendingTx,
    onOpen: () -> Unit,
) {
    val txid = rec.id ?: "?"
    val shortTxid = if (txid.length > 14) "${txid.take(10)}…${txid.takeLast(4)}" else txid
    val dateStr = DATE_FMT.format(Date(rec.createdAt))
    val flavor = rec.flavor?.lowercase() ?: "?"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onOpen),
        elevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = shortTxid,
                    style = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = flavor,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rec.senderFid ?: "(sender unknown)",
                    style = MaterialTheme.typography.caption,
                    color = if (rec.senderFid.isNullOrBlank())
                        MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    else MaterialTheme.colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${rec.inputCount} in · ${rec.outputCount} out",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
                Text(
                    text = "${FchUtils.satoshiToCoin(rec.totalOut)} FCH out",
                    style = MaterialTheme.typography.body2,
                )
            }
        }
    }
}
