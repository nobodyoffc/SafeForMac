package com.fc.safe.desktop.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
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
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import com.fc.safe.desktop.DesktopMultisig
import com.fc.safe.desktop.backup.ExportResult
import com.fc.safe.desktop.backup.MultisigExporter
import com.fc.safe.desktop.backup.MultisigImporter
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.ConfirmReplaceDialog
import com.fc.safe.desktop.ui.ConfirmReplaceResult
import com.fc.safe.desktop.ui.CreateMultisigDialog
import com.fc.safe.desktop.ui.CreateMultisigInputs
import com.fc.safe.desktop.ui.ExportMultisigsDialog
import com.fc.safe.desktop.ui.FidAvatar
import com.fc.safe.desktop.ui.ImportMultisigsDialog
import com.fc.safe.desktop.ui.SafeButton
import androidx.compose.material.icons.filled.ArrowDropDown
import com.fc.safe.platform.macos.WalletSession
import core.crypto.KeyTools
import data.fchData.P2SH
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import utils.Hex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val log = LoggerFactory.getLogger("MyMultisigsScreen")
private const val MULTISIGS_DB_NAME = "multisigs"

/**
 * Saved multisig groups — each row is an M-of-N with a fixed
 * ordered pubkey list. Mirrors Android's `MultisignActivity` list
 * surface (`CreateMultisignIdActivity` is our `CreateMultisigDialog`).
 *
 * List-only in this batch. The downstream Create / Sign / Build tx
 * screens are separate batches; they'll read from this same DB.
 */
class MyMultisigsScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var groups by remember { mutableStateOf<List<DesktopMultisig>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var showCreate by remember { mutableStateOf(false) }
        var showAddMenu by remember { mutableStateOf(false) }
        var showExport by remember { mutableStateOf(false) }
        var exportResult by remember { mutableStateOf<ExportResult?>(null) }
        var showImport by remember { mutableStateOf(false) }
        var pendingReplace by remember { mutableStateOf<PendingReplaceMultisig?>(null) }

        suspend fun refresh() {
            val loaded = withContext(Dispatchers.IO) {
                val db: LocalDB<DesktopMultisig> =
                    WalletSession.openDb(MULTISIGS_DB_NAME, DesktopMultisig::class.java)
                db.all.values.toList().sortedByDescending { it.savedAt }
            }
            groups = loaded
            loading = false
        }

        LaunchedEffect(Unit) { refresh() }

