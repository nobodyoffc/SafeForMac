package com.fc.safe.desktop.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import com.fc.safe.desktop.DesktopSecret
import com.fc.safe.desktop.backup.ExportMode
import com.fc.safe.desktop.backup.ExportResult
import com.fc.safe.desktop.backup.SecretExporter
import com.fc.safe.desktop.backup.SecretImporter
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.ConfirmReplaceDialog
import com.fc.safe.desktop.ui.ConfirmReplaceResult
import com.fc.safe.desktop.ui.ExportSecretsDialog
import com.fc.safe.desktop.ui.ImportSecretsDialog
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.desktop.ui.SecretEditorDialog
import com.fc.safe.desktop.ui.SecretEditorInputs
import com.fc.safe.desktop.ui.SecretRevealDialog
import com.fc.safe.platform.macos.WalletSession
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.icons.filled.ArrowDropDown
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val log = LoggerFactory.getLogger("MySecretsScreen")
private const val SECRETS_DB_NAME = "secrets"

class MySecretsScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var secrets by remember { mutableStateOf<List<DesktopSecret>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var reveal by remember { mutableStateOf<Pair<DesktopSecret, String>?>(null) }
        var creating by remember { mutableStateOf(false) }
        var showCreateMenu by remember { mutableStateOf(false) }
        var showExport by remember { mutableStateOf(false) }
        var exportResult by remember { mutableStateOf<ExportResult?>(null) }
        var showImport by remember { mutableStateOf(false) }
        var pendingImportText by remember { mutableStateOf<String?>(null) }
        var pendingImportAwaitingPassword by remember { mutableStateOf(false) }
        var updating by remember { mutableStateOf<Pair<DesktopSecret, SecretEditorInputs>?>(null) }
        var pendingReplace by remember { mutableStateOf<PendingReplaceSecrets?>(null) }

        suspend fun refresh() {
            val loaded = withContext(Dispatchers.IO) {
                val db: LocalDB<DesktopSecret> =
                    WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                db.all.values.toList().sortedByDescending { it.savedAt }
            }
            secrets = loaded
            loading = false
        }

        LaunchedEffect(Unit) { refresh() }

