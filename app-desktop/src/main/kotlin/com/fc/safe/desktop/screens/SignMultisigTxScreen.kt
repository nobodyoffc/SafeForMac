package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.DesktopKeyInfo
import com.fc.safe.desktop.fch.TxHandler
import com.fc.safe.desktop.fch.parseRawTxInfo
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.FidAvatar
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

private val log = LoggerFactory.getLogger("SignMultisigTxScreen")

/**
 * Signer step of the multisig-TX flow. Paste the unsigned JSON from
 * [CreateMultisigTxScreen], pick a wallet key that's a member of
 * `senderMultisig.fids`, sign — and copy the updated JSON (now
 * carrying this signer's entry in `fidSigMap`) to forward to the
 * next signer or the aggregator.
 *
 * Contract with the signing path: [TxHandler.signSchnorrMultiSignTx]
 * mutates `raw.fidSigMap` in place, adding an entry keyed by the
 * signer's FID with one sig per input. We don't touch the multisig
 * object or any other fields, so the envelope stays byte-compatible
 * with Android's `SignMultisignTxActivity` output.
 *
 * Validation that surfaces before signing:
 * - JSON parses to a `RawTxInfo`.
 * - `senderMultisig` is populated (else this isn't a multisig tx).
 * - Picked key's FID is in `senderMultisig.fids` (else the sig would
 *   never contribute to the final unlock script).
 * - The key hasn't already signed (idempotency — re-signing would
 *   silently overwrite the prior sig, which is never what the user
 *   intends).
 */
class SignMultisigTxScreen(
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
        var signedJson by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }

        fun tryParse() {
            error = null
            signedJson = null
            pickedKey = null
            val raw = parseRawTxInfo(jsonText)
            if (raw == null && jsonText.isNotBlank()) {
                error = "Failed to parse tx JSON"
                parsed = null
                return
            }
            if (raw != null && raw.senderMultisig == null) {
                error = "This tx has no senderMultisig — use Sign TX for single-sig."
                parsed = null
                return
            }
            parsed = raw
        }

        fun sign() {
            val raw = parsed ?: return
            val key = pickedKey ?: run {
                error = "Pick a signing key first"
                return
            }
            val multisig = raw.senderMultisig ?: run {
                error = "Missing multisig metadata"
                return
            }
            if (!multisig.fids.orEmpty().contains(key.id)) {
                error = "Key ${key.id} is not a member of this multisig group"
                return
            }
            if (raw.fidSigMap?.containsKey(key.id) == true) {
                error = "Key ${key.id} has already signed this tx"
                return
            }
            error = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching { signMultisigWithWalletKey(raw, key) }
                }
                busy = false
                outcome.onSuccess { signedJson = it }
                outcome.onFailure {
                    log.warn("Multisig sign failed", it)
                    error = "Sign failed: ${it.message}"
                }
            }
        }

        AppShell(
            title = "Sign multisig TX",
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
                        signedJson = null
                        pickedKey = null
                        error = null
                    },
                    label = "Unsigned multisig tx JSON",
                    placeholder = "Paste a RawTxInfo with senderMultisig set",
                    heightDp = 180,
                    onClear = {
                        jsonText = ""
                        parsed = null
                        signedJson = null
                        pickedKey = null
                        error = null
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

                parsed?.let { raw ->
                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))
                    TxPreview(raw)

                    Spacer(Modifier.height(16.dp))
                    MultisigStatus(raw)

                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))

                    CryptoIoBlock(
                        value = pickedKey?.let { "[wallet key: ${it.id}]" } ?: "",
                        onValueChange = {},
                        label = "Signing key (member of ${raw.senderMultisig.id})",
                        placeholder = "Pick a wallet key via the person icon",
                        readOnly = true,
                        singleLine = true,
                        heightDp = 64,
                        pickKey = { showPicker = true },
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SafeButton(
                            enabled = !busy && pickedKey != null && signedJson == null,
                            onClick = ::sign,
                        ) { Text("Sign") }
                    }
                }

                signedJson?.let { json ->
                    Spacer(Modifier.height(16.dp))
                    Divider()
                    Spacer(Modifier.height(16.dp))
                    CryptoIoBlock(
                        value = json,
                        onValueChange = {},
                        label = "Signed multisig tx (forward to next signer or aggregator)",
                        readOnly = true,
                        heightDp = 260,
                    )
                }
            }
        }

        if (showPicker) {
            KeyPickerDialog(
                filter = KeyPickerFilter.WithPrikey,
                onPicked = {
                    pickedKey = it
                    showPicker = false
                    error = null
                },
                onDismiss = { showPicker = false },
            )
        }
    }
}

/**
 * Member-list + signature-progress block. Members already in
 * `fidSigMap` are marked "signed"; the rest are "pending". M out of
 * N pending → you're ready to build.
 */
@Composable
private fun MultisigStatus(raw: RawTxInfo) {
    val multisig = raw.senderMultisig ?: return
    val members = multisig.fids.orEmpty()
    val signed = raw.fidSigMap?.keys.orEmpty()
    val m = multisig.m
    val n = multisig.n

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "Multisig ${m}-of-${n}  ·  ${signed.size} of $m signed",
            style = MaterialTheme.typography.subtitle2,
        )
        Spacer(Modifier.height(6.dp))
        members.forEachIndexed { i, fid ->
            val state = if (fid in signed) "✓ signed" else "pending"
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                FidAvatar(fid = fid, size = 24.dp)
                Spacer(Modifier.padding(horizontal = 6.dp))
                Text(
                    fid,
                    style = MaterialTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.padding(horizontal = 8.dp))
                Text(
                    state,
                    style = MaterialTheme.typography.caption,
                    color = if (fid in signed) MaterialTheme.colors.primary
                    else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/**
 * Decrypt the picked key, run the shim's
 * [TxHandler.signSchnorrMultiSignTx] (which mutates `raw.fidSigMap`),
 * then serialize the updated envelope for hand-off.
 *
 * Same use-after-wipe guard as [SignTxScreen.signTxWithWalletKey]:
 * `KeyTools.getPrikey32(bytes)` returns the input array for 32-byte
 * input, so we copy before letting the outer `fill(0)` run. Wipe
 * both in `finally` blocks regardless of outcome.
 */
private fun signMultisigWithWalletKey(raw: RawTxInfo, key: DesktopKeyInfo): String {
    val cipher = key.prikeyCipher
        ?: throw IllegalStateException("Picked key has no private key (watch-only)")

    val rawPrikey = WalletSession.decryptFromJson(cipher)
    try {
        val prikey32 = KeyTools.getPrikey32(rawPrikey)?.copyOf()
            ?: throw IllegalStateException("Decrypted value is not a valid 32-byte prikey")
        try {
            TxHandler().signSchnorrMultiSignTx(raw, prikey32)
            return raw.toNiceJson()
                ?: throw IllegalStateException("Signed-tx serialization returned null")
        } finally {
            prikey32.fill(0)
        }
    } finally {
        rawPrikey.fill(0)
    }
}
