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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
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
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.PrikeyRevealDialog
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.WalletSession
import constants.Constants as FcConstants
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

        fun revealPrikey(key: DesktopKeyInfo) {
            val cipher = key.prikeyCipher
            if (cipher == null) {
                error = "This key has no private key (watch-only or legacy record)."
                return
            }
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
                    AddNewKeyMenu(
                        enabled = !busy,
                        busy = busy,
                        onRandom = ::addRandomKey,
                        onByPhrase = { error = "Import by phrase — TODO" },
                        onByPrikey = { error = "Import by privkey — TODO" },
                        onByPrikeyCipher = { error = "Import by prikey cipher — TODO" },
                        onByPubkey = { error = "Import by pubkey — TODO" },
                        onByFid = { error = "Import by FID — TODO" },
                    )
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
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(keys, key = { it.id }) { k ->
                            KeyCard(
                                key = k,
                                onLabelChange = { newLabel -> updateLabel(k, newLabel) },
                                onRevealPrikey = { revealPrikey(k) },
                            )
                        }
                    }
                }
            }
        }

        revealPrikeyHex?.let { hex ->
            PrikeyRevealDialog(
                prikeyHex = hex,
                onDismiss = { revealPrikeyHex = null },
            )
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
        }
    }
}

@Composable
private fun KeyCard(
    key: DesktopKeyInfo,
    onLabelChange: (String) -> Unit,
    onRevealPrikey: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Title: FID (monospace)
            Text(
                text = key.id,
                style = MaterialTheme.typography.h6.copy(fontFamily = FontFamily.Monospace),
            )

            Spacer(Modifier.height(8.dp))
            EditableLabelRow(initialLabel = key.label, onSave = onLabelChange)

            Spacer(Modifier.height(4.dp))
            Text(
                "Created ${formatTs(key.savedAt)}",
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

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            MonoField(label = "Public key", value = key.pubkey)

            // prikeyCipher row with reveal action
            if (!key.prikeyCipher.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        MonoField(label = "Private key (cipher)", value = key.prikeyCipher)
                    }
                    IconButton(onClick = onRevealPrikey) {
                        Text(
                            text = "🔑",  // 🔑 — material-icons-core has no VpnKey
                            style = MaterialTheme.typography.h6,
                        )
                    }
                }
            }

            Divider(modifier = Modifier.padding(vertical = 12.dp))

            MonoField(label = "BTC", value = key.btcAddr)
            MonoField(label = "ETH", value = key.ethAddr)
            MonoField(label = "TRX", value = key.trxAddr)
            MonoField(label = "BCH", value = key.bchAddr)
            MonoField(label = "DOGE", value = key.dogeAddr)
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

@Composable
private fun MonoField(label: String, value: String?) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
        )
    }
    Spacer(Modifier.height(4.dp))
}

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

private fun formatTs(ms: Long): String =
    if (ms == 0L) "—" else dateFmt.format(Date(ms))

/**
 * Fresh random 256-bit key. Prikey is encrypted to a CryptoDataStr JSON
 * (with `data` field nulled) under the wallet password before the raw
 * bytes are wiped. Addresses are derived once here via
 * [KeyTools.pubkeyToAddresses] so the card has them on hand.
 */
private fun createRandomKey(): DesktopKeyInfo {
    val prikeyBytes = ByteArray(32).also(SecureRandom()::nextBytes)
    try {
        val pubkeyBytes = KeyTools.prikeyToPubkey(prikeyBytes)
        val pubkeyHex = Hex.toHex(pubkeyBytes)
        val fid = KeyTools.prikeyToFid(prikeyBytes)
        val prikeyCipher = WalletSession.encryptToJson(prikeyBytes)
        val addrs = KeyTools.pubkeyToAddresses(pubkeyHex) ?: emptyMap<String, String>()

        return DesktopKeyInfo().apply {
            setId(fid)
            this.pubkey = pubkeyHex
            this.prikeyCipher = prikeyCipher
            this.watchOnly = false
            this.label = null
            this.savedAt = System.currentTimeMillis()
            this.btcAddr = addrs[FcConstants.BTC_ADDR]
            this.ethAddr = addrs[FcConstants.ETH_ADDR]
            this.trxAddr = addrs[FcConstants.TRX_ADDR]
            this.bchAddr = addrs[FcConstants.BCH_ADDR]
            this.dogeAddr = addrs[FcConstants.DOGE_ADDR]
        }
    } finally {
        prikeyBytes.fill(0)
    }
}
