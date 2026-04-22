package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.DesktopKeyInfo
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.WalletSession
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

        AppShell(
            title = "My Keys",
            scaffoldState = scaffoldState,
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {

                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (loading) "Loading…" else "${keys.size} key(s)",
                        style = MaterialTheme.typography.subtitle1,
                    )
                    SafeButton(
                        enabled = !busy,
                        onClick = {
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
                        },
                    ) {
                        if (busy) CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colors.onPrimary,
                        ) else Text("Create random key")
                    }
                }

                error?.let {
                    Text(
                        it,
                        modifier = Modifier.fillMaxWidth().padding(horizontalPadding()),
                        color = MaterialTheme.colors.error,
                    )
                }

                Divider()

                when {
                    loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }

                    keys.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No keys yet.", style = MaterialTheme.typography.subtitle1)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Click \"Create random key\" to generate one.",
                                style = MaterialTheme.typography.caption,
                            )
                        }
                    }

                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(keys, key = { it.id }) { key -> KeyRow(key) }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyRow(key: DesktopKeyInfo) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                key.label ?: "(unlabeled)",
                style = MaterialTheme.typography.subtitle1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "FID: ${key.id}",
                style = MaterialTheme.typography.body2,
            )
            Text(
                "Created: ${formatTs(key.savedAt)}",
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
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

private fun horizontalPadding() = 16.dp

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTs(ms: Long): String =
    if (ms == 0L) "—" else dateFmt.format(Date(ms))

/**
 * Fresh random 256-bit key. FID (address) is derived via FC-JDK's KeyTools.
 * Private key is stored as hex in the DesktopKeyInfo; the row is itself
 * encrypted by SqliteDB's value-layer AES-GCM at rest.
 */
private fun createRandomKey(): DesktopKeyInfo {
    val prikeyBytes = ByteArray(32).also(SecureRandom()::nextBytes)
    val pubkeyBytes = KeyTools.prikeyToPubkey(prikeyBytes)
    val fid = KeyTools.prikeyToFid(prikeyBytes)
    val key = DesktopKeyInfo().apply {
        setId(fid)
        prikey = Hex.toHex(prikeyBytes)
        pubkey = Hex.toHex(pubkeyBytes)
        label = "Key ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}"
        watchOnly = false
        savedAt = System.currentTimeMillis()
    }
    prikeyBytes.fill(0)  // wipe local copy; the hex string in the entity is the persisted form
    return key
}