        fun persistSecret(sec: DesktopSecret) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopSecret> =
                            WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                        db.put(sec.id, sec)
                        db.commit()
                    }
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Save secret failed", t)
                    error = "Save failed: ${t.message}"
                } finally {
                    busy = false
                    creating = false
                    updating = null
                }
            }
        }

        fun submitNew(inputs: SecretEditorInputs) {
            error = null
            busy = true
            scope.launch {
                val built = withContext(Dispatchers.Default) {
                    runCatching {
                        val cipher = WalletSession.encryptToJson(
                            inputs.content.toByteArray(Charsets.UTF_8)
                        )
                        DesktopSecret().apply {
                            assignId(inputs.title, inputs.content)
                            this.title = inputs.title
                            this.type = inputs.type
                            this.memo = inputs.memo
                            this.contentCipher = cipher
                            this.savedAt = System.currentTimeMillis()
                        }
                    }
                }
                built.onSuccess { sec ->
                    scope.launch {
                        val existing = withContext(Dispatchers.IO) {
                            val db: LocalDB<DesktopSecret> =
                                WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                            db.get(sec.id)
                        }
                        if (existing == null) {
                            persistSecret(sec)
                        } else {
                            pendingReplace = PendingReplaceSecrets(
                                title = "Replace existing secret?",
                                conflictIds = listOf(sec.id),
                                showSkip = false,
                                onResult = { result ->
                                    when (result) {
                                        ConfirmReplaceResult.REPLACE -> persistSecret(sec)
                                        else -> {
                                            busy = false
                                            creating = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                built.onFailure { t ->
                    log.warn("Create secret failed", t)
                    error = t.message ?: "Create failed"
                    busy = false
                }
            }
        }

        fun submitUpdate(target: DesktopSecret, inputs: SecretEditorInputs) {
            error = null
            busy = true
            scope.launch {
                val built = withContext(Dispatchers.Default) {
                    runCatching {
                        val cipher = WalletSession.encryptToJson(
                            inputs.content.toByteArray(Charsets.UTF_8)
                        )
                        // Preserve the original id — updating title/content
                        // would otherwise re-hash to a different id and
                        // create a duplicate.
                        DesktopSecret().apply {
                            setId(target.id)
                            this.title = inputs.title
                            this.type = inputs.type
                            this.memo = inputs.memo
                            this.contentCipher = cipher
                            this.savedAt = System.currentTimeMillis()
                        }
                    }
                }
                built.onSuccess { persistSecret(it) }
                built.onFailure { t ->
                    log.warn("Update secret failed", t)
                    error = t.message ?: "Update failed"
                    busy = false
                }
            }
        }

        fun revealSecret(sec: DesktopSecret) {
            val cipher = sec.contentCipher
            if (cipher.isNullOrBlank()) {
                error = "This secret has no content cipher."
                return
            }
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching {
                        val bytes = WalletSession.decryptFromJson(cipher)
                        val s = String(bytes, Charsets.UTF_8)
                        bytes.fill(0)
                        s
                    }
                }
                busy = false
                outcome.onSuccess { reveal = sec to it }
                outcome.onFailure {
                    log.warn("Reveal secret failed", it)
                    error = "Decrypt failed: ${it.message}"
                }
            }
        }

        fun startUpdate(sec: DesktopSecret) {
            val cipher = sec.contentCipher ?: run {
                error = "This secret has no content cipher."
                return
            }
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching {
                        val bytes = WalletSession.decryptFromJson(cipher)
                        val s = String(bytes, Charsets.UTF_8)
                        bytes.fill(0)
                        s
                    }
                }
                busy = false
                outcome.onSuccess { plaintext ->
                    updating = sec to SecretEditorInputs(
                        title = sec.title.orEmpty(),
                        type = sec.type.orEmpty(),
                        content = plaintext,
                        memo = sec.memo,
                    )
                }
                outcome.onFailure {
                    log.warn("Decrypt for update failed", it)
                    error = "Decrypt failed: ${it.message}"
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
                        val db: LocalDB<DesktopSecret> =
                            WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                        ids.forEach { db.remove(it) }
                        db.commit()
                    }
                    selectedIds = emptySet()
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Delete secrets failed", t)
                    error = "Delete failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun runExport(mode: ExportMode, pwdChars: CharArray?) {
            error = null
            busy = true
            scope.launch {
                val picked = secrets.filter { it.id in selectedIds }
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { SecretExporter.export(picked, mode, pwdChars) }
                }
                busy = false
                outcome.onSuccess { exportResult = it }
                outcome.onFailure { t ->
                    log.warn("Export secrets failed", t)
                    error = "Export failed: ${t.message}"
                }
                if (mode == ExportMode.CURRENT_PASSWORD) pwdChars?.fill(Char.MIN_VALUE)
            }
        }

        fun persistImportedSecrets(list: List<DesktopSecret>) {
            scope.launch {
                busy = true
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopSecret> =
                            WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                        list.forEach { db.put(it.id, it) }
                        db.commit()
                    }
                    showImport = false
                    pendingImportText = null
                    pendingImportAwaitingPassword = false
                    refresh()
                } catch (t: Throwable) {
                    log.warn("Persist imported secrets failed", t)
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
                    runCatching { SecretImporter.importText(text, password) }
                }
                busy = false
                outcome.onSuccess { imported ->
                    if (imported.isEmpty()) {
                        error = "No secrets imported — check the JSON"
                        return@onSuccess
                    }
                    scope.launch {
                        val existingIds = withContext(Dispatchers.IO) {
                            val db: LocalDB<DesktopSecret> =
                                WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                            imported.mapNotNull { s -> s.id.takeIf { db.get(it) != null } }
                        }
                        if (existingIds.isEmpty()) {
                            persistImportedSecrets(imported)
                        } else {
                            pendingReplace = PendingReplaceSecrets(
                                title = "Replace existing secret(s)?",
                                conflictIds = existingIds,
                                showSkip = imported.size > existingIds.size,
                                onResult = { result ->
                                    when (result) {
                                        ConfirmReplaceResult.REPLACE -> persistImportedSecrets(imported)
                                        ConfirmReplaceResult.SKIP -> {
                                            val existingSet = existingIds.toSet()
                                            persistImportedSecrets(imported.filterNot { it.id in existingSet })
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
                        is SecretImporter.PasswordRequired -> {
                            pendingImportText = t.pendingBlob
                            pendingImportAwaitingPassword = true
                            // Keep dialog open so user enters pwd.
                        }
                        else -> {
                            log.warn("Import secrets failed", t)
                            error = "Import failed: ${t.message}"
                        }
                    }
                }
            }
        }

        AppShell(
            title = "My Secrets",
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
                            if (loading) "Loading…" else "${secrets.size} secret(s)",
                            style = MaterialTheme.typography.subtitle1,
                        )
                        Box {
                            SafeButton(
                                enabled = !busy,
                                onClick = { showCreateMenu = true },
                            ) {
                                Text("Add secret")
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showCreateMenu,
                                onDismissRequest = { showCreateMenu = false },
                            ) {
                                DropdownMenuItem(onClick = {
                                    showCreateMenu = false
                                    creating = true
                                }) { Text("Create new…") }
                                DropdownMenuItem(onClick = {
                                    showCreateMenu = false
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
                                onClick = { exportResult = null; showExport = true },
                            ) { Text("Export") }
                            Spacer(Modifier.width(8.dp))
                            LongPressSecretDeleteButton(
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
                    secrets.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No secrets yet.", style = MaterialTheme.typography.subtitle1)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Use \"Add secret\" → Create new, or Import from backup.",
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
                                items(secrets, key = { it.id }) { s ->
                                    SecretCard(
                                        secret = s,
                                        selected = s.id in selectedIds,
                                        onToggleSelection = {
                                            selectedIds = if (s.id in selectedIds)
                                                selectedIds - s.id
                                            else selectedIds + s.id
                                        },
                                        onReveal = { revealSecret(s) },
                                        onEdit = { startUpdate(s) },
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

        reveal?.let { (sec, content) ->
            SecretRevealDialog(
                title = sec.title ?: "(no title)",
                type = sec.type,
                content = content,
                onDismiss = { reveal = null },
            )
        }

        if (creating) {
            SecretEditorDialog(
                title = "Create secret",
                existing = null,
                busy = busy,
                onSubmit = ::submitNew,
                onDismiss = { if (!busy) creating = false },
            )
        }

        updating?.let { (target, inputs) ->
            SecretEditorDialog(
                title = "Update secret",
                existing = inputs,
                busy = busy,
                onSubmit = { submitUpdate(target, it) },
                onDismiss = { if (!busy) updating = null },
            )
        }

        if (showExport) {
            ExportSecretsDialog(
                selectedCount = selectedIds.size,
                busy = busy,
                result = exportResult,
                onSubmit = { mode, pwdChars -> runExport(mode, pwdChars) },
                onDismiss = {
                    if (!busy) {
                        showExport = false
                        exportResult = null
                    }
                },
            )
        }

        if (showImport) {
            ImportSecretsDialog(
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

private data class PendingReplaceSecrets(
    val title: String,
    val conflictIds: List<String>,
    val showSkip: Boolean,
    val onResult: (ConfirmReplaceResult) -> Unit,
)

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

@Composable
private fun SecretCard(
    secret: DesktopSecret,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onReveal: () -> Unit,
    onEdit: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onReveal() }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = secret.title?.ifBlank { null } ?: "(no title)",
                    style = MaterialTheme.typography.subtitle1,
                    color = if (secret.title.isNullOrBlank())
                        MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    else MaterialTheme.colors.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!secret.type.isNullOrBlank()) {
                        Text(
                            secret.type ?: "",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = if (secret.savedAt > 0) dateFmt.format(Date(secret.savedAt)) else "",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    )
                }
                if (!secret.memo.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        secret.memo ?: "",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
            TextButton(onClick = onEdit) { Text("Edit") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressSecretDeleteButton(
    enabled: Boolean,
    selectedCount: Int,
    onConfirmed: () -> Unit,
) {
    // Same long-press confirm pattern as MyKeysScreen's delete — quick
    // taps just surface a hint, long press commits. Destructive and
    // non-undoable, so never fire on the first click.
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
        androidx.compose.material.DropdownMenu(
            expanded = showTip,
            onDismissRequest = { showTip = false },
        ) {
            androidx.compose.material.DropdownMenuItem(onClick = { showTip = false }) {
                Text("Long-press to confirm delete")
            }
        }
    }
}
