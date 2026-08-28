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
import androidx.compose.material.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.DesktopSecret
import com.fc.safe.desktop.DesktopSecretType
import com.fc.safe.desktop.backup.Base32Shim
import com.fc.safe.desktop.totp.TotpExportFormat
import com.fc.safe.desktop.totp.TotpExporter
import com.fc.safe.desktop.totp.TotpUtil
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.desktop.ui.TotpExportDialog
import com.fc.safe.desktop.ui.TotpImportDialog
import com.fc.safe.platform.macos.WalletSession
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val log = LoggerFactory.getLogger("TotpScreen")
private const val SECRETS_DB_NAME = "secrets"

/**
 * List of TOTP-typed secrets with live 6-digit codes and a
 * 30-second countdown. Seeds are decrypted once on load and cached
 * in memory (Argon2 is ~500ms/call — re-decrypting each second
 * would make the screen unusable). The cache lifetime is the
 * screen's composition; closing the screen discards the cached
 * seeds — caller re-decrypts on next entry.
 *
 * Ticks every 1s via `LaunchedEffect` + `delay(1000)`. Codes
 * recompute cheaply every tick; countdown is a number + linear
 * progress bar.
 */
class TotpScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()
        val clipboard = LocalClipboardManager.current

        // Loaded TOTP secret records + decoded seed cache keyed by id.
        var secrets by remember { mutableStateOf<List<DesktopSecret>>(emptyList()) }
        var seeds by remember { mutableStateOf<Map<String, ByteArray>>(emptyMap()) }
        var loading by remember { mutableStateOf(true) }
        var error by remember { mutableStateOf<String?>(null) }
        var showImport by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
        var showExport by remember { mutableStateOf(false) }
        var exportResult by remember { mutableStateOf<String?>(null) }

        // Wall-clock that drives the countdown. Incremented every 1s;
        // triggers recomposition of all cards.
        var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

        suspend fun load() {
            loading = true
            try {
                val (list, decoded) = withContext(Dispatchers.IO) {
                    val db: LocalDB<DesktopSecret> =
                        WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                    val all = db.all.values.toList()
                        .filter { it.type.equals(DesktopSecretType.TOTP.tag, ignoreCase = true) }
                        .sortedBy { it.title?.lowercase() ?: "" }
                    // Decrypt each seed once. A broken cipher or a
                    // non-Base32 content yields an empty seed; the
                    // card will render "------" for that row.
                    val seedMap = HashMap<String, ByteArray>(all.size)
                    for (s in all) {
                        val cipher = s.contentCipher ?: continue
                        runCatching {
                            val raw = WalletSession.decryptFromJson(cipher)
                            val text = String(raw, Charsets.UTF_8)
                            raw.fill(0)
                            val seed = Base32Shim.fromBase32(text)
                            if (seed.isNotEmpty()) seedMap[s.id] = seed
                        }
                    }
                    all to seedMap
                }
                secrets = list
                seeds = decoded
                loading = false
            } catch (t: Throwable) {
                log.warn("Load TOTP failed", t)
                error = "Load failed: ${t.message}"
                loading = false
            }
        }

        LaunchedEffect(Unit) { load() }

        // Tick every second. Kept separate from the load coroutine so
        // load errors don't stop the clock.
        LaunchedEffect(Unit) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(1000)
            }
        }

        fun deleteOne(sec: DesktopSecret) {
            error = null
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopSecret> =
                            WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                        db.remove(sec.id)
                        db.commit()
                    }
                    // Wipe the cached seed too.
                    seeds[sec.id]?.fill(0)
                    seeds = seeds - sec.id
                    secrets = secrets.filter { it.id != sec.id }
                } catch (t: Throwable) {
                    log.warn("Delete TOTP failed", t)
                    error = "Delete failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun saveImported(title: String, base32Seed: String) {
            error = null
            busy = true
            scope.launch {
                try {
                    // Validate the base32 decodes non-empty — otherwise
                    // we'd store a record that renders as "------".
                    val decoded = runCatching { Base32Shim.fromBase32(base32Seed) }.getOrNull()
                    if (decoded == null || decoded.isEmpty()) {
                        error = "Invalid Base32 seed"
                        busy = false
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        val cipher = WalletSession.encryptToJson(
                            base32Seed.toByteArray(Charsets.UTF_8)
                        )
                        val sec = DesktopSecret().apply {
                            assignId(title, base32Seed)
                            this.title = title
                            this.type = DesktopSecretType.TOTP.tag
                            this.contentCipher = cipher
                            this.savedAt = System.currentTimeMillis()
                        }
                        val db: LocalDB<DesktopSecret> =
                            WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                        db.put(sec.id, sec)
                        db.commit()
                    }
                    showImport = false
                    load()
                } catch (t: Throwable) {
                    log.warn("Save TOTP import failed", t)
                    error = "Save failed: ${t.message}"
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
                        val db: LocalDB<DesktopSecret> =
                            WalletSession.openDb(SECRETS_DB_NAME, DesktopSecret::class.java)
                        ids.forEach { id ->
                            db.remove(id)
                            seeds[id]?.fill(0)
                        }
                        db.commit()
                    }
                    seeds = seeds - ids
                    secrets = secrets.filter { it.id !in ids }
                    selectedIds = emptySet()
                } catch (t: Throwable) {
                    log.warn("Delete selected TOTP failed", t)
                    error = "Delete failed: ${t.message}"
                } finally {
                    busy = false
                }
            }
        }

        fun runExport(format: TotpExportFormat) {
            error = null
            // Seeds are already decoded in memory as raw bytes — for
            // export we want the original Base32 text (what other apps
            // parse), which we kept encrypted on disk and decrypt once
            // here. Re-decrypt on export to avoid holding the Base32
            // string in memory between screen ticks.
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching {
                        val picked = secrets.filter { it.id in selectedIds }
                        val seedByFid = buildMap {
                            for (s in picked) {
                                val cipher = s.contentCipher ?: continue
                                val raw = WalletSession.decryptFromJson(cipher)
                                val base32 = String(raw, Charsets.UTF_8)
                                raw.fill(0)
                                put(s.id, base32)
                            }
                        }
                        TotpExporter.export(picked, seedByFid, format)
                    }
                }
                busy = false
                outcome.onSuccess { exportResult = it }
                outcome.onFailure {
                    log.warn("TOTP export failed", it)
                    error = "Export failed: ${it.message}"
                }
            }
        }

        val wallClock = remember(nowMs) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nowMs))
        }
        val remaining = TotpUtil.secondsRemaining(nowMs / 1000)

        AppShell(
            title = "TOTP",
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
                                if (loading) "Loading…" else "${secrets.size} TOTP code(s)",
                                style = MaterialTheme.typography.subtitle1,
                            )
                            Text(
                                "$wallClock  ·  ${remaining}s to next",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        SafeButton(
                            enabled = !busy,
                            onClick = { showImport = true },
                        ) { Text("Import TOTP") }
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
                            LongPressTotpDeleteButton(
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
                            Text("No TOTP codes.", style = MaterialTheme.typography.subtitle1)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Import an otpauth:// URI or a {secret, label} JSON.",
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
                                    TotpCard(
                                        secret = s,
                                        seed = seeds[s.id],
                                        nowMs = nowMs,
                                        selected = s.id in selectedIds,
                                        onToggleSelection = {
                                            selectedIds = if (s.id in selectedIds)
                                                selectedIds - s.id
                                            else selectedIds + s.id
                                        },
                                        onCopy = { code ->
                                            clipboard.setText(AnnotatedString(code))
                                        },
                                        onDelete = { deleteOne(s) },
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

        if (showImport) {
            TotpImportDialog(
                busy = busy,
                onSubmit = { title, seed -> saveImported(title, seed) },
                onDismiss = { if (!busy) showImport = false },
            )
        }

        if (showExport) {
            TotpExportDialog(
                selectedCount = selectedIds.size,
                result = exportResult,
                onSubmit = { runExport(it) },
                onDismiss = {
                    if (!busy) {
                        showExport = false
                        exportResult = null
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TotpCard(
    secret: DesktopSecret,
    seed: ByteArray?,
    nowMs: Long,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    onCopy: (String) -> Unit,
    onDelete: () -> Unit,
) {
    // "------" when we couldn't decode the seed (bad Base32, missing
    // cipher, etc). Card still renders so the user can delete it.
    val unixSeconds = nowMs / 1000
    val code = remember(seed, unixSeconds / TotpUtil.DEFAULT_STEP_SECONDS) {
        if (seed == null) "------" else runCatching {
            TotpUtil.generate(seed, unixSeconds)
        }.getOrDefault("------")
    }
    val remaining = TotpUtil.secondsRemaining(unixSeconds)
    val progress = remaining.toFloat() / TotpUtil.DEFAULT_STEP_SECONDS.toFloat()

    // Hidden by default so codes don't leak to shoulder-surfers on a
    // screen-share or photo. Click the card to toggle; Copy appears
    // only while revealed. State keyed on `secret.id` so navigating
    // off-and-on the screen re-hides.
    var revealed by remember(secret.id) { mutableStateOf(false) }
    var showDeleteTip by remember(secret.id) { mutableStateOf(false) }

    val display = if (revealed) code else "•".repeat(code.length)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = { revealed = !revealed },
                onLongClick = { showDeleteTip = true },
            ),
        elevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Checkbox is its own click target, independent of the
                // card's combinedClickable — click/long-press on the
                // card keep the reveal / delete semantics.
                Checkbox(checked = selected, onCheckedChange = { onToggleSelection() })
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = secret.title?.ifBlank { null } ?: "(no title)",
                        style = MaterialTheme.typography.subtitle1,
                    )
                    Text(
                        text = if (revealed)
                            "click to hide · long-press to delete"
                        else "click to reveal · long-press to delete",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                    )
                }
                Text(
                    text = display,
                    style = MaterialTheme.typography.h4.copy(fontFamily = FontFamily.Monospace),
                    color = if (remaining <= 5)
                        MaterialTheme.colors.error
                    else MaterialTheme.colors.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${remaining}s",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                color = if (remaining <= 5) MaterialTheme.colors.error
                else MaterialTheme.colors.primary,
            )
            if (revealed) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onCopy(code) }) { Text("Copy") }
                }
            }
        }
    }

    if (showDeleteTip) {
        // Simple confirm dialog — full-blown long-press-to-delete bar
        // feels wrong for a TOTP row (destructive but lower-risk than
        // a signing key). A one-step confirm is enough.
        androidx.compose.material.AlertDialog(
            onDismissRequest = { showDeleteTip = false },
            title = { Text("Delete TOTP?") },
            text = { Text("Remove \"${secret.title ?: secret.id}\" from this vault?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteTip = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteTip = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Multi-delete in the selection toolbar. Reuses the hold-to-confirm
 * pattern from MyKeys — quick tap shows a tip, long press commits.
 * More friction than the single-row AlertDialog because this one
 * can wipe many rows at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LongPressTotpDeleteButton(
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
