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
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.SafeButton
import core.crypto.KeyTools

/**
 * Given either a public key or any valid FCH-family address, derive
 * the corresponding address on every chain the shared hash160 is
 * expressible on — BTC, ETH, FCH, BCH, TRX, DOGE, LTC — and render
 * them all.
 *
 * Mirrors Android's `AddressConverterActivity`:
 * - Pubkey input → `KeyTools.pubkeyToAddresses(pubkey)` directly.
 * - Address input → `addrToHash160` → `hash160ToAddresses`. This
 *   lets you paste a BTC address and get the matching FCH / ETH /
 *   DOGE… addresses sharing the same hash160, which is handy for
 *   users moving funds across chains they own on the same key.
 */
class AddressConverterScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()

        var input by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }

        fun convert() {
            error = null
            result = ""
            val raw = input.trim()
            if (raw.isEmpty()) return
            try {
                val map: Map<String, String> = if (KeyTools.isPubkey(raw)) {
                    KeyTools.pubkeyToAddresses(raw)
                } else {
                    val hash160 = KeyTools.addrToHash160(raw)
                        ?: throw IllegalArgumentException("Not a recognisable address")
                    KeyTools.hash160ToAddresses(hash160)
                }
                result = map.entries.joinToString("\n\n") { "${it.key}: ${it.value}" }
            } catch (t: Throwable) {
                error = "Convert failed: ${t.message}"
            }
        }

        AppShell(
            title = "Address converter",
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
                    label = "Public key or address",
                    placeholder = "Compressed pubkey hex, or any chain's legacy address",
                    heightDp = 100,
                    onClear = { input = ""; result = ""; error = null },
                    enableQr = true,
                    onQrError = { error = it },
                )

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
                    label = "Derived addresses",
                    readOnly = true,
                    enableMakeQr = true,
                    heightDp = 260,
                )
            }
        }
    }
}
