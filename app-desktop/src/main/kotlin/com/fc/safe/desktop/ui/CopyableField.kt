package com.fc.safe.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** How long the "copied" marker stays on screen after a click. */
private const val COPIED_FLASH_MS = 1200L

/**
 * Labelled read-only value (FID, pubkey, address, redeem script…). Clicking
 * anywhere on the row copies [value] to the clipboard and flashes a "copied"
 * marker beside the label. Rows with a blank/absent value render an em dash
 * and are inert.
 *
 * A make-QR icon sits at the end of the row so the value can also leave
 * this machine when there's no network to carry it — set [showQr] to
 * false on rows nobody would scan (a count, a threshold, a date).
 */
@Composable
fun CopyableField(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    valueStyle: TextStyle? = null,
    showQr: Boolean = true,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FLASH_MS)
            copied = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (value.isNullOrBlank()) Modifier
                else Modifier.clickable {
                    clipboard.setText(AnnotatedString(value))
                    copied = true
                }
            )
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
            CopiedMarker(copied)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value ?: "—",
                style = valueStyle
                    ?: MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f, fill = false),
            )
            if (showQr && !value.isNullOrBlank()) {
                Spacer(Modifier.width(4.dp))
                // The icon button swallows its own click, so tapping it
                // shows the code instead of also copying the row.
                MakeQrIconButton(text = value, title = label, compact = true)
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}

/**
 * Bare click-to-copy text for rows that carry no label of their own (list-card
 * FIDs, multisig members). [text] is what the user sees — which may be
 * shortened or numbered — while [value] is what lands on the clipboard.
 */
@Composable
fun CopyableText(
    text: String,
    value: String = text,
    modifier: Modifier = Modifier,
    style: TextStyle? = null,
    color: Color = MaterialTheme.colors.onSurface,
    /** Appends a compact make-QR icon; off by default, since these rows
     * appear in dense lists where an icon per line is noise. */
    showQr: Boolean = false,
    qrTitle: String = "QR code",
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FLASH_MS)
            copied = false
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            style = style
                ?: MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
            color = color,
            modifier = if (value.isBlank()) Modifier else Modifier.clickable {
                clipboard.setText(AnnotatedString(value))
                copied = true
            },
        )
        CopiedMarker(copied)
        if (showQr && value.isNotBlank()) {
            Spacer(Modifier.width(4.dp))
            MakeQrIconButton(text = value, title = qrTitle, compact = true)
        }
    }
}

@Composable
private fun CopiedMarker(copied: Boolean) {
    if (copied) {
        Spacer(Modifier.width(6.dp))
        Text(
            "copied",
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.primary,
        )
    }
}
