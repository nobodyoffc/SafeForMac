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
import com.fc.safe.desktop.backup.Base32Shim
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.SafeButton
import core.crypto.Base58
import utils.Hex
import java.util.Base64

/**
 * Decode input in a chosen format to bytes, then re-encode the bytes
 * in every other format. Matches Android's `DecodeActivity` — the
 * user pastes something they don't know the encoding of, picks a
 * source (or **Auto** to try in turn), and gets all five views of
 * the underlying bytes at once.
 *
 * Auto-detect order: Hex → Base58 → Base64 → Base32 → UTF-8 (same as
 * Android). The first encoding that produces a non-null byte array
 * wins.
 */
private enum class DecodeFormat(val display: String) {
    AUTO("Auto"),
    HEX("Hex"),
    BASE58("Base58"),
    BASE64("Base64"),
    BASE32("Base32"),
    UTF8("UTF-8"),
}

class DecodeScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()

        var input by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var format by remember { mutableStateOf(DecodeFormat.AUTO) }
        var error by remember { mutableStateOf<String?>(null) }

        fun run() {
            error = null
            result = ""
            if (input.isBlank()) return
            val trimmed = input.trim()
            val (bytes, usedFormat) = decodeTrying(trimmed, format) ?: run {
                error = "Couldn't decode as ${format.display}"
                return
            }
            result = buildString {
                append("Decoded as ${usedFormat.display}:\n\n")
                append("Hex: ").append(Hex.toHex(bytes)).append("\n\n")
                append("Base58: ").append(Base58.encode(bytes)).append("\n\n")
                append("Base64: ").append(Base64.getEncoder().encodeToString(bytes)).append("\n\n")
                append("Base32: ").append(Base32Shim.toBase32(bytes)).append("\n\n")
                append("UTF-8: ").append(String(bytes, Charsets.UTF_8))
            }
        }

        AppShell(
            title = "String decoder",
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
                    label = "Encoded input",
                    placeholder = "Paste hex, Base58, Base64, Base32, or plain UTF-8",
                    heightDp = 140,
                    onClear = { input = ""; result = ""; error = null },
                    enableQr = true,
                    onQrError = { error = it },
                )

                Spacer(Modifier.height(8.dp))
                Text("Source format", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(4.dp))
                DecodeFormat.entries.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.forEach { f ->
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = format == f, onClick = { format = f })
                                Text(f.display, style = MaterialTheme.typography.body2)
                            }
                        }
                        // Pad short rows so widths stay even
                        repeat(3 - row.size) {
                            Row(modifier = Modifier.weight(1f)) {}
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
                        onClick = ::run,
                    ) { Text("Decode") }
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
                    label = "Decoded",
                    readOnly = true,
                    enableMakeQr = true,
                    heightDp = 260,
                )
            }
        }
    }
}

/**
 * Try to decode [input] using [picked]. For [DecodeFormat.AUTO] the
 * order is Hex → Base58 → Base64 → Base32 → UTF-8 (same order as
 * Android's `DecodeActivity`). Returns `(bytes, formatActuallyUsed)`
 * or `null` if every attempt failed.
 */
private fun decodeTrying(input: String, picked: DecodeFormat): Pair<ByteArray, DecodeFormat>? {
    if (picked != DecodeFormat.AUTO) {
        return tryDecode(input, picked)?.let { it to picked }
    }
    for (f in listOf(
        DecodeFormat.HEX, DecodeFormat.BASE58,
        DecodeFormat.BASE64, DecodeFormat.BASE32, DecodeFormat.UTF8,
    )) {
        val bytes = tryDecode(input, f) ?: continue
        return bytes to f
    }
    return null
}

private fun tryDecode(input: String, fmt: DecodeFormat): ByteArray? = runCatching {
    when (fmt) {
        DecodeFormat.HEX -> if (Hex.isHexString(input)) Hex.fromHex(input) else null
        DecodeFormat.BASE58 -> if (Base58.isBase58Encoded(input)) Base58.decode(input) else null
        DecodeFormat.BASE64 -> Base64.getDecoder().decode(input)
        DecodeFormat.BASE32 -> Base32Shim.fromBase32(input)
        DecodeFormat.UTF8 -> input.toByteArray(Charsets.UTF_8)
        DecodeFormat.AUTO -> null
    }
}.getOrNull()
