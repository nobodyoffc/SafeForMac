package com.fc.safe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.slf4j.LoggerFactory
import java.awt.FileDialog
import java.io.File
import java.nio.charset.StandardCharsets
import javax.imageio.ImageIO

private val log = LoggerFactory.getLogger("QrMake")

/**
 * Splits a payload across as many QR codes as it takes.
 *
 * This machine is expected to run with no network, so a QR shown on
 * screen is the *only* way a signed tx, a cipher blob or a backup gets
 * to another device. Anything past a few hundred bytes stops being
 * reliably scannable long before it hits the format's hard ceiling — a
 * phone camera at arm's length simply can't resolve the modules.
 *
 * The thresholds mirror Android Safe's `QRCodeGenerator` exactly
 * (single code up to 300 bytes, 400-byte parts beyond that) so the two
 * apps chunk a given payload identically and the phone-side reader
 * reassembles what it already knows how to reassemble.
 *
 * Parts carry no index header — the reader concatenates them in scan
 * order, which is why the display dialog insists on the running
 * "Part n of N" label.
 */
object QrChunks {
    /** Payloads at or under this many UTF-8 bytes fit one code. */
    const val SINGLE_QR_MAX_BYTES = 300

    /** UTF-8 bytes per part once a payload has to be split. */
    const val CHUNK_MAX_BYTES = 400

    /**
     * [text] as one or more chunks. Empty text yields an empty list.
     *
     * Splits on code point boundaries, never inside one: a surrogate
     * pair cut in half encodes as replacement bytes and the rejoined
     * payload no longer matches what was displayed.
     */
    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        if (text.toByteArray(StandardCharsets.UTF_8).size <= SINGLE_QR_MAX_BYTES) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = start
            var bytes = 0
            while (end < text.length) {
                val cp = text.codePointAt(end)
                val cpChars = Character.charCount(cp)
                val cpBytes = text.substring(end, end + cpChars)
                    .toByteArray(StandardCharsets.UTF_8).size
                if (bytes + cpBytes > CHUNK_MAX_BYTES) break
                bytes += cpBytes
                end += cpChars
            }
            // A single code point wider than the budget can't happen at
            // 400 bytes, but never emit an empty chunk and spin forever.
            if (end == start) end = start + Character.charCount(text.codePointAt(start))
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }
}

/**
 * Make-QR affordance: the mirror of [QrScanIconButton]. Any value the
 * user might need to carry to another machine — an address, a FID, a
 * cipher, a signed tx, a backup blob — gets one of these at the end of
 * its field, and clicking it puts the value on screen as a code the
 * other device can scan.
 *
 * Disabled (greyed, not hidden) when [text] is blank, so a result box
 * doesn't reflow the moment it fills in.
 */
@Composable
fun MakeQrIconButton(
    text: String,
    modifier: Modifier = Modifier,
    title: String = "QR code",
    tooltip: String = "Show as QR code",
    enabled: Boolean = true,
    /**
     * Shrinks the button from Material's 48dp touch target to something
     * that fits inline in a dense detail row without doubling its
     * height. There's a mouse here, not a thumb.
     */
    compact: Boolean = false,
) {
    var showing by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        HoverTooltip(tooltip) {
            IconButton(
                enabled = enabled && text.isNotBlank(),
                onClick = { showing = true },
                modifier = if (compact) Modifier.size(28.dp) else Modifier,
            ) {
                Icon(
                    Icons.Filled.QrCode2,
                    contentDescription = tooltip,
                    modifier = if (compact) Modifier.size(18.dp) else Modifier,
                )
            }
        }
    }

    if (showing) {
        QrShowDialog(text = text, title = title, onDismiss = { showing = false })
    }
}

/**
 * Text-button form of [MakeQrIconButton], for dialog action rows that
 * already speak in labels ("Copy JSON", "Done") rather than glyphs.
 */
@Composable
fun ShowQrTextButton(
    text: String,
    title: String = "QR code",
    label: String = "Show QR",
    enabled: Boolean = true,
) {
    var showing by remember { mutableStateOf(false) }

    TextButton(
        enabled = enabled && text.isNotBlank(),
        onClick = { showing = true },
    ) { Text(label) }

    if (showing) {
        QrShowDialog(text = text, title = title, onDismiss = { showing = false })
    }
}

