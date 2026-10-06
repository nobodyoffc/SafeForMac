package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.DesktopKeyInfo
import com.fc.safe.desktop.PendingFlavor
import com.fc.safe.desktop.fch.TxHandler
import com.fc.safe.desktop.fch.parseRawTxInfo
import com.fc.safe.desktop.savePendingTx
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.KeyPickerDialog
import com.fc.safe.desktop.ui.KeyPickerFilter
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.desktop.ui.TxPreview
import com.fc.safe.platform.macos.WalletSession
import core.crypto.KeyTools
import core.fch.RawTxInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("SignTxScreen")

/**
 * Offline tx signing. Paste a `RawTxInfo` JSON → parse → preview →
 * pick a signing key → sign → show the signed hex with Copy. The
 * signed hex is what you'd broadcast via an explorer's "push
 * transaction" endpoint.
 *
 * Uses FC-JDK's `TxCreator.createUnsignedTx` + `TxCreator.signTx`
 * pair — equivalent to Freer's `TxHandler.signTx(rawTxInfo, prikey)`
 * convenience wrapper, which hasn't been backported yet. TxCreator
 * already has P2SH / CLTV / Schnorr support inlined so it covers
 * everything TxHandler does for this flow.
 *
 * Sender FID from the JSON is *not* auto-matched to a wallet key —
 * the user explicitly picks the key via [KeyPickerDialog]. That's a
 * deliberate safeguard: a malformed `rawTxInfo.sender` shouldn't
 * cause the wrong key to auto-select.
 */
class SignTxScreen(
    /**
     * Optional pre-filled JSON. When supplied (e.g. `CreateTxScreen`
     * handing off its assembled `RawTxInfo`) the screen opens with
     * the JSON already in the input box and auto-parsed.
     */
    private val prefilledJson: String? = null,
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var jsonText by remember { mutableStateOf(prefilledJson.orEmpty()) }
        var parsed by remember {
            mutableStateOf(prefilledJson?.let { parseRawTxInfo(it) })
        }
        var pickedKey by remember { mutableStateOf<DesktopKeyInfo?>(null) }
        var showPicker by remember { mutableStateOf(false) }
        var signedHex by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var pendingSaved by remember { mutableStateOf(false) }
        var pendingMsg by remember { mutableStateOf<String?>(null) }

        fun tryParse() {
            error = null
            signedHex = null
            pendingSaved = false
            pendingMsg = null
            parsed = parseRawTxInfo(jsonText)
            if (parsed == null && jsonText.isNotBlank()) {
                error = "Failed to parse tx JSON (not RawTxInfo or RawTxForCs shape)"
            }
        }

        fun savePending() {
            val raw = parsed ?: return
            val hex = signedHex ?: return
            pendingMsg = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { savePendingTx(raw, hex, PendingFlavor.SINGLE) }
                }
                busy = false
                outcome.onSuccess {
                    pendingSaved = true
                    pendingMsg = "Saved to Pending Broadcasts (txid ${it.take(8)}…)"
                }
                outcome.onFailure {
                    log.warn("Save pending tx failed", it)
                    error = "Save failed: ${it.message}"
                }
            }
        }

        fun sign() {
            val raw = parsed ?: return
            val key = pickedKey ?: run {
                error = "Pick a signing key first"
                return
            }
            error = null
            pendingSaved = false
            pendingMsg = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { signTxWithWalletKey(raw, key) }
                }
                busy = false
                outcome.onSuccess { signedHex = it }
                outcome.onFailure {
                    log.warn("Sign tx failed", it)
                    error = "Sign failed: ${it.message}"
                }
            }
        }

        AppShell(
            title = "Sign TX",
            scaffoldState = scaffoldState,
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                CryptoIoBlock(
                    value = jsonText,
                    onValueChange = {
                        jsonText = it
                        parsed = null
                        signedHex = null
                        error = null
                        pendingSaved = false
                        pendingMsg = null
                    },
                    label = "Unsigned tx JSON",
                    placeholder = "Paste a RawTxInfo JSON (or use QR…)",
                    heightDp = 180,
                    onClear = {
                        jsonText = ""
                        parsed = null
                        signedHex = null
                        error = null
                        pendingSaved = false
                        pendingMsg = null
                    },
                    enableQr = true,
                    onQrError = { error = it },
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(
                        enabled = !busy && jsonText.isNotBlank(),
                        onClick = ::tryParse,
                    ) { Text("Parse") }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                parsed?.let { tx ->
                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))
                    TxPreview(tx)

                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))

                    CryptoIoBlock(
                        value = pickedKey?.let { "[wallet key: ${it.id}]" } ?: "",
                        onValueChange = {},
                        label = "Signing key",
                        placeholder = "Pick a wallet key via the person icon",
                        readOnly = true,
                        enableMakeQr = true,
                        singleLine = true,
                        heightDp = 64,
                        pickKey = { showPicker = true },
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SafeButton(
                            enabled = !busy && pickedKey != null && signedHex == null,
                            onClick = ::sign,
                        ) { Text("Sign") }
                    }
                }

                signedHex?.let { hex ->
                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))
                    CryptoIoBlock(
                        value = hex,
                        onValueChange = {},
                        label = "Signed tx (hex — broadcast this)",
                        readOnly = true,
                        enableMakeQr = true,
                        heightDp = 200,
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SafeButton(
                            enabled = !busy && !pendingSaved,
                            onClick = ::savePending,
                        ) {
                            Text(if (pendingSaved) "Saved ✓" else "Save to Pending Broadcasts")
                        }
                    }
                    pendingMsg?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.primary,
                        )
                    }
                }
            }
        }

        if (showPicker) {
            KeyPickerDialog(
                filter = KeyPickerFilter.WithPrikey,
                onPicked = {
                    pickedKey = it
                    showPicker = false
                },
                onDismiss = { showPicker = false },
            )
        }
    }
}

