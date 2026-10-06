package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
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
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.KeyPickerDialog
import com.fc.safe.desktop.ui.KeyPickerFilter
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.WalletSession
import core.crypto.KeyTools
import data.fcData.AlgorithmId
import data.fcData.Signature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import utils.Hex

private val log = LoggerFactory.getLogger("SignMsgScreen")

private enum class SignAlg(val display: String, val algId: AlgorithmId, val wantsPrikey: Boolean) {
    ECDSA("ECDSA", AlgorithmId.BTC_EcdsaSignMsg_No1_NrC7, true),
    SCHNORR("Schnorr", AlgorithmId.FC_SchnorrSignMsg_No1_NrC7, true),
    SYMKEY("Symkey (HMAC-SHA256)", AlgorithmId.FC_Sha256SymSignMsg_No1_NrC7, false),
}

class SignMsgScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var msg by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var pickedKey by remember { mutableStateOf<DesktopKeyInfo?>(null) }
        var alg by remember { mutableStateOf(SignAlg.ECDSA) }
        var result by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var showPicker by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        fun run() {
            error = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { signOne(msg, key, pickedKey, alg) }
                }
                busy = false
                outcome.onSuccess { result = it }
                outcome.onFailure {
                    log.warn("Sign failed", it)
                    error = it.message ?: "Sign failed"
                    result = ""
                }
            }
        }

        AppShell(
            title = "Sign message",
            scaffoldState = scaffoldState,
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CryptoIoBlock(
                    value = msg,
                    onValueChange = { msg = it; error = null },
                    label = "Message",
                    placeholder = "Text to sign",
                    heightDp = 140,
                    onClear = { msg = "" },
                    enableQr = true,
                    onQrError = { error = it },
                )

                Text("Algorithm", style = MaterialTheme.typography.subtitle2)
                SignAlg.entries.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = alg == a, onClick = {
                            alg = a
                            pickedKey = null
                            key = ""
                            error = null
                        })
                        Text(a.display, style = MaterialTheme.typography.body2)
                    }
                }
                Spacer(Modifier.height(8.dp))

                CryptoIoBlock(
                    value = if (pickedKey != null) "[wallet key: ${pickedKey!!.id}]" else key,
                    onValueChange = { key = it; error = null },
                    label = if (alg.wantsPrikey) "Private key (hex) or wallet key" else "Symmetric key (64 hex)",
                    placeholder = if (alg.wantsPrikey)
                        "64 hex chars, or pick from wallet via the person icon"
                    else "64 hex chars",
                    singleLine = true,
                    enabled = pickedKey == null,
                    heightDp = 64,
                    password = false,
                    onClear = { key = ""; pickedKey = null },
                    pickKey = if (alg.wantsPrikey) { { showPicker = true } } else null,
                    enableQr = true,
                    onQrError = { error = it },
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(onClick = {
                        msg = ""; key = ""; pickedKey = null; result = ""; error = null
                    }) { Text("Clear") }
                    SafeButton(
                        enabled = !busy && msg.isNotBlank() &&
                            (pickedKey != null || key.isNotBlank()),
                        onClick = ::run,
                    ) { Text("Sign") }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
                CryptoIoBlock(
                    value = result,
                    onValueChange = {},
                    label = "Signature JSON",
                    readOnly = true,
                    enableMakeQr = true,
                    heightDp = 220,
                )
            }
        }

        if (showPicker) {
            KeyPickerDialog(
                filter = KeyPickerFilter.WithPrikey,
                onPicked = {
                    pickedKey = it
                    key = ""
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
    }
}

private fun signOne(
    msg: String,
    typedKey: String,
    pickedKey: DesktopKeyInfo?,
    alg: SignAlg,
): String {
    val keyBytes = if (alg.wantsPrikey) {
        resolvePrikey(typedKey, pickedKey)
            ?: throw IllegalArgumentException("No usable private key")
    } else {
        if (!Hex.isHex32(typedKey))
            throw IllegalArgumentException("Symkey must be 64 hex chars")
        Hex.fromHex(typedKey)
    }
    try {
        val sig = Signature().apply {
            setMsg(msg)
            setKey(keyBytes)
            setAlg(alg.algId)
        }
        sig.sign() ?: throw IllegalStateException("Signing failed")
        return sig.toNiceJson() ?: throw IllegalStateException("Signing produced no JSON")
    } finally {
        if (alg.wantsPrikey) keyBytes.fill(0)
    }
}

private fun resolvePrikey(typedKey: String, pickedKey: DesktopKeyInfo?): ByteArray? {
    if (pickedKey != null) {
        val cipherJson = pickedKey.prikeyCipher ?: return null
        val raw = WalletSession.decryptFromJson(cipherJson)
        try {
            // Same use-after-wipe trap as DecryptScreen.resolvePrikey —
            // getPrikey32 returns the input array for 32-byte input, so
            // wiping `raw` would zero the returned key. Always copy.
            return KeyTools.getPrikey32(raw)?.copyOf()
        } finally {
            raw.fill(0)
        }
    }
    return KeyTools.getPrikey32(typedKey)
}