        fun persist(group: DesktopMultisig) {
            error = null
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopMultisig> =
                            WalletSession.openDb(MULTISIGS_DB_NAME, DesktopMultisig::class.java)
                        db.put(group.id, group)
                        db.commit()
                    }
                    showCreate = false
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Save multisig failed", t)
                    error = "Save failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun submitCreate(inputs: CreateMultisigInputs) {
            error = null
            busy = true
            scope.launch {
                val built = withContext(Dispatchers.Default) {
                    runCatching { buildMultisig(inputs) }
                }
                busy = false
                built.onSuccess { group ->
                    scope.launch {
                        val existing = withContext(Dispatchers.IO) {
                            val db: LocalDB<DesktopMultisig> =
                                WalletSession.openDb(MULTISIGS_DB_NAME, DesktopMultisig::class.java)
                            db.get(group.id)
                        }
                        if (existing == null) {
                            persist(group)
                        } else {
                            pendingReplace = PendingReplaceMultisig(
                                conflictIds = listOf(group.id),
                                onResult = { result ->
                                    when (result) {
                                        ConfirmReplaceResult.REPLACE -> persist(group)
                                        else -> Unit
                                    }
                                },
                            )
                        }
                    }
                }
                built.onFailure { t ->
                    log.warn("Build multisig failed", t)
                    error = t.message ?: "Invalid input"
                }
            }
        }

        fun updateLabel(group: DesktopMultisig, newLabel: String) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopMultisig> =
                            WalletSession.openDb(MULTISIGS_DB_NAME, DesktopMultisig::class.java)
                        val current = db.get(group.id) ?: return@withContext
                        current.label = newLabel.ifBlank { null }
                        db.put(group.id, current)
                        db.commit()
                    }
                    refresh()
                } catch (t: Throwable) {
                    error = "Label save failed: ${t.message}"
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
                        val db: LocalDB<DesktopMultisig> =
                            WalletSession.openDb(MULTISIGS_DB_NAME, DesktopMultisig::class.java)
                        ids.forEach { db.remove(it) }
                        db.commit()
                    }
                    selectedIds = emptySet()
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Delete multisigs failed", t)
                    error = "Delete failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun runExport() {
            error = null
            busy = true
            exportResult = null
            scope.launch {
                val picked = groups.filter { it.id in selectedIds }
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { MultisigExporter.export(picked) }
                }
                busy = false
                outcome.onSuccess { exportResult = it }
                outcome.onFailure {
                    log.warn("Export multisigs failed", it)
                    error = "Export failed: ${it.message}"
                    showExport = false
                }
            }
        }

        fun persistImported(list: List<DesktopMultisig>) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopMultisig> =
                            WalletSession.openDb(MULTISIGS_DB_NAME, DesktopMultisig::class.java)
                        list.forEach { db.put(it.id, it) }
                        db.commit()
                    }
                    showImport = false
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Persist imported multisigs failed", t)
                    error = "Save failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun runImport(text: String) {
            error = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { MultisigImporter.importText(text) }
                }
                busy = false
                outcome.onSuccess { imported ->
                    if (imported.isEmpty()) {
                        error = "No multisigs found — check the JSON"
                        return@onSuccess
                    }
                    scope.launch {
                        val existingIds = withContext(Dispatchers.IO) {
                            val db: LocalDB<DesktopMultisig> =
                                WalletSession.openDb(MULTISIGS_DB_NAME, DesktopMultisig::class.java)
                            imported.mapNotNull { g -> g.id.takeIf { db.get(it) != null } }
                        }
                        if (existingIds.isEmpty()) {
                            persistImported(imported)
                        } else {
                            pendingReplace = PendingReplaceMultisig(
                                conflictIds = existingIds,
                                onResult = { result ->
                                    when (result) {
                                        ConfirmReplaceResult.REPLACE ->
                                            persistImported(imported)
                                        else -> Unit
                                    }
                                },
                            )
                        }
                    }
                }
                outcome.onFailure {
                    log.warn("Import multisigs failed", it)
                    error = "Import failed: ${it.message}"
                }
            }
        }

        AppShell(
            title = "My Multisigs",
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
                            if (loading) "Loading…" else "${groups.size} group(s)",
                            style = MaterialTheme.typography.subtitle1,
                        )
                        Box {
                            SafeButton(
                                enabled = !busy,
                                onClick = { showAddMenu = true },
                            ) {
                                Text("New multisig")
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showAddMenu,
                                onDismissRequest = { showAddMenu = false },
                            ) {
                                DropdownMenuItem(onClick = {
                                    showAddMenu = false
                                    showCreate = true
                                }) { Text("Create…") }
                                DropdownMenuItem(onClick = {
                                    showAddMenu = false
                                    showImport = true
                                }) { Text("Import from backup…") }
                            }
                        }
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SafeButton(
                                enabled = !busy,
                                onClick = {
                                    showExport = true
                                    runExport()
                                },
                            ) { Text("Export") }
                            Spacer(Modifier.width(8.dp))
                            LongPressMultisigDeleteButton(
                                enabled = !busy,
                                selectedCount = selectedIds.size,
                                onConfirmed = ::deleteSelected,
                            )
                        }
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
                    groups.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No multisig groups yet.", style = MaterialTheme.typography.subtitle1)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Use \"New multisig\" to create one.",
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
                                items(groups, key = { it.id }) { g ->
                                    MultisigCard(
                                        group = g,
                                        selected = g.id in selectedIds,
                                        onToggleSelection = {
                                            selectedIds = if (g.id in selectedIds)
                                                selectedIds - g.id
                                            else selectedIds + g.id
                                        },
                                        onLabelChange = { newLabel -> updateLabel(g, newLabel) },
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
            CreateMultisigDialog(
                busy = busy,
                onSubmit = ::submitCreate,
                onDismiss = { if (!busy) showCreate = false },
            )
        }

        if (showExport) {
            ExportMultisigsDialog(
                selectedCount = selectedIds.size,
                result = exportResult,
                onDismiss = {
                    if (!busy) {
                        showExport = false
                        exportResult = null
                    }
                },
            )
        }

        if (showImport) {
            ImportMultisigsDialog(
                busy = busy,
                onSubmit = { text -> runImport(text) },
                onDismiss = { if (!busy) showImport = false },
            )
        }

        pendingReplace?.let { p ->
            ConfirmReplaceDialog(
                title = if (p.conflictIds.size > 1)
                    "Replace existing multisig(s)?"
                else "Replace existing multisig?",
                conflictIds = p.conflictIds,
                onResult = { result ->
                    pendingReplace = null
                    p.onResult(result)
                },
            )
        }
    }
}

