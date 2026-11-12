package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fc.safe.desktop.KeyInputDetector
import com.fc.safe.desktop.KeyInputDetector.Kind
import core.crypto.KeyTools
import java.awt.FileDialog
import java.io.File

/** What the user asked [ImportKeyDialog] to import. */
internal data class ImportKeyRequest(
    val kind: Kind,
    val text: String,
    val password: String,
    val label: String?,
)

/** Watch-only verdicts: not an error, but not a key that can sign either. */
private val WatchOnlyColor = Color(0xFFB26A00)

/** A backup file larger than this is not a key backup. */
private const val MAX_BACKUP_FILE_BYTES = 16L * 1024 * 1024

/**
 * Adds a key from whatever key text the user has: a private key, public key, FID,
 * password cipher or key backup JSON, typed, scanned or picked from a file.
 * [KeyInputDetector] decides which, and the verdict is shown while typing so a
 * watch-only key is never mistaken for one that can sign. The same screen as
 * Android Safe's Import Keys.
 *
 * [awaitingPassword] is set by the caller when a backup turned out to be
 * encrypted: the password field then shows for it too.
 *
 * A plain [Dialog] + sized [Surface] rather than `AlertDialog`, which grows to
 * fit a long backup and pushes the buttons out of reach.
 */
@Composable
internal fun ImportKeyDialog(
    busy: Boolean,
    error: String?,
    awaitingPassword: Boolean,
    onInputChanged: () -> Unit,
    onSubmit: (ImportKeyRequest) -> Unit,
    onDismiss: () -> Unit,
    /** Text the field starts with. Only the render smoke sets it. */
    initialText: String = "",
) {
    var text by remember { mutableStateOf(initialText) }
    var password by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    // A backup picked from disk: imported as it is instead of the typed text.
    var file by remember { mutableStateOf<LoadedFile?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val kind = remember(text, file) {
        if (file != null) Kind.BACKUP else KeyInputDetector.detect(text)
    }
    val verdict = remember(text, kind, awaitingPassword) {
        if (kind == Kind.BACKUP && awaitingPassword) "This backup is encrypted. Input its password." to Tone.GOOD
        else verdictOf(kind, text.trim())
    }
    val importable = kind in setOf(Kind.PRIKEY, Kind.PUBKEY, Kind.FID, Kind.CIPHER, Kind.BACKUP)
    val showPassword = kind == Kind.CIPHER || (kind == Kind.BACKUP && awaitingPassword)
    val canSubmit = !busy && importable && (!showPassword || password.isNotEmpty())

    fun inputChanged() {
        localError = null
        onInputChanged()
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.requiredWidth(560.dp).height(520.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Import keys", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = file?.let { "File loaded: ${it.name}" } ?: text,
                    onValueChange = { text = it; inputChanged() },
                    label = { Text("Prikey, pubkey, FID, cipher or key JSON") },
                    readOnly = file != null,
                    textStyle = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    trailingIcon = {
                        QrScanTrailingIcon(
                            onDecoded = { file = null; text = it.trim(); inputChanged() },
                            onError = { localError = it },
                            enabled = !busy,
                            tooltip = "Scan a key QR code",
                        )
                    },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            pickBackupFile()?.let { picked ->
                                runCatching { LoadedFile(picked.name, readBackupFile(picked)) }
                                    .onSuccess { file = it; password = ""; inputChanged() }
                                    .onFailure { localError = it.message ?: "Cannot read the file" }
                            }
                        },
                    ) { Text("Open file…") }
                    TextButton(
                        enabled = !busy && (text.isNotEmpty() || file != null),
                        onClick = { file = null; text = ""; password = ""; label = ""; inputChanged() },
                    ) { Text("Clear") }
                }

                verdict?.let { (message, tone) ->
                    Text(
                        message,
                        color = when (tone) {
                            Tone.GOOD -> MaterialTheme.colors.primary
                            Tone.WATCH_ONLY -> WatchOnlyColor
                            Tone.BAD -> MaterialTheme.colors.error
                        },
                        style = MaterialTheme.typography.body2,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (showPassword) {
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Password") },
                        placeholder = { Text("The password it was encrypted with") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // A backup carries its own labels.
                if (kind != Kind.BACKUP) {
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Label (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                (localError ?: error)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                }

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = canSubmit,
                        onClick = {
                            onSubmit(
                                ImportKeyRequest(
                                    kind = kind,
                                    text = file?.content ?: text.trim(),
                                    password = password,
                                    label = label.trim().ifEmpty { null },
                                )
                            )
                        },
                    ) { Text(if (busy) "Working…" else if (kind == Kind.BACKUP) "Import" else "Save") }
                }
            }
        }
    }
}

private class LoadedFile(val name: String, val content: String)

private enum class Tone { GOOD, WATCH_ONLY, BAD }

/** The line under the input: what was detected. */
private fun verdictOf(kind: Kind, text: String): Pair<String, Tone>? {
    return when (kind) {
        Kind.EMPTY -> null
        Kind.PRIKEY -> "Private key → ${fidOfPrikey(text)}" to Tone.GOOD
        Kind.PUBKEY -> "Public key → ${fidOfPubkey(text)}. Watch-only: it can't sign." to Tone.WATCH_ONLY
        Kind.FID -> "FID. Watch-only: it can't sign." to Tone.WATCH_ONLY
        Kind.CIPHER -> "Encrypted. Input its password." to Tone.GOOD
        Kind.BACKUP -> "Key backup. Click Import to add its keys." to Tone.GOOD
        Kind.NON_PASSWORD_CIPHER -> "Encrypted, but not with a password. It can't be imported here." to Tone.BAD
        Kind.BAD_PRIKEY -> "Looks like a prikey, but it isn't valid. Check for a typo." to Tone.BAD
        Kind.BAD_PUBKEY -> "Looks like a public key, but it isn't valid. Check for a typo." to Tone.BAD
        Kind.BAD_FID -> "Looks like an FID, but the checksum is wrong. Check for a typo." to Tone.BAD
        Kind.MULTIPLE -> "One key at a time. Use a key backup JSON for several." to Tone.BAD
        Kind.UNKNOWN -> "Not a recognized key. For a phrase, use Input phrase." to Tone.BAD
    }
}

private fun fidOfPrikey(text: String): String {
    val prikey32 = KeyInputDetector.toPrikey32(text) ?: return "?"
    return try {
        KeyTools.prikeyToFid(prikey32)
    } catch (_: Exception) {
        "?"
    } finally {
        prikey32.fill(0)
    }
}

private fun fidOfPubkey(text: String): String =
    runCatching { KeyTools.pubkeyToFchAddr(text) }.getOrNull() ?: "?"

private fun pickBackupFile(): File? {
    val dlg = FileDialog(findAwtFrame(), "Pick a key backup", FileDialog.LOAD)
    dlg.isVisible = true
    val name = dlg.file ?: return null
    return File(dlg.directory, name)
}

private fun readBackupFile(file: File): String {
    require(file.length() <= MAX_BACKUP_FILE_BYTES) { "The file is too large to be a key backup" }
    return file.readText(Charsets.UTF_8)
}