/**
 * Decrypt the wallet key's prikey via [WalletSession], then sign via
 * the local [TxHandler] shim — the mature CLTV / P2SH / multisig-aware
 * path from FC-AJDK. Routing through the shim (instead of FC-JDK's
 * `TxCreator.createUnsignedTx + signTx` pair we used earlier) fixes a
 * silent bug where CLTV outputs with `redeemScript` set on the
 * RawTxInfo were being serialized as plain P2PKH outputs because
 * TxCreator doesn't read the output-redeemScript field.
 *
 * Before signing we **populate `raw.sender` and per-input `owner`
 * from the picked key's FID** when they're missing. This is the
 * single-sig assumption: the key you picked is the owner of the
 * inputs you're spending. If the JSON already carries a `sender`
 * and it disagrees with the picked key, bail loudly — that mismatch
 * is almost always the wrong key being chosen.
 *
 * Prikey is wiped in `finally` regardless of outcome.
 */
private fun signTxWithWalletKey(raw: RawTxInfo, key: DesktopKeyInfo): String {
    val cipher = key.prikeyCipher
        ?: throw IllegalStateException("Picked key has no private key (watch-only)")

    val signerFid = key.id
    val existingSender = raw.sender
    if (!existingSender.isNullOrBlank() && existingSender != signerFid) {
        throw IllegalStateException(
            "Picked key ($signerFid) doesn't match the tx's sender ($existingSender). " +
                "Pick the matching wallet key, or remove the sender from the JSON."
        )
    }
    raw.sender = signerFid
    raw.inputs?.forEach { input ->
        if (input.owner.isNullOrBlank()) input.owner = signerFid
    }

    val rawPrikey = WalletSession.decryptFromJson(cipher)
    try {
        // Copy before calling getPrikey32 — getPrikey32(bytes) returns
        // the same array for 32-byte input; we need an independent
        // buffer so the outer fill(0) on rawPrikey doesn't zero the
        // prikey we hand to the shim. Same trap as Decrypt/SignMsg.
        val prikey32 = KeyTools.getPrikey32(rawPrikey)?.copyOf()
            ?: throw IllegalStateException("Decrypted value is not a valid 32-byte prikey")
        try {
            return TxHandler().signTx(raw, prikey32)
                ?: throw IllegalStateException(
                    "TxHandler.signTx returned null — check inputs/outputs"
                )
        } finally {
            prikey32.fill(0)
        }
    } finally {
        rawPrikey.fill(0)
    }
}
