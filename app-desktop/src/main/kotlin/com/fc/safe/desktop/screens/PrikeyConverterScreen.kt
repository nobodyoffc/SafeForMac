package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.SafeButton
import core.crypto.KeyTools
import utils.Hex

/**
 * Accepts a private key in one of {mnemonic, hex, WIF} and emits it
 * in a user-chosen target format. Mirrors Android's
 * `PrikeyConverterActivity`. Radio output (one format at a time)
 * rather than show-all on purpose — a raw prikey in every form
 * simultaneously is more exposure than most callers need.
 *
 * Wallet-key integration is deferred: to convert a stored wallet
 * key, use the prikey reveal flow in My Keys (password-gated),
 * copy the hex, and paste it here. Adding a direct picker would
 * require a second password prompt here, which isn't worth the
 * extra surface area for v1.
 */
private enum class PrikeyTarget(val display: String) {
    HEX("Hex (32 bytes)"),
    WIF_COMPRESSED("WIF compressed"),
    WIF_LEGACY("WIF legacy (uncompressed)"),
    MNEMONIC("Mnemonic (12 words)"),
}

class PrikeyConverterScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()

        var input by remember { mutableStateOf("") }
        var target by remember { mutableStateOf(PrikeyTarget.WIF_COMPRESSED) }
        var result by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        fun convert() {
            error = null
            result = ""
            val key = input.trim()
            if (key.isEmpty()) return
            try {
                // Mnemonic → bytes takes priority when input looks like 12/24 words.
                val words = key.split(Regex("\\s+"))
                val bytes: ByteArray = if (words.size == 12 || words.size == 24) {
                    runCatching { KeyTools.mnemonicToBytes(key) }.getOrNull()
                        ?: KeyTools.getPrikey32(key)
                        ?: run { error = "Not a valid mnemonic or key"; return }
                } else {
                    KeyTools.getPrikey32(key)
                        ?: run { error = "Not a valid hex/WIF private key"; return }
                }
                val hex = Hex.toHex(bytes)
                result = when (target) {
                    PrikeyTarget.HEX -> hex
                    PrikeyTarget.WIF_COMPRESSED -> KeyTools.prikey32To38WifCompressed(hex)
                    PrikeyTarget.WIF_LEGACY -> KeyTools.prikey32To37(hex)
                    PrikeyTarget.MNEMONIC -> KeyTools.bytesToMnemonic(bytes)
                }
            } catch (t: Throwable) {
                error = "Convert failed: ${t.message}"
                result = ""
            }
        }

        AppShell(
            title = "Private key converter",
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
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = "Private key input",
                    placeholder = "Mnemonic (12 / 24 words), 64-char hex, or WIF",
                    heightDp = 120,
                    onClear = { input = ""; result = ""; error = null },
                )

                Spacer(Modifier.height(8.dp))
                Text("Output format", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(4.dp))
                PrikeyTarget.entries.forEach { t ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = target == t, onClick = { target = t })
                        Text(t.display, style = MaterialTheme.typography.body2)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(onClick = {
                        input = ""; result = ""; error = null
                    }) { Text("Clear") }
                    SafeButton(
                        enabled = input.isNotBlank(),
                        onClick = ::convert,
                    ) { Text("Convert") }
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
                    label = target.display,
                    readOnly = true,
                    heightDp = 120,
                )
            }
        }
    }
}
