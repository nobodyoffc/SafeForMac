package com.fc.safe.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Surface
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fc.safe.desktop.qr.QrDecode
import com.fc.safe.desktop.ui.CopyableField
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.QrChunks
import com.fc.safe.desktop.ui.QrShowPanel
import com.fc.safe.desktop.ui.encodeQrMatrix
import com.fc.safe.desktop.ui.qrMatrixToImage
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Checks the make-QR path end to end: split a payload, encode every
 * chunk, rasterise it the way "Save PNG…" does, and read it back with
 * the same decoder the scan-QR button uses. Concatenated chunks must
 * equal the original — that's the contract the receiving device relies
 * on, and nothing else in the app would catch a break in it.
 *
 * Also renders the on-screen shapes (a code at display size, a result
 * box with the icon, a detail row) to build/qr-make-smoke.png so the
 * layout can be eyeballed without clicking through the app.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    var failures = 0

    val cases = linkedMapOf(
        "FID" to "FEk41Kqjar45fLDriztUDTUkdki7dBAg5R",
        "prikey hex" to "a".repeat(64),
        "at the single-code limit" to "x".repeat(QrChunks.SINGLE_QR_MAX_BYTES),
        "one byte over the limit" to "x".repeat(QrChunks.SINGLE_QR_MAX_BYTES + 1),
        "signed tx JSON" to buildString {
            append("{\"txHex\":\"")
            repeat(60) { append("0123456789abcdef") }
            append("\",\"note\":\"multi-part payload\"}")
        },
        "unicode + emoji" to ("交易签名 ✅ ".repeat(80)),
    )

    for ((name, payload) in cases) {
        val chunks = QrChunks.split(payload)
        val rejoined = chunks.joinToString("")
        if (rejoined != payload) {
            println("FAIL [$name] split/rejoin mismatch")
            failures++
            continue
        }

        val oversized = chunks.filter {
            it.toByteArray(Charsets.UTF_8).size > QrChunks.CHUNK_MAX_BYTES
        }
        if (oversized.isNotEmpty()) {
            println("FAIL [$name] ${oversized.size} chunk(s) over ${QrChunks.CHUNK_MAX_BYTES} bytes")
            failures++
            continue
        }

        val decoded = StringBuilder()
        var decodeFailed = false
        for ((i, chunk) in chunks.withIndex()) {
            val matrix = encodeQrMatrix(chunk)
            if (matrix == null) {
                println("FAIL [$name] chunk ${i + 1}/${chunks.size} would not encode")
                decodeFailed = true
                break
            }
            val text = QrDecode.decode(qrMatrixToImage(matrix))
            if (text == null) {
                println("FAIL [$name] chunk ${i + 1}/${chunks.size} encoded but did not decode")
                decodeFailed = true
                break
            }
            decoded.append(text)
        }
        if (decodeFailed) {
            failures++
            continue
        }

        if (decoded.toString() != payload) {
            println("FAIL [$name] round-trip mismatch (${decoded.length} chars back, ${payload.length} in)")
            failures++
            continue
        }

        val bytes = payload.toByteArray(Charsets.UTF_8).size
        println("ok   [$name] $bytes bytes → ${chunks.size} code(s), round-tripped")
    }

    // Empty input yields no codes at all rather than an empty QR — the
    // dialog leans on this to stay off screen for a blank field.
    if (QrChunks.split("").isNotEmpty()) {
        println("FAIL [empty] expected no chunks")
        failures++
    } else {
        println("ok   [empty] no codes")
    }

    renderLayout(File("build/qr-make-smoke.png"))

    if (failures > 0) {
        println("\n$failures check(s) failed")
        kotlin.system.exitProcess(1)
    }
    println("\nAll QR make checks passed")
}

@OptIn(ExperimentalComposeUiApi::class)
private fun renderLayout(out: File) {
    val scene = ImageComposeScene(width = 620, height = 940) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                CryptoIoBlock(
                    value = "0".repeat(160),
                    onValueChange = {},
                    label = "Cipher",
                    readOnly = true,
                    enableMakeQr = true,
                    heightDp = 100,
                )
                Spacer(Modifier.height(8.dp))
                CopyableField(label = "FID", value = "FEk41Kqjar45fLDriztUDTUkdki7dBAg5R")
                Spacer(Modifier.height(8.dp))
                // The dialog body itself, on a payload big enough to
                // need splitting, so the pager row is in frame.
                QrShowPanel(
                    text = "{\"txHex\":\"" + "0123456789abcdef".repeat(60) + "\"}",
                    title = "Signed tx",
                    onDismiss = {},
                )
            }
        }
    }
    out.parentFile?.mkdirs()
    try {
        val data = scene.render().encodeToData(EncodedImageFormat.PNG)
            ?: error("PNG encode failed")
        out.writeBytes(data.bytes)
    } finally {
        scene.close()
    }
    println("\nwrote ${out.absolutePath}")
}
