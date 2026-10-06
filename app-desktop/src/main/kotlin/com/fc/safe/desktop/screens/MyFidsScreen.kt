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
import com.fc.safe.desktop.DesktopFid
import com.fc.safe.desktop.ui.AddFidDialog
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CopyableText
import com.fc.safe.desktop.ui.FidAvatar
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.WalletSession
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("MyFidsScreen")
private const val FIDS_DB_NAME = "fids"

/**
 * Address-book of remote FIDs (other people's wallets you regularly
 * send to). Same multi-select / long-press-delete pattern as
 * MyKeys / MySecrets / TOTP. Used by CreateTx as the source for the
 * `AddOutputFromFidListDialog` picker (next batch).
 */
class MyFidsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var fids by remember { mutableStateOf<List<DesktopFid>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var showAdd by remember { mutableStateOf(false) }

        suspend fun refresh() {
            val loaded = withContext(Dispatchers.IO) {
                val db: LocalDB<DesktopFid> =
                    WalletSession.openDb(FIDS_DB_NAME, DesktopFid::class.java)
                db.all.values.toList().sortedByDescending { it.savedAt }
            }
            fids = loaded
            loading = false
        }

        LaunchedEffect(Unit) { refresh() }

        fun addFid(fidStr: String, labelStr: String?) {
            error = null
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopFid> =
                            WalletSession.openDb(FIDS_DB_NAME, DesktopFid::class.java)
                        val rec = DesktopFid().apply {
                            setId(fidStr)
                            label = labelStr
                            savedAt = System.currentTimeMillis()
                        }
                        db.put(rec.id, rec)
                        db.commit()
                    }
                    showAdd = false
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Add FID failed", t)
                    error = "Add failed: ${t.message}"
                } finally {
                    busy = false
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
                        val db: LocalDB<DesktopFid> =
                            WalletSession.openDb(FIDS_DB_NAME, DesktopFid::class.java)
                        ids.forEach { db.remove(it) }
                        db.commit()
                    }
                    selectedIds = emptySet()
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Delete FIDs failed", t)
                    error = "Delete failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        AppShell(
            title = "Address book (FIDs)",
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
                        Text(
                            if (loading) "Loading…" else "${fids.size} FID(s)",
                            style = MaterialTheme.typography.subtitle1,
                        )
                        SafeButton(
                            enabled = !busy,
                            onClick = { showAdd = true },
                        ) { Text("Add FID") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                enabled = !busy,
                                onClick = { selectedIds = emptySet() },
                            ) { Text("Cancel") }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "${selectedIds.size} selected",
                                style = MaterialTheme.typography.subtitle1,
                            )
                        }
                        LongPressFidDeleteButton(
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
                    fids.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Address book is empty.", style = MaterialTheme.typography.subtitle1)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Use \"Add FID\" to add a recipient.",
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
                                items(fids, key = { it.id }) { f ->
                                    FidCard(
                                        fid = f,
                                        selected = f.id in selectedIds,
                                        onToggleSelection = {
                                            selectedIds = if (f.id in selectedIds)
                                                selectedIds - f.id
                                            else selectedIds + f.id
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

        if (showAdd) {
            AddFidDialog(
                onDone = { fid, label -> addFid(fid, label) },
                onDismiss = { if (!busy) showAdd = false },
            )
        }
    }
}

@Composable
private fun FidCard(
    fid: DesktopFid,
    selected: Boolean,
    onToggleSelection: () -> Unit,
) {
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
            FidAvatar(fid = fid.id, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                CopyableText(text = fid.id, showQr = true, qrTitle = "FID")
                Spacer(Modifier.height(2.dp))
                Text(
                    text = fid.label?.ifBlank { null } ?: "(no label)",
                    style = MaterialTheme.typography.caption,
                    color = if (fid.label.isNullOrBlank())
                        MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    else MaterialTheme.colors.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressFidDeleteButton(
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