/**
 * Bottom-aligned wrapper for the trailing slot of a *tall* field, for
 * the same reason [QrScanTrailingIcon] exists: Material centres a
 * trailing icon, which strands it halfway down a 260dp box.
 *
 * Only safe where the field's height is bounded.
 */
@Composable
fun MakeQrTrailingIcon(
    text: String,
    title: String = "QR code",
    tooltip: String = "Show as QR code",
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.Bottom,
    ) {
        MakeQrIconButton(text = text, title = title, tooltip = tooltip, enabled = enabled)
    }
}

/**
 * Displays [text] as one or more QR codes, one at a time, with Prev /
 * Next when it had to be split.
 *
 * The code sits on a white plate regardless of app theme — a dark-theme
 * surface behind a QR inverts it and half the scanners on the market
 * won't read that.
 */
@Composable
fun QrShowDialog(
    text: String,
    title: String = "QR code",
    onDismiss: () -> Unit,
) {
    // Nothing to show and nothing to dismiss — a blank field shouldn't
    // be able to open an empty window.
    if (QrChunks.split(text).isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        QrShowPanel(text = text, title = title, onDismiss = onDismiss)
    }
}

/**
 * The dialog's contents, split out from the window that hosts them so
 * the layout can be rendered off-screen by `QrMakeSmoke`. A desktop
 * [Dialog] is a real window and never shows up in an `ImageComposeScene`.
 */
@Composable
internal fun QrShowPanel(
    text: String,
    title: String,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val chunks = remember(text) { QrChunks.split(text) }
    var page by remember(text) { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    if (chunks.isEmpty()) return
    val current = chunks[page.coerceIn(0, chunks.lastIndex)]

    Surface(
        modifier = Modifier.width(420.dp),
        shape = MaterialTheme.shapes.medium,
        elevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = MaterialTheme.typography.h6)

            if (chunks.size > 1) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Split into ${chunks.size} codes — scan them in order.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            }

            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.size(320.dp).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                QrCode(text = current, modifier = Modifier.size(304.dp))
            }

            if (chunks.size > 1) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = page > 0,
                        onClick = { page-- },
                    ) { Text("‹ Prev") }
                    Text(
                        "Part ${page + 1} of ${chunks.size}",
                        style = MaterialTheme.typography.body2,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    TextButton(
                        enabled = page < chunks.lastIndex,
                        onClick = { page++ },
                    ) { Text("Next ›") }
                }
            }

            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.primary,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(text))
                    status = "Text copied"
                }) { Text("Copy text") }
                TextButton(onClick = {
                    status = saveQrImages(chunks)
                }) { Text("Save PNG…") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }

            // The payload itself, so the value on screen can be
            // read back / checked against the source field without
            // trusting the code alone.
            Spacer(Modifier.height(8.dp))
            Text(
                text = current,
                style = MaterialTheme.typography.caption.copy(
                    fontFamily = FontFamily.Monospace,
                ),
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Writes every chunk to disk beside the name the user picks, numbered
 * `_1`.. `_N` when there's more than one. Returns a status line for the
 * dialog, or null when the user cancels the save panel.
 */
private fun saveQrImages(chunks: List<String>): String? {
    val dlg = FileDialog(findAwtFrame(), "Save QR code", FileDialog.SAVE).apply {
        file = "qr.png"
    }
    dlg.isVisible = true
    val dir = dlg.directory ?: return null
    val picked = dlg.file ?: return null
    val base = picked.removeSuffix(".png").removeSuffix(".PNG")

    var saved = 0
    chunks.forEachIndexed { i, chunk ->
        val matrix = encodeQrMatrix(chunk) ?: return@forEachIndexed
        val name = if (chunks.size == 1) "$base.png" else "${base}_${i + 1}.png"
        try {
            ImageIO.write(qrMatrixToImage(matrix), "png", File(dir, name))
            saved++
        } catch (t: Throwable) {
            log.warn("QR save failed ({}): {}", name, t.message)
        }
    }
    return when {
        saved == 0 -> "Save failed"
        chunks.size == 1 -> "Saved $base.png"
        else -> "Saved $saved of ${chunks.size} codes"
    }
}
