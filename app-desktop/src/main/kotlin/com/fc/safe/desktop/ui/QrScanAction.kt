package com.fc.safe.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fc.safe.desktop.qr.QrDecode
import com.fc.safe.desktop.qr.ScanQrLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Scan-QR affordance shared by every field that can be filled from a
 * code shown on another device: FIDs, pubkeys, tx hex, OP_RETURN
 * carvings, backup JSON, otpauth URIs…
 *
 * Renders as a single icon button that drops down the three sources we
 * support:
 *  - **clipboard image** — Cmd-Shift-4 a QR off a phone screen / web page
 *  - **file** — a saved PNG/JPG
 *  - **camera** — hands off to the standalone ScanQR companion app
 *    (see [ScanQrLauncher]); greyed out when it isn't installed.
 *
 * Decoded text goes to [onDecoded]; decode failures to [onError] (a
 * caller-owned inline error / toast). A user cancelling out of ScanQR
 * is silent by design.
 */
@Composable
fun QrScanIconButton(
    onDecoded: (String) -> Unit,
    modifier: Modifier = Modifier,
    onError: (String) -> Unit = {},
    enabled: Boolean = true,
    tooltip: String = "Scan a QR code",
) {
    var menuOpen by remember { mutableStateOf(false) }
    var cameraBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Cache the install check across recompositions — cheap, but no
    // reason to stat the filesystem on every frame.
    val scanQrInstalled = remember { ScanQrLauncher.isInstalled() }

    Box(modifier = modifier) {
        HoverTooltip(if (cameraBusy) "Waiting for ScanQR…" else tooltip) {
            IconButton(
                enabled = enabled && !cameraBusy,
                onClick = { menuOpen = true },
            ) {
                if (cameraBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = tooltip)
                }
            }
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
                pickQrFile()?.let { f ->
                    val txt = QrDecode.fromFile(f)
                    if (txt != null) onDecoded(txt)
                    else onError("No QR code found in ${f.name}")
                }
            }) { Text("From file…") }

            // Camera scan via the standalone ScanQR companion app.
            // Disabled (greyed) when ScanQR isn't installed at the
            // expected path — keeps the menu honest about what works
            // without bouncing the user into a failure dialog.
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
 * Bottom-aligned wrapper for the trailing slot of a *tall* text field.
 *
 * Material centres a trailing icon vertically, which strands it halfway
 * down a 100-260dp box, reading as unrelated to anything. Filling the
 * slot's height and pushing the button to the bottom parks it in the
 * field's bottom-right corner instead, where it reads as an action on
 * the field.
 *
 * Only safe where the field's height is bounded — an explicit
 * `.height(...)` or a `weight(1f)`. An unbounded (wrap-content) field
 * would hand this an infinite height constraint; single-line fields
 * don't need it anyway, since centre and bottom coincide there.
 */
@Composable
fun QrScanTrailingIcon(
    onDecoded: (String) -> Unit,
    onError: (String) -> Unit = {},
    enabled: Boolean = true,
    tooltip: String = "Scan a QR code",
) {
    Column(
        modifier = Modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Bottom,
    ) {
        QrScanIconButton(
            onDecoded = onDecoded,
            onError = onError,
            enabled = enabled,
            tooltip = tooltip,
        )
    }
}

/**
 * [OutlinedTextField] with a scan-QR icon parked in its trailing slot.
 * The drop-in for the short, single-line fields that carry a value
 * someone might be holding on another screen — FID, tx id, pubkey.
 *
 * Scanned text replaces the field by default; pass [onScanned] when the
 * caller needs to do more than assign (clear an error, re-run a lookup).
 * Tall multi-line fields shouldn't use this — a trailing icon floats to
 * the vertical centre of the box and reads as unrelated to the label;
 * put a bare [QrScanIconButton] in a row beneath those instead.
 */
@Composable
fun ScanTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    monospace: Boolean = false,
    scanTooltip: String = "Scan a QR code",
    onScanError: (String) -> Unit = {},
    onScanned: ((String) -> Unit)? = null,
    /**
     * Adds a make-QR icon ahead of the scan one, so the value in the
     * field can be pushed *out* to another device as well as pulled in.
     * Worth having wherever the field holds something transferable —
     * an address, a FID, a pubkey — and pointless on a passphrase.
     */
    enableMakeQr: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = if (monospace)
            MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace)
        else MaterialTheme.typography.body2,
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (enableMakeQr) {
                    MakeQrIconButton(text = value, title = label)
                }
                QrScanIconButton(
                    // Trim by default: QR payloads routinely carry stray
                    // whitespace/newlines that would fail an FID or hex
                    // validity check for no reason the user can see.
                    onDecoded = onScanned ?: { onValueChange(it.trim()) },
                    onError = onScanError,
                    enabled = enabled && !readOnly,
                    tooltip = scanTooltip,
                )
            }
        },
        modifier = modifier,
    )
}

/**
 * Hover label for icon-only buttons. Desktop has a pointer, so the
 * `contentDescription` that would carry this on a phone is invisible —
 * without a tooltip a lone glyph is a guessing game.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HoverTooltip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(
                elevation = 4.dp,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text,
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        },
        content = content,
    )
}

/**
 * Native open-panel for a QR image. AWT's [FileDialog] is the macOS
 * panel and we already host a Swing window, so this needs no extra
 * dependency or styling. Returns null when the user cancels.
 */
private fun pickQrFile(): File? {
    val dlg = FileDialog(findAwtFrame(), "Pick a QR image", FileDialog.LOAD).apply {
        isMultipleMode = false
        // Very loose filter — users may pass PNG, JPG, whatever.
        // ZXing reads what ImageIO reads.
        setFilenameFilter { _, name ->
            val lower = name.lowercase()
            lower.endsWith(".png") || lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") || lower.endsWith(".gif") ||
                lower.endsWith(".bmp")
        }
    }
    dlg.isVisible = true
    val dir = dlg.directory ?: return null
    val file = dlg.file ?: return null
    return File(dir, file)
}

/**
 * Walk AWT's window list for any visible Frame to parent [FileDialog]
 * on. Null is fine — AWT falls back to a detached dialog.
 */
internal fun findAwtFrame(): Frame? =
    Frame.getFrames().firstOrNull { it.isShowing }
