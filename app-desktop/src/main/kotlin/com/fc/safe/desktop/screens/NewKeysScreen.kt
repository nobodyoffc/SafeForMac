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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.Checkbox
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.fc.safe.desktop.NewKeyCandidate
import com.fc.safe.desktop.NiceFidFinder
import com.fc.safe.desktop.buildKeyFromPrikey32
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.FidAvatar
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.WalletSession
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("NewKeysScreen")
private const val KEYS_DB_NAME = "keys"

/** How many keys one round makes or looks for, as on Android. */
private const val BATCH_SIZE = 10

/**
 * Offers freshly made keys to pick from, as Android Safe's Random Keys and
 * Find a Nice FID do. Random keys come ten at a time. With [findNice], keys
 * are made until ten have an FID ending in the text the user asked for.
 *
 * A candidate's private key stays in memory, unencrypted, until it is saved or
 * the screen goes away; only the ticked ones are ever written to the vault.
 */
class NewKeysScreen(private val findNice: Boolean) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()

        var candidates by remember { mutableStateOf<List<NewKeyCandidate>>(emptyList()) }
        var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
        var match by remember { mutableStateOf("") }
        var search by remember { mutableStateOf<Job?>(null) }
        var elapsedSeconds by remember { mutableStateOf(0L) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        val finding = search?.isActive == true
        val matchProblem = if (findNice && match.isNotEmpty()) NiceFidFinder.problemWith(match) else null

        fun addRandom() {
            candidates = candidates + List(BATCH_SIZE) { NewKeyCandidate.random() }
        }

        fun startFinding() {
            error = null
            elapsedSeconds = 0
            val suffix = match.trim()
            search = scope.launch {
                val timer = launch {
                    while (true) {
                        delay(1000)
                        elapsedSeconds++
                    }
                }
                try {
                    NiceFidFinder.find(suffix, BATCH_SIZE) { found -> candidates = candidates + found }
                } finally {
                    timer.cancel()
                    search = null
                }
            }
        }

        fun save() {
            val picked = candidates.filter { it.fid in selected }
            if (picked.isEmpty()) return
            error = null
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val db: LocalDB<DesktopKeyInfo> =
                            WalletSession.openDb(KEYS_DB_NAME, DesktopKeyInfo::class.java)
                        picked.forEach {
                            // The builder wipes what it is given; the candidate's
                            // own copy is wiped when the screen goes away.
                            val key = buildKeyFromPrikey32(it.prikey32.copyOf(), label = null)
                            db.put(key.id, key)
                        }
                        db.commit()
                    }
                    navigator.pop()
                } catch (t: Throwable) {
                    log.warn("Save new keys failed", t)
                    error = "Save failed: ${t.message}"
                    saving = false
                }
            }
        }

        if (!findNice) LaunchedEffect(Unit) { addRandom() }

        // Runs on leaving, and on an auto-lock, which replaces the screen.
        val held = remember { mutableStateOf<List<NewKeyCandidate>>(emptyList()) }
        held.value = candidates
        DisposableEffect(Unit) {
            onDispose { held.value.forEach { it.prikey32.fill(0) } }
        }

        AppShell(
            title = if (findNice) "Find a nice FID" else "Random keys",
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    if (findNice) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = match,
                                onValueChange = { match = it.trim() },
                                label = { Text("FID ends with") },
                                enabled = !finding,
                                singleLine = true,
                                isError = matchProblem != null,
                                textStyle = MaterialTheme.typography.body1.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(formatElapsed(elapsedSeconds), style = MaterialTheme.typography.h6)
                        }
                        Text(
                            matchProblem ?: ("Upper and lower case count as the same. Each added character " +
                                "takes about 58 times longer. The wallet still auto-locks when idle, and a " +
                                "lock ends the search and drops the keys found but not saved."),
                            color = if (matchProblem != null) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
                            style = MaterialTheme.typography.caption,
                        )
                        Spacer(Modifier.padding(top = 8.dp))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (selected.isEmpty()) "Tick the keys to keep" else "${selected.size} selected",
                            style = MaterialTheme.typography.subtitle1,
                        )
                        Row {
                            if (findNice) {
                                SafeButton(
                                    enabled = !saving && !finding && match.isNotEmpty() && matchProblem == null,
                                    onClick = ::startFinding,
                                ) { Text("Start") }
                                Spacer(Modifier.width(8.dp))
                                SafeButton(enabled = finding, onClick = { search?.cancel() }) { Text("Stop") }
                            } else {
                                SafeButton(enabled = !saving, onClick = ::addRandom) { Text("More") }
                            }
                            Spacer(Modifier.width(8.dp))
                            SafeButton(
                                enabled = !saving && !finding && selected.isNotEmpty(),
                                onClick = ::save,
                            ) { Text(if (saving) "Saving…" else "Save") }
                        }
                    }
                    error?.let { Text(it, color = MaterialTheme.colors.error) }
                }

                Divider()

                val listState = rememberLazyListState()
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(candidates, key = { it.fid }) { c ->
                            val toggle = {
                                if (!saving) selected = if (c.fid in selected) selected - c.fid else selected + c.fid
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().clickable(onClick = toggle)
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Checkbox(checked = c.fid in selected, onCheckedChange = { toggle() }, enabled = !saving)
                                Spacer(Modifier.width(8.dp))
                                FidAvatar(c.fid)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    c.fid,
                                    style = MaterialTheme.typography.body1.copy(fontFamily = FontFamily.Monospace),
                                )
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun formatElapsed(seconds: Long): String =
    "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
