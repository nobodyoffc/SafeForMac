package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
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
import core.crypto.Hash
import java.security.MessageDigest
import utils.Hex

private enum class HashAlgo(val display: String) {
    SHA256("SHA-256"),
    SHA256x2("SHA-256 ×2"),
    SHA1("SHA-1"),
    MD5("MD5"),
    RIPEMD160("RIPEMD-160"),
    SHA3("SHA-3"),
}

private fun HashAlgo.compute(input: ByteArray): String = when (this) {
    HashAlgo.SHA256 -> Hex.toHex(Hash.sha256(input))
    HashAlgo.SHA256x2 -> Hex.toHex(Hash.sha256x2(input))
    // FC-JDK's Hash lacks SHA-1 and MD5 (Android FC-AJDK has them); use
    // the JVM's MessageDigest instead. Output bytes match Android.
    HashAlgo.SHA1 -> Hex.toHex(MessageDigest.getInstance("SHA-1").digest(input))
    HashAlgo.MD5 -> Hex.toHex(MessageDigest.getInstance("MD5").digest(input))
    HashAlgo.RIPEMD160 -> Hex.toHex(Hash.Ripemd160(input))
    // sha3String takes a hex input; feed it hex-encoded bytes for parity
    // with Android's HashActivity.applyHashAlgorithm.
    HashAlgo.SHA3 -> Hash.sha3String(Hex.toHex(input))
}

class HashScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()

        var input by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var algo by remember { mutableStateOf(HashAlgo.SHA256) }
        var asHex by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        AppShell(
            title = "Hash",
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
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = "Text to hash",
                    placeholder = "Type or paste; toggle \"as hex\" to hash the hex-decoded bytes",
                    heightDp = 140,
                    onClear = { input = ""; result = "" },
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = asHex, onCheckedChange = { asHex = it })
                    Text("Treat input as hex bytes", style = MaterialTheme.typography.body2)
                }

                Spacer(Modifier.height(8.dp))
                Text("Algorithm", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(4.dp))
                // Two-column grid, same layout as Android's two-row arrangement.
                HashAlgo.entries.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.forEach { a ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = algo == a, onClick = { algo = a })
                                Text(a.display, style = MaterialTheme.typography.body2)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(onClick = {
                        input = ""; result = ""; error = null
                    }) { Text("Clear") }
                    SafeButton(
                        enabled = input.isNotBlank(),
                        onClick = {
                            error = null
                            result = try {
                                val bytes = if (asHex) {
                                    runCatching { Hex.fromHex(input.trim()) }.getOrNull()
                                        ?: run {
                                            error = "Input is not valid hex"
                                            return@SafeButton
                                        }
                                } else {
                                    input.toByteArray(Charsets.UTF_8)
                                }
                                algo.compute(bytes)
                            } catch (t: Throwable) {
                                error = "Hash failed: ${t.message}"
                                ""
                            }
                        },
                    ) { Text("Hash") }
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
                    label = "Hash (hex)",
                    readOnly = true,
                    heightDp = 80,
                )
            }
        }
    }
}
