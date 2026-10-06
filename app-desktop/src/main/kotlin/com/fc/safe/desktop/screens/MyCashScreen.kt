package com.fc.safe.desktop.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CashImportDialog
import com.fc.safe.desktop.ui.ConfirmReplaceDialog
import com.fc.safe.desktop.ui.ConfirmReplaceResult
import com.fc.safe.desktop.ui.CreateCashDialog
import com.fc.safe.desktop.ui.CopyableText
import com.fc.safe.desktop.ui.FidAvatar
import com.fc.safe.desktop.ui.ImportCashFromTxDialog
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.WalletSession
import data.fchData.Cash
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import utils.FchUtils

private val log = LoggerFactory.getLogger("MyCashScreen")
private const val CASH_DB_NAME = "cashes"

/**
 * UTXO inventory for the wallet. Stores FC-JDK's `Cash` directly
 * (extends `FcEntity` via `FcObject`) in a dedicated `cashes`
 * SqliteDB. List view + multi-select + long-press delete + add /
 * import. Selected-total summary lets you eyeball whether you have
 * enough for a tx before opening CreateTx.
 *
 * Pagination is deliberately omitted for v1 — wallets typically
 * have well under 1000 UTXOs at any given time, well within
 * LazyColumn's comfort zone. Add it later if a user complains.
 */
class MyCashScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var cashes by remember { mutableStateOf<List<Cash>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var showAddMenu by remember { mutableStateOf(false) }
        var showCreate by remember { mutableStateOf(false) }
        var showImport by remember { mutableStateOf(false) }
        var showImportFromTx by remember { mutableStateOf(false) }
        var pendingReplace by remember { mutableStateOf<PendingReplaceCash?>(null) }

        suspend fun refresh() {
            val loaded = withContext(Dispatchers.IO) {
                val db: LocalDB<Cash> = WalletSession.openDb(CASH_DB_NAME, Cash::class.java)
                db.all.values.toList().sortedByDescending { it.value ?: 0L }
            }
            cashes = loaded
            loading = false
        }

        LaunchedEffect(Unit) { refresh() }

