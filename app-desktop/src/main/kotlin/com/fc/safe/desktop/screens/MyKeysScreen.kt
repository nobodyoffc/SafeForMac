package com.fc.safe.desktop.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.AlertDialog
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Person
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
import com.fc.safe.desktop.DesktopKeyInfo
import com.fc.safe.desktop.buildKeyFromPrikey32
import com.fc.safe.desktop.buildWatchOnlyFromFid
import com.fc.safe.desktop.buildWatchOnlyFromPubkey
import com.fc.safe.desktop.ui.AddKeyDialog
import com.fc.safe.desktop.ui.AddKeyInputs
import com.fc.safe.desktop.ui.AddKeyMode
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.ConfirmReplaceDialog
import com.fc.safe.desktop.ui.CopyableField
import com.fc.safe.desktop.ui.ConfirmReplaceResult
import com.fc.safe.desktop.ui.FidAvatar
import com.fc.safe.desktop.ui.PasswordPromptDialog
import com.fc.safe.desktop.ui.PrikeyRevealDialog
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.desktop.backup.ExportResult
import com.fc.safe.desktop.backup.KeyExporter
import com.fc.safe.desktop.backup.KeyImporter
import com.fc.safe.desktop.ui.ExportKeysDialog
import com.fc.safe.desktop.ui.ImportKeysDialog
import com.fc.safe.platform.macos.WalletSession
import core.crypto.Decryptor
import core.crypto.Hash
import core.crypto.KeyTools
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import utils.Hex
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val log = LoggerFactory.getLogger("MyKeysScreen")
private const val KEYS_DB_NAME = "keys"

class MyKeysScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var keys by remember { mutableStateOf<List<DesktopKeyInfo>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        var revealPrikeyHex by remember { mutableStateOf<String?>(null) }
        var pendingReveal by remember { mutableStateOf<DesktopKeyInfo?>(null) }
        var addKeyMode by remember { mutableStateOf<AddKeyMode?>(null) }
        var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var showExport by remember { mutableStateOf(false) }
        var showImport by remember { mutableStateOf(false) }
        var exportResult by remember { mutableStateOf<ExportResult?>(null) }
        var pendingImportText by remember { mutableStateOf<String?>(null) }
        var pendingImportAwaitingPassword by remember { mutableStateOf(false) }
        var pendingReplace by remember { mutableStateOf<PendingReplaceKeys?>(null) }

        suspend fun refresh() {
            val loaded = withContext(Dispatchers.IO) {
                val db: LocalDB<DesktopKeyInfo> =
                    WalletSession.openDb(KEYS_DB_NAME, DesktopKeyInfo::class.java)
                db.all.values.toList().sortedByDescending { it.savedAt }
            }
            keys = loaded
            loading = false
        }

        LaunchedEffect(Unit) { refresh() }

