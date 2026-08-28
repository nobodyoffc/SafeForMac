package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import com.fc.safe.desktop.qr.QrDecode
import com.fc.safe.desktop.qr.ScanQrLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Shared I/O field used by the crypto-utility screens (Hash, Encrypt,
 * Decrypt, SignMsg, Verify). Replaces Android Safe's `IoIconsView` +
 * `TextInputLayout + scan/paste/copy icons` pattern.
 *
 * Caller decides which action icons appear via the `onXxx` callbacks:
 * a `null` callback → icon hidden. This keeps the block one flexible
 * composable instead of a family of similar ones.
 *
 * The optional [pickKey] icon (person glyph) is only meaningful on
 * key-input rows; it's wired by callers that want a "pick a wallet
 * key" dialog (SignMsg / Decrypt / Encrypt-by-pubkey).
 */
@Composable
fun CryptoIoBlock(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    monospace: Boolean = true,
    password: Boolean = false,
    heightDp: Int = 120,
    onClear: (() -> Unit)? = null,
    onPaste: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    pickKey: (() -> Unit)? = null,
    /**
     * When true, the block shows a "QR ▾" dropdown next to Paste
     * exposing "From clipboard image" and "From file…" options.
     * Decoded text replaces the field value on success. Noisy
     * "no QR found" is surfaced via [onQrError] (a toast / inline
     * error — caller's choice).
     */
    enableQr: Boolean = false,
    onQrError: ((String) -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val effectivePaste = onPaste ?: if (!readOnly && enabled) {
        { clipboard.getText()?.text?.let(onValueChange) }
    } else null
    val effectiveCopy = onCopy ?: if (value.isNotEmpty()) {
        { clipboard.setText(AnnotatedString(value)) }
    } else null

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = placeholder?.let { { Text(it) } },
            enabled = enabled,
            readOnly = readOnly,
            singleLine = singleLine,
            visualTransformation = if (password) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
            textStyle = if (monospace)
                MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.body2,
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pickKey != null) {
                IconButton(onClick = pickKey) {
                    Icon(Icons.Filled.Person, contentDescription = "Pick a key from wallet")
                }
            }
            // Text-label buttons instead of icon buttons. material-icons-core
            // doesn't ship ContentCopy / ContentPaste / Clear-at-the-end
            // glyphs; pulling in material-icons-extended just for three
            // icons is overkill. Labels are also clearer on desktop.
            if (onClear != null && value.isNotEmpty()) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
            if (effectivePaste != null) {
                TextButton(onClick = { effectivePaste() }) { Text("Paste") }
            }
            if (enableQr && !readOnly && enabled) {
                QrSourceMenu(
                    onDecoded = onValueChange,
                    onError = { msg -> onQrError?.invoke(msg) },
                )
            }
            if (effectiveCopy != null) {
                TextButton(onClick = { effectiveCopy() }) { Text("Copy") }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * "QR ▾" dropdown: lets the user feed a field from a QR code
 * picked either off the clipboard or out of a file on disk.
 * Decoded text replaces the field via [onDecoded]; decoder
 * failures are surfaced through [onError] (the caller can render
 * a toast / inline message).
 *
 * The file picker uses AWT [FileDialog] instead of a Compose
 * file dialog — AWT is macOS-native and we already host a Swing
 * window, so no extra deps or styling work.
 */
@Composable
private fun QrSourceMenu(
    onDecoded: (String) -> Unit,
    onError: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var cameraBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Cache install check across recompositions; cheap to re-check
    // but avoids hitting the filesystem every recomposition cycle.
    val scanQrInstalled = remember { ScanQrLauncher.isInstalled() }

    Box {
        TextButton(
            enabled = !cameraBusy,
            onClick = { menuOpen = true },
        ) {
            Text(if (cameraBusy) "QR (waiting for ScanQR…)" else "QR ▾")
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
        ) {
            DropdownMenuItem(onClick = {
                menuOpen = false
                val txt = QrDecode.fromClipboardImage()
                if (txt != null) onDecoded(txt)
                else onError("No QR code found on clipboard image")
            }) { Text("From clipboard image") }
            DropdownMenuItem(onClick = {
                menuOpen = false
                val parent = findAwtFrame()
                val dlg = FileDialog(parent, "Pick a QR image", FileDialog.LOAD).apply {
                    isMultipleMode = false
                    // Very loose filter — users may pass PNG, JPG,
                    // whatever. ZXing reads what ImageIO reads.
                    setFilenameFilter { _, name ->
                        val lower = name.lowercase()
                        lower.endsWith(".png") || lower.endsWith(".jpg") ||
                            lower.endsWith(".jpeg") || lower.endsWith(".gif") ||
                            lower.endsWith(".bmp")
                    }
                }
                dlg.isVisible = true
                val dir = dlg.directory
                val file = dlg.file
                if (dir != null && file != null) {
                    val f = File(dir, file)
                    val txt = QrDecode.fromFile(f)
                    if (txt != null) onDecoded(txt)
                    else onError("No QR code found in ${f.name}")
                }
            }) { Text("From file…") }
            // Camera scan via the standalone ScanQR companion app.
            // Disabled (greyed) when ScanQR isn't installed at the
            // expected path — keeps the menu honest about what
            // works without bouncing the user into a failure dialog.
            DropdownMenuItem(
                enabled = scanQrInstalled,
                onClick = {
                    menuOpen = false
                    cameraBusy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ScanQrLauncher.launchAndWait()
                        }
                        cameraBusy = false
                        when (result) {
                            is ScanQrLauncher.Result.Ok -> onDecoded(result.text)
                            is ScanQrLauncher.Result.Cancelled -> {
                                // No surface — user clicked away in
                                // ScanQR; treat as a quiet no-op.
                            }
                            is ScanQrLauncher.Result.Failed -> onError(result.message)
                        }
                    }
                },
            ) {
                Text(
                    if (scanQrInstalled) "From camera (ScanQR)…"
                    else "From camera (install ScanQR.app)"
                )
            }
        }
    }
}

/**
 * Walk AWT's window list to find any visible Frame — used as
 * the parent for [FileDialog]. Returning null is fine; AWT
 * falls back to a detached dialog.
 */
private fun findAwtFrame(): Frame? =
    Frame.getFrames().firstOrNull { it.isShowing }
