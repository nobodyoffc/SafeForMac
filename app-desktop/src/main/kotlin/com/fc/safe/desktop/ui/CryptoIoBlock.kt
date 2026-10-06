package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

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
     * When true, the action row gains a scan-QR icon
     * ([QrScanIconButton]) offering clipboard image / file / camera
     * as sources. Decoded text replaces the field value on success;
     * decode failures are surfaced via [onQrError] (a toast / inline
     * error — caller's choice).
     */
    enableQr: Boolean = false,
    onQrError: ((String) -> Unit)? = null,
    /**
     * When true, the field gains a make-QR icon ([MakeQrIconButton])
     * that puts the current value on screen as a scannable code —
     * greyed while the field is empty. This is the outbound half of
     * [enableQr], and the one that matters on an air-gapped machine:
     * it's how a result leaves this app. Result boxes should set it.
     */
    enableMakeQr: Boolean = false,
    /** Dialog title for [enableMakeQr]; defaults to the field's label. */
    makeQrTitle: String? = null,
) {
    val clipboard = LocalClipboardManager.current
    val effectivePaste = onPaste ?: if (!readOnly && enabled) {
        { clipboard.getText()?.text?.let(onValueChange) }
    } else null
    val effectiveCopy = onCopy ?: if (value.isNotEmpty()) {
        { clipboard.setText(AnnotatedString(value)) }
    } else null
    val showScanIcon = enableQr && !readOnly && enabled

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
            trailingIcon = if (showScanIcon || enableMakeQr) {
                {
                    // Bottom-right: the box is up to 260dp tall, and a
                    // centred icon floats unmoored in the middle of it.
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.Bottom,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (enableMakeQr) {
                                MakeQrIconButton(
                                    text = value,
                                    title = makeQrTitle ?: label,
                                )
                            }
                            if (showScanIcon) {
                                QrScanIconButton(
                                    onDecoded = onValueChange,
                                    onError = { msg -> onQrError?.invoke(msg) },
                                )
                            }
                        }
                    }
                }
            } else null,
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
            if (effectiveCopy != null) {
                TextButton(onClick = { effectiveCopy() }) { Text("Copy") }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