        fun persistKey(key: DesktopKeyInfo) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopKeyInfo> =
                            WalletSession.openDb(KEYS_DB_NAME, DesktopKeyInfo::class.java)
                        db.put(key.id, key)
                        db.commit()
                    }
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Save key failed", t)
                    error = "Save failed: ${t.message}"
                } finally {
                    busy = false
                    addKeyMode = null
                }
            }
        }

        fun saveNewKey(key: DesktopKeyInfo) {
            scope.launch {
                val existing = withContext(Dispatchers.IO) {
                    val db: LocalDB<DesktopKeyInfo> =
                        WalletSession.openDb(KEYS_DB_NAME, DesktopKeyInfo::class.java)
                    db.get(key.id)
                }
                if (existing == null) {
                    persistKey(key)
                } else {
                    pendingReplace = PendingReplaceKeys(
                        title = "Replace existing key?",
                        conflictIds = listOf(key.id),
                        showSkip = false,
                        onResult = { result ->
                            when (result) {
                                ConfirmReplaceResult.REPLACE -> persistKey(key)
                                else -> {
                                    busy = false
                                    addKeyMode = null
                                }
                            }
                        },
                    )
                }
            }
        }

        fun addRandomKey() {
            error = null
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.Default) {
                        val newKey = createRandomKey()
                        val db: LocalDB<DesktopKeyInfo> =
                            WalletSession.openDb(KEYS_DB_NAME, DesktopKeyInfo::class.java)
                        db.put(newKey.id, newKey)
                        db.commit()
                    }
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Create random key failed", t)
                    error = "Failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun submitAddKey(mode: AddKeyMode, inputs: AddKeyInputs) {
            error = null
            busy = true
            scope.launch {
                val built = withContext(Dispatchers.Default) {
                    runCatching { buildKeyFromInputs(mode, inputs) }
                }
                built.onSuccess { saveNewKey(it) }
                built.onFailure { t ->
                    log.warn("Add key by {} failed", mode, t)
                    error = t.message ?: "Invalid input"
                    busy = false
                }
            }
        }

        fun updateLabel(key: DesktopKeyInfo, newLabel: String) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopKeyInfo> =
                            WalletSession.openDb(KEYS_DB_NAME, DesktopKeyInfo::class.java)
                        val current = db.get(key.id) ?: return@withContext
                        current.label = newLabel.ifBlank { null }
                        db.put(key.id, current)
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
                        val db: LocalDB<DesktopKeyInfo> =
                            WalletSession.openDb(KEYS_DB_NAME, DesktopKeyInfo::class.java)
                        ids.forEach { db.remove(it) }
                        db.commit()
                    }
                    selectedIds = emptySet()
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Delete keys failed", t)
                    error = "Delete failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun runExport(mode: com.fc.safe.desktop.backup.ExportMode, pwdChars: CharArray?) {
            error = null
            busy = true
            scope.launch {
                val picked = keys.filter { it.id in selectedIds }
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { KeyExporter.export(picked, mode, pwdChars) }
                }
                busy = false
                outcome.onSuccess { result ->
                    exportResult = result
                }
                outcome.onFailure { t ->
                    log.warn("Export failed", t)
                    error = "Export failed: ${t.message}"
                }
            }
        }

        fun persistImported(list: List<DesktopKeyInfo>) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopKeyInfo> =
                            WalletSession.openDb(KEYS_DB_NAME, DesktopKeyInfo::class.java)
                        list.forEach { db.put(it.id, it) }
                        db.commit()
                    }
                    showImport = false
                    pendingImportText = null
                    pendingImportAwaitingPassword = false
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Persist imported keys failed", t)
                    error = "Save failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun runImport(text: String, password: String?) {
            error = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { KeyImporter.importText(text, password) }
                }
                busy = false
                outcome.onSuccess { imported ->
                    if (imported.isEmpty()) {
                        error = "No keys imported — check the JSON"
                        return@onSuccess
                    }
                    scope.launch {
                        val existingIds = withContext(Dispatchers.IO) {
                            val db: LocalDB<DesktopKeyInfo> =
                                WalletSession.openDb(KEYS_DB_NAME, DesktopKeyInfo::class.java)
                            imported.mapNotNull { k -> k.id.takeIf { db.get(it) != null } }
                        }
                        if (existingIds.isEmpty()) {
                            persistImported(imported)
                        } else {
                            pendingReplace = PendingReplaceKeys(
                                title = "Replace existing key(s)?",
                                conflictIds = existingIds,
                                showSkip = imported.size > existingIds.size,
                                onResult = { result ->
                                    when (result) {
                                        ConfirmReplaceResult.REPLACE -> persistImported(imported)
                                        ConfirmReplaceResult.SKIP -> {
                                            val existingSet = existingIds.toSet()
                                            persistImported(imported.filterNot { it.id in existingSet })
                                        }
                                        ConfirmReplaceResult.CANCEL -> Unit
                                    }
                                },
                            )
                        }
                    }
                }
                outcome.onFailure { t ->
                    when (t) {
                        is KeyImporter.PasswordRequired -> {
                            pendingImportText = t.pendingBlob
                            pendingImportAwaitingPassword = true
                            // keep import dialog open so user can enter pwd
                        }
                        else -> {
                            log.warn("Import failed", t)
                            error = "Import failed: ${t.message}"
                        }
                    }
                }
            }
        }

        fun requestReveal(key: DesktopKeyInfo) {
            if (key.prikeyCipher == null) {
                error = "This key has no private key (watch-only or legacy record)."
                return
            }
            // Don't decrypt yet — gate behind a fresh password prompt
            // so a walk-up attacker can't reveal the prikey through an
            // already-unlocked session.
            pendingReveal = key
        }

        fun revealPrikeyNow(key: DesktopKeyInfo) {
            val cipher = key.prikeyCipher ?: return
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { Hex.toHex(WalletSession.decryptFromJson(cipher)) }
                }
                busy = false
                outcome.onSuccess { revealPrikeyHex = it }
                outcome.onFailure {
                    log.warn("Prikey reveal failed", it)
                    error = "Decrypt failed: ${it.message}"
                }
            }
        }

        AppShell(
            title = "My Keys",
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
                            if (loading) "Loading…" else "${keys.size} key(s)",
                            style = MaterialTheme.typography.subtitle1,
                        )
                        AddNewKeyMenu(
                            enabled = !busy,
                            busy = busy,
                            onRandom = ::addRandomKey,
                            onByPhrase = { addKeyMode = AddKeyMode.PHRASE },
                            onByPrikey = { addKeyMode = AddKeyMode.PRIKEY },
                            onByPrikeyCipher = { addKeyMode = AddKeyMode.PRIKEY_CIPHER },
                            onByPubkey = { addKeyMode = AddKeyMode.PUBKEY },
                            onByFid = { addKeyMode = AddKeyMode.FID },
                            onImport = { showImport = true },
                        )
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
                                onClick = { showExport = true },
                            ) { Text("Export") }
                            Spacer(Modifier.width(8.dp))
                            LongPressDeleteButton(
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
                    keys.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No keys yet.", style = MaterialTheme.typography.subtitle1)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Use \"Add new key\" to generate one.",
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
                                items(keys, key = { it.id }) { k ->
                                    KeyCard(
                                        key = k,
                                        selected = k.id in selectedIds,
                                        onToggleSelection = {
                                            selectedIds = if (k.id in selectedIds)
                                                selectedIds - k.id
                                            else selectedIds + k.id
                                        },
                                        onLabelChange = { newLabel -> updateLabel(k, newLabel) },
                                        onRevealPrikey = { requestReveal(k) },
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

        pendingReveal?.let { key ->
            PasswordPromptDialog(
                title = "Confirm password",
                message = "Re-enter your wallet password to reveal the private key for ${key.id}.",
                confirmLabel = "Reveal",
                onDismiss = { pendingReveal = null },
                onVerified = { pwd ->
                    pwd.fill(Char.MIN_VALUE)
                    pendingReveal = null
                    revealPrikeyNow(key)
                },
            )
        }

        revealPrikeyHex?.let { hex ->
            PrikeyRevealDialog(
                prikeyHex = hex,
                onDismiss = { revealPrikeyHex = null },
            )
        }

        addKeyMode?.let { mode ->
            AddKeyDialog(
                mode = mode,
                busy = busy,
                onSubmit = { inputs -> submitAddKey(mode, inputs) },
                onDismiss = { if (!busy) addKeyMode = null },
            )
        }

        if (showExport) {
            ExportKeysDialog(
                selectedCount = selectedIds.size,
                busy = busy,
                result = exportResult,
                onSubmit = { mode, pwdChars ->
                    runExport(mode, pwdChars)
                },
                onDismiss = {
                    if (!busy) {
                        showExport = false
                        exportResult = null
                    }
                },
            )
        }

        if (showImport) {
            ImportKeysDialog(
                busy = busy,
                awaitingPassword = pendingImportAwaitingPassword,
                onSubmit = { text, pwd ->
                    if (pendingImportAwaitingPassword) {
                        val blob = pendingImportText ?: text
                        runImport(blob, pwd)
                    } else {
                        runImport(text, pwd)
                    }
                },
                onDismiss = {
                    if (!busy) {
                        showImport = false
                        pendingImportText = null
                        pendingImportAwaitingPassword = false
                    }
                },
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

private data class PendingReplaceKeys(
    val title: String,
    val conflictIds: List<String>,
    val showSkip: Boolean,
    val onResult: (ConfirmReplaceResult) -> Unit,
)

/**
 * Destructive action confirmed by a long press. A quick click shows a
 * tip so users don't accidentally wipe a key — they must hold the
 * button for the platform's long-press threshold (~500ms) to commit.
 *
 * Built from a styled [Surface] + [combinedClickable] rather than
 * [SafeButton] because Material's `Button` uses its own `clickable`
 * modifier that swallows long-press events — adding a pointerInput
 * above it does not reliably win. Styled to match SafeButton.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressDeleteButton(
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
        DropdownMenu(
            expanded = showTip,
            onDismissRequest = { showTip = false },
        ) {
            DropdownMenuItem(onClick = { showTip = false }) {
                Text("Long-press to confirm delete")
            }
        }
    }
}

@Composable
private fun AddNewKeyMenu(
    enabled: Boolean,
    busy: Boolean,
    onRandom: () -> Unit,
    onByPhrase: () -> Unit,
    onByPrikey: () -> Unit,
    onByPrikeyCipher: () -> Unit,
    onByPubkey: () -> Unit,
    onByFid: () -> Unit,
    onImport: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SafeButton(
            enabled = enabled,
            onClick = { expanded = true },
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colors.onPrimary,
                )
            } else {
                Text("Add new key")
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(onClick = { expanded = false; onRandom() }) { Text("Random") }
            DropdownMenuItem(onClick = { expanded = false; onByPhrase() }) { Text("By mnemonic phrase") }
            DropdownMenuItem(onClick = { expanded = false; onByPrikey() }) { Text("By private key (hex)") }
            DropdownMenuItem(onClick = { expanded = false; onByPrikeyCipher() }) { Text("By prikey cipher JSON") }
            DropdownMenuItem(onClick = { expanded = false; onByPubkey() }) { Text("By public key (watch-only)") }
            DropdownMenuItem(onClick = { expanded = false; onByFid() }) { Text("By FID (watch-only)") }
            Divider()
            DropdownMenuItem(onClick = { expanded = false; onImport() }) { Text("Import keys from backup…") }
        }
    }
}

@Composable
private fun KeyCard(
    key: DesktopKeyInfo,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onLabelChange: (String) -> Unit,
    onRevealPrikey: () -> Unit,
) {
    var expanded by remember(key.id) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = 1.dp,
    ) {
        Column {
            // Collapsed header: checkbox + avatar + FID + label + expand chevron.
            // Clicking anywhere on this row (except the checkbox) toggles expansion.
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
                FidAvatar(fid = key.id, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = key.id,
                        style = MaterialTheme.typography.subtitle1.copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = key.label?.ifBlank { null } ?: "(no label)",
                        style = MaterialTheme.typography.body2,
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
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }

            if (expanded) {
                Divider()
                Column(modifier = Modifier.padding(16.dp)) {
                    EditableLabelRow(initialLabel = key.label, onSave = onLabelChange)

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Created ${formatTs(key.savedAt)}",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    CopyableField(label = "FID", value = key.id)
                    CopyableField(label = "Public key", value = key.pubkey)

                    // prikeyCipher row with reveal action
                    if (!key.prikeyCipher.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                CopyableField(label = "Private key (cipher)", value = key.prikeyCipher)
                            }
                            IconButton(onClick = onRevealPrikey) {
                                Text(
                                    text = "🔑",  // material-icons-core has no VpnKey
                                    style = MaterialTheme.typography.h6,
                                )
                            }
                        }
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp))

                    CopyableField(label = "BTC", value = key.btcAddr)
                    CopyableField(label = "ETH", value = key.ethAddr)
                    CopyableField(label = "TRX", value = key.trxAddr)
                    CopyableField(label = "BCH", value = key.bchAddr)
                    CopyableField(label = "DOGE", value = key.dogeAddr)
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
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "Edit label",
                    modifier = Modifier.size(16.dp),
                )
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

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTs(ms: Long): String =
    if (ms == 0L) "—" else dateFmt.format(Date(ms))

/**
 * Fresh random 256-bit key. See [buildKeyFromPrikey32].
 */
private fun createRandomKey(): DesktopKeyInfo =
    buildKeyFromPrikey32(ByteArray(32).also(SecureRandom()::nextBytes), label = null)

/**
 * Dispatches an [AddKeyMode] + user-provided inputs to the right builder.
 * Runs on [Dispatchers.Default] per the caller — Argon2 encrypt is
 * expensive so we stay off the UI thread.
 */
private fun buildKeyFromInputs(mode: AddKeyMode, inputs: AddKeyInputs): DesktopKeyInfo {
    val primary = inputs.primary
    return when (mode) {
        AddKeyMode.PRIKEY -> {
            val bytes = KeyTools.getPrikey32(primary)
                ?: throw IllegalArgumentException("Invalid private key")
            buildKeyFromPrikey32(bytes, inputs.label)
        }
        AddKeyMode.PHRASE -> {
            if (primary.isBlank()) throw IllegalArgumentException("Phrase is empty")
            val bytes = Hash.sha256(primary.toByteArray(Charsets.UTF_8))
            buildKeyFromPrikey32(bytes, inputs.label)
        }
        AddKeyMode.PRIKEY_CIPHER -> {
            val pwdChars = inputs.password.toCharArray()
            try {
                val cdb = Decryptor().decryptJsonByPassword(primary, pwdChars)
                check(cdb.code == 0) { "Wrong password or corrupted cipher" }
                val raw = cdb.data ?: throw IllegalArgumentException("Decrypt produced no bytes")
                try {
                    val prikey32 = KeyTools.getPrikey32(raw)
                        ?: throw IllegalArgumentException("Decrypted value is not a valid private key")
                    buildKeyFromPrikey32(prikey32, inputs.label)
                } finally {
                    raw.fill(0)
                }
            } finally {
                pwdChars.fill(Char.MIN_VALUE)
            }
        }
        AddKeyMode.PUBKEY -> {
            if (!KeyTools.isPubkey(primary)) throw IllegalArgumentException("Invalid public key")
            buildWatchOnlyFromPubkey(primary, inputs.label)
        }
        AddKeyMode.FID -> {
            if (!KeyTools.isGoodFid(primary)) throw IllegalArgumentException("Invalid FID")
            buildWatchOnlyFromFid(primary, inputs.label)
        }
    }
}
