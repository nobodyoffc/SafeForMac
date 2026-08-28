package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.SafeButton
import core.crypto.Hash
import core.crypto.KeyTools
import data.fcData.AlgorithmId
import data.fcData.Signature
import org.slf4j.LoggerFactory
import utils.BytesUtils
import utils.Hex

private val log = LoggerFactory.getLogger("VerifyScreen")

private enum class VerifyState { Idle, Valid, Invalid }

class VerifyScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()

        var sigJson by remember { mutableStateOf("") }
        var key by remember { mutableStateOf("") }
        var state by remember { mutableStateOf(VerifyState.Idle) }
        var error by remember { mutableStateOf<String?>(null) }

        AppShell(
            title = "Verify signature",
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
                    value = sigJson,
                    onValueChange = { sigJson = it; error = null; state = VerifyState.Idle },
                    label = "Signature JSON",
                    heightDp = 200,
                    onClear = { sigJson = "" },
                )

                CryptoIoBlock(
                    value = key,
                    onValueChange = { key = it; error = null; state = VerifyState.Idle },
                    label = "Key (symkey hex — only for HMAC-SHA256) or FID fallback",
                    placeholder = "Leave empty for ECDSA/Schnorr when the signature carries an FID",
                    singleLine = true,
                    heightDp = 64,
                    onClear = { key = "" },
                )

                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(onClick = {
                        sigJson = ""; key = ""; error = null; state = VerifyState.Idle
                    }) { Text("Clear") }
                    SafeButton(
                        enabled = sigJson.isNotBlank(),
                        onClick = {
                            error = null
                            state = try {
                                if (verifyOne(sigJson, key)) VerifyState.Valid
                                else VerifyState.Invalid
                            } catch (t: Throwable) {
                                log.warn("Verify threw", t)
                                error = t.message ?: "Verify failed"
                                VerifyState.Invalid
                            }
                        },
                    ) { Text("Verify") }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                Spacer(Modifier.height(24.dp))
                Divider()
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (state) {
                        VerifyState.Idle -> {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = null,
                                modifier = Modifier.iconSize(48.dp),
                                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("Not yet verified", style = MaterialTheme.typography.subtitle1)
                        }
                        VerifyState.Valid -> {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Valid",
                                modifier = Modifier.iconSize(48.dp),
                                tint = Color(0xFF2E7D32),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Signature is VALID",
                                style = MaterialTheme.typography.subtitle1,
                                color = Color(0xFF2E7D32),
                            )
                        }
                        VerifyState.Invalid -> {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Invalid",
                                modifier = Modifier.iconSize(48.dp),
                                tint = MaterialTheme.colors.error,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Signature is INVALID",
                                style = MaterialTheme.typography.subtitle1,
                                color = MaterialTheme.colors.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

// Trivial alias so call-sites read naturally; `Modifier.size(48.dp)`
// directly also works after the import above, but keeping this lets us
// grep the file for "iconSize" to find all icon affordances.
private fun Modifier.iconSize(size: androidx.compose.ui.unit.Dp): Modifier =
    this.then(Modifier.size(size))

/**
 * Mirrors Android `VerifyActivity.verifyMessage`: parse the signature
 * JSON, supply a symkey when the algorithm demands one, optionally
 * attach an FID fallback when the signature didn't carry its own,
 * then call `Signature.verify()`.
 *
 * Caveat for `FC_Sha256SymSignMsg_No1_NrC7`: FC-JDK's `Signature.verify`
 * checks only the legacy `sha256x2(msg||key)` scheme, but `Signature.sign`
 * emits HMAC-SHA256 — so FC-JDK-produced sym signatures never round-trip
 * through FC-JDK's own verify. FC-AJDK was fixed to try HMAC first then
 * fall back to legacy; we reproduce that locally until the fix is
 * backported. ECDSA and Schnorr still defer to `Signature.verify()`.
 */
private fun verifyOne(sigJson: String, key: String): Boolean {
    val sig = Signature.fromJson(sigJson)
        ?: throw IllegalArgumentException("Not a valid signature JSON")
    val alg = sig.alg ?: throw IllegalArgumentException("Signature missing algorithm")

    if (alg == AlgorithmId.FC_Sha256SymSignMsg_No1_NrC7) {
        if (key.isBlank()) throw IllegalArgumentException("Symkey required for HMAC-SHA256 signature")
        if (!Hex.isHex32(key)) throw IllegalArgumentException("Symkey must be 64 hex chars")
        val symkey = Hex.fromHex(key)
        val msg = sig.msg ?: throw IllegalArgumentException("Signature missing msg")
        val signHex = sig.sign ?: throw IllegalArgumentException("Signature missing sign")
        return verifySymSignDual(msg, symkey, signHex)
    }

    if (key.isNotBlank() && sig.fid == null && KeyTools.isGoodFid(key)) {
        sig.fid = key
    }
    return sig.verify()
}

/**
 * HMAC-then-legacy verify, mirroring FC-AJDK's fixed
 * `verifySha256SymSign`. A signed-with-HMAC value matches the first
 * branch; older signatures produced by the legacy path still verify
 * via the fallback.
 */
private fun verifySymSignDual(msg: String, symkey: ByteArray, signHex: String): Boolean {
    val msgBytes = msg.toByteArray(Charsets.UTF_8)
    val hmacHex = Hex.toHex(Hash.hmacSha256(msgBytes, symkey))
    if (signHex.equals(hmacHex, ignoreCase = true)) return true
    val legacyBytes = BytesUtils.bytesMerger(msgBytes, symkey)
    val legacyHex = Hex.toHex(Hash.sha256x2(legacyBytes))
    return signHex.equals(legacyHex, ignoreCase = true)
}