        fun persistCash(list: List<Cash>) {
            error = null
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<Cash> = WalletSession.openDb(CASH_DB_NAME, Cash::class.java)
                        list.forEach { c ->
                            // Re-derive id if missing — defensive against
                            // pasted JSON that omitted the canonical id.
                            val id = c.id ?: c.makeId(c.birthTxId, c.birthIndex)
                            db.put(id, c)
                        }
                        db.commit()
                    }
                    showCreate = false
                    showImport = false
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Save cash failed", t)
                    error = "Save failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun saveAll(list: List<Cash>) {
            if (list.isEmpty()) return
            error = null
            busy = true
            scope.launch {
                val withIds = list.map { c ->
                    val id = c.id ?: c.makeId(c.birthTxId, c.birthIndex)
                    if (c.id == null) c.id = id
                    c to id
                }
                val existingIds = withContext(Dispatchers.IO) {
                    val db: LocalDB<Cash> = WalletSession.openDb(CASH_DB_NAME, Cash::class.java)
                    withIds.mapNotNull { (_, id) -> id.takeIf { db.get(it) != null } }
                }
                if (existingIds.isEmpty()) {
                    persistCash(list)
                } else {
                    pendingReplace = PendingReplaceCash(
                        title = if (list.size == 1) "Replace existing UTXO?"
                        else "Replace existing UTXO(s)?",
                        conflictIds = existingIds,
                        showSkip = list.size > existingIds.size,
                        onResult = { result ->
                            when (result) {
                                ConfirmReplaceResult.REPLACE -> persistCash(list)
                                ConfirmReplaceResult.SKIP -> {
                                    val existingSet = existingIds.toSet()
                                    persistCash(list.filterNot { it.id in existingSet })
                                }
                                ConfirmReplaceResult.CANCEL -> {
                                    busy = false
                                }
                            }
                        },
                    )
                }
            }
        }

        fun deleteSelected() {
            val ids = selectedIds
            if (ids.isEmpty()) return
            error = null
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<Cash> = WalletSession.openDb(CASH_DB_NAME, Cash::class.java)
                        ids.forEach { db.remove(it) }
                        db.commit()
                    }
                    selectedIds = emptySet()
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Delete cashes failed", t)
                    error = "Delete failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        val totalAll = cashes.sumOf { it.value ?: 0L }
        val totalSelected = cashes.filter { it.id in selectedIds }.sumOf { it.value ?: 0L }

        AppShell(
            title = "My Cash (UTXOs)",
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
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectedIds.isEmpty()) {
                        Column {
                            Text(
                                if (loading) "Loading…" else "${cashes.size} UTXO(s)",
                                style = MaterialTheme.typography.subtitle1,
                            )
                            Text(
                                "Total ${FchUtils.satoshiToCoin(totalAll)} FCH",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        Box {
                            SafeButton(
                                enabled = !busy,
                                onClick = { showAddMenu = true },
                            ) {
                                Text("Add cash")
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showAddMenu,
                                onDismissRequest = { showAddMenu = false },
                            ) {
                                DropdownMenuItem(onClick = {
                                    showAddMenu = false
                                    showCreate = true
                                }) { Text("Create manually") }
                                DropdownMenuItem(onClick = {
                                    showAddMenu = false
                                    showImport = true
                                }) { Text("Import JSON") }
                                DropdownMenuItem(onClick = {
                                    showAddMenu = false
                                    showImportFromTx = true
                                }) { Text("From signed tx hex…") }
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                enabled = !busy,
                                onClick = { selectedIds = emptySet() },
                            ) { Text("Cancel") }
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(
                                    "${selectedIds.size} selected",
                                    style = MaterialTheme.typography.subtitle1,
                                )
                                Text(
                                    "${FchUtils.satoshiToCoin(totalSelected)} FCH",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                )
                            }
                        }
                        LongPressCashDeleteButton(
                            enabled = !busy,
                            selectedCount = selectedIds.size,
                            onConfirmed = ::deleteSelected,
                        )
                    }
                }

                error?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        color = MaterialTheme.colors.error,
                    )
                }

                Divider()

                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    cashes.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No UTXOs yet.", style = MaterialTheme.typography.subtitle1)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Use \"Add cash\" → Create manually, or paste a Cash JSON.",
                                style = MaterialTheme.typography.caption,
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
                                items(cashes, key = { it.id }) { c ->
                                    CashCard(
                                        cash = c,
                                        selected = c.id in selectedIds,
                                        onToggleSelection = {
                                            selectedIds = if (c.id in selectedIds)
                                                selectedIds - c.id
                                            else selectedIds + c.id
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

        if (showCreate) {
            CreateCashDialog(
                onDone = { saveAll(listOf(it)) },
                onDismiss = { if (!busy) showCreate = false },
            )
        }
        if (showImport) {
            CashImportDialog(
                onDone = ::saveAll,
                onDismiss = { if (!busy) showImport = false },
            )
        }

        if (showImportFromTx) {
            ImportCashFromTxDialog(
                onDone = { list ->
                    showImportFromTx = false
                    saveAll(list)
                },
                onDismiss = { if (!busy) showImportFromTx = false },
            )
        }

        pendingReplace?.let { p ->
            ConfirmReplaceDialog(
                title = p.title,
                conflictIds = p.conflictIds,
                showSkip = p.showSkip,
                onResult = { result ->
                    pendingReplace = null
                    p.onResult(result)
                },
            )
        }
    }
}

private data class PendingReplaceCash(
    val title: String,
    val conflictIds: List<String>,
    val showSkip: Boolean,
    val onResult: (ConfirmReplaceResult) -> Unit,
)

@Composable
private fun CashCard(
    cash: Cash,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
    val txId = cash.birthTxId
    val short = if (txId != null && txId.length > 12) "${txId.take(8)}…${txId.takeLast(4)}" else (txId ?: "?")
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
            Spacer(Modifier.width(4.dp))
            cash.owner?.takeIf { it.isNotBlank() }?.let { owner ->
                FidAvatar(fid = owner, size = 36.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                // Shown abbreviated; the full cash id is what gets copied.
                CopyableText(
                    text = "$short:${cash.birthIndex ?: -1}",
                    value = cash.id ?: txId.orEmpty(),
                )
                Spacer(Modifier.height(2.dp))
                CopyableText(
                    text = cash.owner ?: "(no owner)",
                    value = cash.owner.orEmpty(),
                    style = MaterialTheme.typography.caption,
                    color = if (cash.owner.isNullOrBlank())
                        MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    else MaterialTheme.colors.onSurface,
                )
            }
            Text(
                text = "${FchUtils.satoshiToCoin(cash.value ?: 0L)} FCH",
                style = MaterialTheme.typography.body1,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressCashDeleteButton(
    enabled: Boolean,
    selectedCount: Int,
    onConfirmed: () -> Unit,
) {
    var showTip by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = if (enabled) MaterialTheme.colors.error
            else MaterialTheme.colors.error.copy(alpha = 0.5f),
            contentColor = MaterialTheme.colors.onPrimary,
            elevation = 1.dp,
            modifier = Modifier
                .defaultMinSize(minWidth = 120.dp)
                .height(40.dp)
                .combinedClickable(
                    enabled = enabled,
                    onClick = { showTip = true },
                    onLongClick = {
                        showTip = false
                        onConfirmed()
                    },
                ),
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text("Delete ($selectedCount)")
            }
        }
        DropdownMenu(expanded = showTip, onDismissRequest = { showTip = false }) {
            DropdownMenuItem(onClick = { showTip = false }) {
                Text("Long-press to confirm delete")
            }
        }
    }
}
