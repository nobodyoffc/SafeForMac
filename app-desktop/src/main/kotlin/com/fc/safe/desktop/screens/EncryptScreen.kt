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
import com.fc.safe.desktop.ui.KeyPickerDialog
import com.fc.safe.desktop.ui.KeyPickerFilter
import com.fc.safe.desktop.ui.SafeButton
import core.crypto.Encryptor
import core.crypto.KeyTools
import data.fcData.AlgorithmId
import utils.Hex

private enum class EncryptMode(val label: String, val keyHint: String) {
    PASSWORD("By password", "Arbitrary password text"),
    SYMKEY("By symkey (hex32)", "64 hex chars (32 bytes)"),
    PUBKEY("By pubkey (hex)", "Compressed (66 hex) or uncompressed (130 hex) pubkey"),
}

class EncryptScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()

        var text by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var mode by remember { mutableStateOf(EncryptMode.PASSWORD) }
        var showPicker by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        AppShell(
            title = "Encrypt",
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
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = "Plaintext",
                    placeholder = "Text to encrypt",
                    heightDp = 140,
                    onClear = { text = "" },
                    enableQr = true,
                    onQrError = { error = it },
                )

                Text("Key type", style = MaterialTheme.typography.subtitle2)
                EncryptMode.entries.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = mode == m, onClick = { mode = m; error = null })
                        Text(m.label, style = MaterialTheme.typography.body2)
                    }
                }
                Spacer(Modifier.height(8.dp))

                CryptoIoBlock(
                    value = key,
                    onValueChange = { key = it; error = null },
                    label = when (mode) {
                        EncryptMode.PASSWORD -> "Password"
                        EncryptMode.SYMKEY -> "Symmetric key (hex)"
                        EncryptMode.PUBKEY -> "Public key (hex)"
                    },
                    placeholder = mode.keyHint,
                    singleLine = true,
                    heightDp = 64,
                    password = mode == EncryptMode.PASSWORD,
                    onClear = { key = "" },
                    pickKey = if (mode == EncryptMode.PUBKEY) {
                        { showPicker = true }
                    } else null,
                    enableQr = true,
                    onQrError = { error = it },
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(onClick = {
                        text = ""; key = ""; result = ""; error = null
                    }) { Text("Clear") }
                    SafeButton(
                        enabled = text.isNotBlank() && key.isNotBlank(),
                        onClick = {
                            error = null
                            result = try {
                                encryptOne(text, key, mode)
                            } catch (t: Throwable) {
                                error = t.message ?: "Encrypt failed"
                                ""
                            }
                        },
                    ) { Text("Encrypt") }
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
                    label = "Cipher (CryptoDataStr JSON)",
                    readOnly = true,
                    enableMakeQr = true,
                    heightDp = 240,
                )
            }
        }

        if (showPicker) {
            KeyPickerDialog(
                filter = KeyPickerFilter.WithPubkey,
                onPicked = { picked ->
                    picked.pubkey?.let { key = it }
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
    }
}

private fun encryptOne(text: String, keyStr: String, mode: EncryptMode): String {
    val msgBytes = text.toByteArray(Charsets.UTF_8)
    // AEAD family: FC_AesGcm256 / FC_EccK1AesGcm256. Built-in auth
    // tag, no separate sum field. Replaces the legacy CBC + sum4
    // pairing.
    val cdb = when (mode) {
        EncryptMode.PASSWORD ->
            Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
                .encryptByPassword(msgBytes, keyStr.toCharArray())
        EncryptMode.SYMKEY -> {
            if (!Hex.isHex32(keyStr)) throw IllegalArgumentException("Symkey must be 64 hex chars (32 bytes)")
            Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
                .encryptBySymkey(msgBytes, Hex.fromHex(keyStr))
        }
        EncryptMode.PUBKEY -> {
            if (!KeyTools.isPubkey(keyStr)) throw IllegalArgumentException("Not a valid public key")
            Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7)
                .encryptByAsyOneWay(msgBytes, Hex.fromHex(keyStr))
        }
    }
    return cdb.toNiceJson() ?: throw IllegalStateException("Serialisation failed")
}
