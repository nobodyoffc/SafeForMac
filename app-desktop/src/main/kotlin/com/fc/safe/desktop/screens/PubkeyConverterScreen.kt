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
 * Accept a 33-byte compressed pubkey (hex or WIF variant) and render
 * every derived form at once: FID, uncompressed hex, and the three
 * WIF variants. Show-all rather than radio — pubkeys are public
 * data, so extra forms don't widen the attack surface, and users
 * typically want to inspect every variant when debugging.
 *
 * Mirrors Android's `PubkeyConverterActivity` 1:1 — same five
 * output fields, same FC-JDK helpers.
 */
class PubkeyConverterScreen : Screen {
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
            val pubkey33 = KeyTools.getPubkey33(raw) ?: run {
                error = "Not a valid public key"
                return
            }
            result = buildString {
                append("FID: ").append(KeyTools.pubkeyToFchAddr(pubkey33)).append("\n\n")
                append("Uncompressed hex: ").append(KeyTools.recoverPK33ToPK65(pubkey33)).append("\n\n")
                append("WIF uncompressed: ").append(KeyTools.getPubkeyWifUncompressed(pubkey33)).append("\n\n")
                append("WIF compressed (ver 0): ")
                    .append(KeyTools.getPubkeyWifCompressedWithVer0(pubkey33)).append("\n\n")
                append("WIF compressed (no ver): ")
                    .append(KeyTools.getPubkeyWifCompressedWithoutVer(pubkey33))
            }
        }

        AppShell(
            title = "Public key converter",
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
                    label = "Public key input",
                    placeholder = "66-char compressed hex, 130-char uncompressed hex, or WIF",
                    heightDp = 120,
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
                    label = "Derived forms",
                    readOnly = true,
                    enableMakeQr = true,
                    heightDp = 240,
                )
            }
        }
    }
}
