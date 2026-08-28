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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.fch.parseRawTxInfo
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.desktop.ui.TxPreview
import core.fch.RawTxInfo

/**
 * Read-only tx inspection screen. Paste a tx JSON, see the parsed
 * inputs / outputs / fee / OP_RETURN. No signing here — that lives
 * on [SignTxScreen]. Useful for verifying a transaction someone
 * else sent you before you decide whether to sign it.
 *
 * Accepts either `RawTxInfo` JSON (the canonical Safe shape) or the
 * CashScript-v1 (`RawTxForCs`) shape, via the tolerant
 * [parseRawTxInfo] helper.
 */
class ImportTxInfoScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val clipboard = LocalClipboardManager.current

        var jsonText by remember { mutableStateOf("") }
        var parsed by remember { mutableStateOf<RawTxInfo?>(null) }
        var error by remember { mutableStateOf<String?>(null) }

        fun tryParse() {
            error = null
            parsed = parseRawTxInfo(jsonText)
            if (parsed == null && jsonText.isNotBlank()) {
                error = "Failed to parse tx JSON (not RawTxInfo or RawTxForCs shape)"
            }
        }

        AppShell(
            title = "Import TX info",
            scaffoldState = scaffoldState,
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CryptoIoBlock(
                    value = jsonText,
                    onValueChange = {
                        jsonText = it
                        parsed = null
                        error = null
                    },
                    label = "Tx JSON",
                    placeholder = "Paste a RawTxInfo or RawTxForCs JSON (or use QR…)",
                    heightDp = 180,
                    onClear = {
                        jsonText = ""
                        parsed = null
                        error = null
                    },
                    enableQr = true,
                    onQrError = { error = it },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(
                        enabled = jsonText.isNotBlank(),
                        onClick = ::tryParse,
                    ) { Text("Parse") }
                    parsed?.let {
                        SafeButton(onClick = {
                            // Copy the canonical form — if the input was
                            // RawTxForCs, this normalises it to RawTxInfo
                            // with sender info embedded.
                            clipboard.setText(AnnotatedString(it.toJsonWithSenderInfo()))
                        }) { Text("Copy JSON") }
                    }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                parsed?.let { tx ->
                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))
                    TxPreview(tx)
                }
            }
        }
    }
}
