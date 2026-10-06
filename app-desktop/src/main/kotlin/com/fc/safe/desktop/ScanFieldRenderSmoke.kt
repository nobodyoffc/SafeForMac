package com.fc.safe.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.QrScanIconButton
import com.fc.safe.desktop.ui.QrScanTrailingIcon
import com.fc.safe.desktop.ui.ScanTextField
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders the scan-QR affordances off-screen to a PNG so the layout can
 * be eyeballed without launching the app and clicking through to every
 * dialog. Covers the three shapes in use: a [ScanTextField] (trailing
 * icon), a bare [QrScanIconButton] in an action row under a tall field,
 * and [CryptoIoBlock] with `enableQr`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val scene = ImageComposeScene(width = 600, height = 900) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                ScanTextField(
                    value = "FEk41Kqjar45fLDriztUDTUkdki7dBAg5R",
                    onValueChange = {},
                    label = "Recipient FID",
                    monospace = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                ScanTextField(
                    value = "",
                    onValueChange = {},
                    label = "Tx ID (64 hex)",
                    placeholder = "64 hex chars",
                    monospace = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = "{\n  \"secret\": \"…\",\n  \"label\": \"…\"\n}",
                    onValueChange = {},
                    label = { Text("Backup JSON") },
                    trailingIcon = {
                        QrScanTrailingIcon(onDecoded = {}, tooltip = "Scan a backup QR code")
                    },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                )
                Spacer(Modifier.height(8.dp))
                // Multisig member row: scan sits alongside the existing
                // pick-from-keys and clear icons, so check three icon
                // buttons still leave a usable pubkey field.
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = "0345c39bd9a2cd4c8f7d1e6a2b9c0f3e5d7a8b1c2d3e4f5061728394a5b6c7d8e9",
                        onValueChange = {},
                        label = { Text("#1") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    QrScanIconButton(onDecoded = {}, tooltip = "Scan member #1's public key")
                    androidx.compose.material.IconButton(onClick = {}) {
                        androidx.compose.material.Icon(
                            Icons.Filled.Person,
                            contentDescription = "Pick from keys",
                        )
                    }
                    androidx.compose.material.IconButton(onClick = {}) {
                        androidx.compose.material.Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear",
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                CryptoIoBlock(
                    value = "hello on-chain",
                    onValueChange = {},
                    label = "OP_RETURN / carving (optional)",
                    heightDp = 100,
                    onClear = {},
                    enableQr = true,
                )
                CryptoIoBlock(
                    value = "FEk41Kqjar45fLDriztUDTUkdki7dBAg5R",
                    onValueChange = {},
                    label = "Sender FID (auto-filled from first input's owner)",
                    singleLine = true,
                    heightDp = 64,
                    onClear = {},
                    enableQr = true,
                )
                CryptoIoBlock(
                    value = "{\n  \"alg\": \"EccAes256K1P7\",\n  \"cipher\": \"…\"\n}",
                    onValueChange = {},
                    label = "Cipher (CryptoDataStr JSON or base64 bundle)",
                    heightDp = 160,
                    onClear = {},
                    enableQr = true,
                )
            }
        }
    }

    val out = File("build/scan-field-smoke.png")
    out.parentFile.mkdirs()
    try {
        val data = scene.render().encodeToData(EncodedImageFormat.PNG)
            ?: error("PNG encode failed")
        out.writeBytes(data.bytes)
    } finally {
        scene.close()
    }
    println("wrote ${out.absolutePath}")
}
