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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import core.crypto.CryptoDataByte
import core.crypto.Decryptor
import core.crypto.EncryptType
import core.crypto.KeyTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import utils.Hex
import java.util.Base64

private val log = LoggerFactory.getLogger("DecryptScreen")

class DecryptScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var cipher by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        // When a wallet-key is picked, holds the DesktopKeyInfo; we
        // resolve its prikey on-demand via WalletSession so we never
        // keep raw prikey bytes in UI state.
        var pickedKey by remember { mutableStateOf<DesktopKeyInfo?>(null) }
        var result by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var showPicker by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        fun run() {
            error = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { decryptOne(cipher, key, pickedKey) }
                }
                busy = false
                outcome.onSuccess { result = it }
                outcome.onFailure {
                    log.warn("Decrypt failed", it)
                    error = it.message ?: "Decrypt failed"
                    result = ""
                }
            }
        }

        AppShell(
            title = "Decrypt",
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
                    value = cipher,
                    onValueChange = { cipher = it; error = null },
                    label = "Cipher (CryptoDataStr JSON or base64 bundle)",
                    heightDp = 160,
                    onClear = { cipher = "" },
                    enableQr = true,
                    onQrError = { error = it },
                )

                CryptoIoBlock(
                    value = if (pickedKey != null) "[wallet key: ${pickedKey!!.id}]" else key,
                    onValueChange = { key = it; error = null },
                    label = "Key (password / symkey hex / prikey hex)",
                    placeholder = "Or click the person icon to pick a wallet key",
                    singleLine = true,
                    enabled = pickedKey == null,
                    heightDp = 64,
                    onClear = { key = ""; pickedKey = null },
                    pickKey = { showPicker = true },
                    enableQr = true,
                    onQrError = { error = it },
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(onClick = {
                        cipher = ""; key = ""; pickedKey = null; result = ""; error = null
                    }) { Text("Clear") }
                    SafeButton(
                        enabled = !busy && cipher.isNotBlank() &&
                            (pickedKey != null || key.isNotBlank()),
                        onClick = ::run,
                    ) { Text("Decrypt") }
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
                    label = "Plaintext",
                    readOnly = true,
                    enableMakeQr = true,
                    heightDp = 200,
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

/**
 * Parse + dispatch decryption. Mirrors Android `DecryptActivity.decrypt`:
 * - Accepts either a CryptoDataStr JSON or a base64 bundle for [cipher].
 * - Reads `EncryptType` off the parsed cdb to decide which Decryptor
 *   method to invoke.
 * - For asymmetric decrypt with a picked wallet key, resolves the raw
 *   prikey via [WalletSession] and wipes it immediately after use.
 */
private fun decryptOne(
    cipher: String,
    typedKey: String,
    pickedKey: DesktopKeyInfo?,
): String {
    val cdb = parseCipher(cipher)
        ?: throw IllegalArgumentException("Not a valid cipher (not JSON or base64 bundle)")
    val decryptor = Decryptor()

    when (cdb.type) {
        EncryptType.Password -> {
            val pwd = typedKey.toCharArray()
            try {
                val r = decryptor.decryptJsonByPassword(cdb.toJson(), pwd)
                return completeDecrypt(r)
            } finally { pwd.fill(Char.MIN_VALUE) }
        }
        EncryptType.Symkey -> {
            if (!Hex.isHex32(typedKey)) {
                throw IllegalArgumentException("Symkey must be 64 hex chars (32 bytes)")
            }
            val r = decryptor.decryptJsonBySymkey(cdb.toJson(), Hex.fromHex(typedKey))
            return completeDecrypt(r)
        }
        EncryptType.AsyOneWay, EncryptType.AsyTwoWay -> {
            val prikey = resolvePrikey(typedKey, pickedKey)
                ?: throw IllegalArgumentException(
                    "Asymmetric cipher requires a private key (64 hex chars or a picked wallet key)"
                )
            try {
                cdb.prikeyB = prikey
                decryptor.decrypt(cdb)
                return completeDecrypt(cdb)
            } finally { prikey.fill(0) }
        }
        else -> throw IllegalStateException("Unsupported cipher type: ${cdb.type}")
    }
}

private fun parseCipher(cipher: String): CryptoDataByte? {
    val trimmed = cipher.trim()
    if (trimmed.startsWith("{")) {
        return runCatching { CryptoDataByte.fromJson(trimmed) }.getOrNull()
    }
    // Fall back to base64 bundle
    return runCatching {
        CryptoDataByte.fromBundle(Base64.getDecoder().decode(trimmed))
    }.getOrNull()
}

private fun completeDecrypt(cdb: CryptoDataByte): String {
    if (cdb.code != 0) throw IllegalStateException("Decrypt code=${cdb.code} msg=${cdb.message}")
    val data = cdb.data ?: throw IllegalStateException("Decrypt produced no bytes")
    return String(data, Charsets.UTF_8)
}

private fun resolvePrikey(typedKey: String, pickedKey: DesktopKeyInfo?): ByteArray? {
    if (pickedKey != null) {
        val cipherJson = pickedKey.prikeyCipher ?: return null
        val raw = WalletSession.decryptFromJson(cipherJson)
        try {
            // `KeyTools.getPrikey32(bytes)` returns the same array when
            // the input is already 32 bytes. Wiping `raw` afterwards
            // would zero the returned key too — that's exactly the
            // "Scalar is not in the interval [1, n-1]" bug (zero scalar
            // fails ECDH validation). Always return a distinct copy.
            return KeyTools.getPrikey32(raw)?.copyOf()
        } finally {
            raw.fill(0)
        }
    }
    return KeyTools.getPrikey32(typedKey)
}