private data class PendingReplaceMultisig(
    val conflictIds: List<String>,
    val onResult: (ConfirmReplaceResult) -> Unit,
)

@Composable
private fun MultisigCard(
    group: DesktopMultisig,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onLabelChange: (String) -> Unit,
) {
    var expanded by remember(group.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = 1.dp,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                )
                Spacer(Modifier.width(4.dp))
                FidAvatar(fid = group.id, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.id,
                        style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${group.m}-of-${group.n} — ${group.label?.ifBlank { null } ?: "(no label)"}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }

            if (expanded) {
                Divider()
                Column(modifier = Modifier.padding(16.dp)) {
                    EditableLabelRow(initialLabel = group.label, onSave = onLabelChange)

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Created ${formatTs(group.savedAt)}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        "Members (${group.fids.size})",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                    group.fids.forEachIndexed { i, fid ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FidAvatar(fid = fid, size = 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${i + 1}. $fid",
                                style = MaterialTheme.typography.body2.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                            )
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        "Redeem script",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                    Text(
                        group.redeemScript ?: "—",
                        style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditableLabelRow(
    initialLabel: String?,
    onSave: (String) -> Unit,
) {
    var editing by remember(initialLabel) { mutableStateOf(false) }
    var draft by remember(initialLabel) { mutableStateOf(initialLabel.orEmpty()) }

    if (!editing) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = initialLabel?.ifBlank { null } ?: "(no label)",
                style = MaterialTheme.typography.body1,
                color = if (initialLabel.isNullOrBlank())
                    MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                else MaterialTheme.colors.onSurface,
            )
            IconButton(onClick = { draft = initialLabel.orEmpty(); editing = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit label", modifier = Modifier.padding(0.dp))
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier.weight(1f),
                label = { Text("Label") },
            )
            IconButton(onClick = { editing = false; onSave(draft) }) {
                Icon(Icons.Filled.Check, contentDescription = "Save label")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressMultisigDeleteButton(
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

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTs(ms: Long): String =
    if (ms == 0L) "—" else dateFmt.format(Date(ms))

/**
 * Derive redeem script + FID via FC-JDK and wrap in a [DesktopMultisig].
 * The FID is the P2SH multisig address (hash160 of the redeem script
 * → base58check) — computed by `KeyTools.scriptToMultiAddr`, same as
 * what the chain will use.
 *
 * Member FIDs are derived per-pubkey via
 * `KeyTools.pubkeyToFchAddr` and cached so the card can render them
 * without re-deriving on every paint.
 */
private fun buildMultisig(inputs: CreateMultisigInputs): DesktopMultisig {
    val script = P2SH.makeMultisigRedeemScript(inputs.pubkeys, inputs.m, inputs.n)
    val hex = Hex.toHex(script.program)
    val fid = KeyTools.scriptToMultiAddr(hex)
        ?: throw IllegalStateException("Could not derive multisig address")
    val memberFids = inputs.pubkeys.map { pk ->
        KeyTools.pubkeyToFchAddr(pk)
            ?: throw IllegalStateException("Could not derive FID for pubkey $pk")
    }
    return DesktopMultisig().apply {
        setId(fid)
        m = inputs.m
        n = inputs.n
        redeemScript = hex
        pubkeys = inputs.pubkeys
        fids = memberFids
        label = inputs.label
        savedAt = System.currentTimeMillis()
    }
}
