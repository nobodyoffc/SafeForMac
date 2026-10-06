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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.JsonParser
import java.net.URLDecoder

/**
 * Parses a pasted TOTP source and emits (title, base32Seed).
 * Accepts three shapes, in order of preference:
 *
 * 1. `otpauth://totp/Issuer:account?secret=BASE32[&issuer=...]` —
 *    the QR-code format Google Authenticator, Authy, 1Password etc.
 *    all speak. Title becomes `"Issuer: account"` when `issuer` is
 *    present, else just `account`.
 * 2. JSON `{"secret":"...","label":"..."}` — the shape Safe Android's
 *    `ImportTotpActivity.parseTotpInput` uses for file-based import.
 * 3. Bare Base32 — no label available, uses the user-typed title or a
 *    placeholder.
 *
 * Parsing is local to this dialog; callers just get (title, seed) and
 * save through their own persistence path.
 */
@Composable
fun TotpImportDialog(
    busy: Boolean,
    onSubmit: (title: String, base32Seed: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var source by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }

    // Live parse — attempt every time `source` or `title` changes so
    // Save enables/disables without requiring a "Parse" button.
    val parsed = remember(source, title) { parseSource(source.trim(), title.trim()) }
    val canSubmit = !busy && parsed is Parsed.Ok

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.requiredWidth(560.dp).height(440.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Import TOTP", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Paste an otpauth:// URI, a {secret, label} JSON, or a bare Base32 seed.",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it; parseError = null },
                    label = { Text("TOTP source") },
                    textStyle = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    trailingIcon = {
                        QrScanTrailingIcon(
                            onDecoded = { source = it.trim(); parseError = null },
                            onError = { parseError = it },
                            tooltip = "Scan the authenticator QR code",
                        )
                    },
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (overrides parsed label)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                val preview = when (val p = parsed) {
                    is Parsed.Ok -> "→ title: \"${p.title}\" · seed: ${p.seed.take(10)}…"
                    is Parsed.Err -> p.message
                    Parsed.Empty -> " "
                }
                Text(
                    preview,
                    style = MaterialTheme.typography.caption,
                    color = if (parsed is Parsed.Err)
                        MaterialTheme.colors.error
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = canSubmit,
                        onClick = {
                            (parsed as? Parsed.Ok)?.let { onSubmit(it.title, it.seed) }
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

private sealed interface Parsed {
    data object Empty : Parsed
    data class Ok(val title: String, val seed: String) : Parsed
    data class Err(val message: String) : Parsed
}

/**
 * Hand-rolled URI parsing — deliberately avoiding `java.net.URI` which
 * is strict about reserved characters in TOTP labels (spaces, colons,
 * `@` are all common and escape inconsistently). We only need
 * `secret` and `issuer` query params plus the path, so a small
 * substring carve-out is simpler and more forgiving.
 */
private fun parseSource(source: String, titleOverride: String): Parsed {
    if (source.isBlank()) return Parsed.Empty

    // 1. otpauth://totp/label?secret=...&issuer=...
    if (source.startsWith("otpauth://totp/", ignoreCase = true)) {
        val afterScheme = source.substring("otpauth://totp/".length)
        val queryIdx = afterScheme.indexOf('?')
        val label = if (queryIdx >= 0) afterScheme.substring(0, queryIdx) else afterScheme
        val query = if (queryIdx >= 0) afterScheme.substring(queryIdx + 1) else ""
        val decodedLabel = runCatching { URLDecoder.decode(label, "UTF-8") }.getOrDefault(label)
        val params = query.split('&')
            .mapNotNull {
                val eq = it.indexOf('=')
                if (eq < 0) null
                else it.substring(0, eq) to
                    runCatching { URLDecoder.decode(it.substring(eq + 1), "UTF-8") }
                        .getOrDefault(it.substring(eq + 1))
            }
            .toMap()
        val seed = params["secret"] ?: return Parsed.Err("otpauth URI missing `secret`")
        val issuer = params["issuer"]
        val parsedTitle = when {
            !issuer.isNullOrBlank() && !decodedLabel.contains(':') ->
                "$issuer: $decodedLabel"
            else -> decodedLabel
        }
        return Parsed.Ok(titleOverride.ifBlank { parsedTitle }, seed)
    }

    // 2. {"secret":"...","label":"..."}
    if (source.trimStart().startsWith("{")) {
        return runCatching {
            val obj = JsonParser.parseString(source).asJsonObject
            val seed = obj.get("secret")?.asString
                ?: return@runCatching Parsed.Err("JSON missing `secret`")
            val label = obj.get("label")?.asString ?: obj.get("title")?.asString ?: "TOTP"
            Parsed.Ok(titleOverride.ifBlank { label }, seed) as Parsed
        }.getOrElse { Parsed.Err("Not valid JSON") }
    }

    // 3. Bare Base32 — any non-whitespace non-URI input that looks
    //    like A-Z2-7 characters. Leave validation to the caller's
    //    fromBase32 call.
    if (source.all { it.isLetterOrDigit() || it == ' ' || it == '=' }) {
        val fallbackTitle = titleOverride.ifBlank { "TOTP" }
        return Parsed.Ok(fallbackTitle, source.replace(" ", ""))
    }

    return Parsed.Err("Unrecognized format")
}
