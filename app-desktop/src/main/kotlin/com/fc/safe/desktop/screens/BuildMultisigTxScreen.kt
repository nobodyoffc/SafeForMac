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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.PendingFlavor
import com.fc.safe.desktop.fch.TxHandler
import com.fc.safe.desktop.fch.parseRawTxInfo
import com.fc.safe.desktop.savePendingTx
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.ImportCashFromTxDialog
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.desktop.ui.TxPreview
import com.fc.safe.platform.macos.WalletSession
import core.fch.RawTxInfo
import data.fchData.Cash
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("BuildMultisigTxScreen")

/**
 * Aggregator step of the multisig-TX flow. Collect M signed JSONs
 * (one from each signer running [SignMultisigTxScreen]) and combine
 * them into a single broadcastable hex transaction.
 *
 * Under the hood this is one call —
 * [TxHandler.buildSignedMultisignTx] which runs
 * `mergeMultisignTxData` (verifying each contributor's sig against
 * the tx + redeem script) and then `buildSchnorrMultiSignTx` (which
 * writes the final `OP_0 <sig1> … <sigM> <redeemScript>` unlock
 * script onto each input).
 *
 * UX: the signer list grows on demand. Starting with two slots
 * since 2-of-N is the most common case; the aggregator grows
 * the list to whatever M the envelope requires. A single pasted
 * JSON that already carries M sigs (e.g. a sequential-sign flow
 * where each signer appended to the same envelope) also works —
 * `mergeMultisignTxData` handles the degenerate one-element array.
 */
class BuildMultisigTxScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var slots by remember { mutableStateOf(listOf("", "")) }
        var signedHex by remember { mutableStateOf<String?>(null) }
        var decoded by remember { mutableStateOf<RawTxInfo?>(null) }
        var showImport by remember { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf<String?>(null) }
        var pendingSaved by remember { mutableStateOf(false) }

        fun build() {
            error = null
            status = null
            signedHex = null
            decoded = null
            pendingSaved = false
            val filled = slots.map { it.trim() }.filter { it.isNotBlank() }
            if (filled.isEmpty()) {
                error = "Paste at least one signed JSON"
                return
            }
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.Default) {
                    runCatching {
                        val handler = TxHandler()
                        val hex = handler.buildSignedMultisignTx(filled.toTypedArray())
                            ?: throw IllegalStateException(
                                "buildSignedMultisignTx returned null — " +
                                    "check that all JSONs are for the same tx, sigs verify, " +
                                    "and you have at least M distinct signers."
                            )
                        // Decode for the preview pane. Non-fatal: a
                        // parse failure here just hides the preview;
                        // the hex itself is still correct.
                        val preview = runCatching { handler.parseTx(hex) }.getOrNull()
                        hex to preview
                    }
                }
                busy = false
                outcome.onSuccess { (hex, preview) ->
                    signedHex = hex
                    decoded = preview
                }
                outcome.onFailure {
                    log.warn("Build multisig tx failed", it)
                    error = "Build failed: ${it.message}"
                }
            }
        }

        fun savePending() {
            val hex = signedHex ?: return
            val raw = decoded ?: run {
                error = "Cannot save: tx hex could not be decoded"
                return
            }
            // Enrich the decoded envelope with senderMultisig from the
            // first non-blank slot so the pending-list row has a
            // meaningful sender FID to display. parseTx(hex) can't
            // recover the multisig metadata on its own.
            val enriched = slots.asSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotBlank() }
                ?.let { parseRawTxInfo(it) }
                ?.senderMultisig
            enriched?.let { raw.senderMultisig = it }
            status = null
            error = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { savePendingTx(raw, hex, PendingFlavor.MULTISIG) }
                }
                busy = false
                outcome.onSuccess { txid ->
                    pendingSaved = true
                    status = "Saved to Pending Broadcasts (txid ${txid.take(8)}…)"
                }
                outcome.onFailure {
                    log.warn("Save pending tx failed", it)
                    error = "Save failed: ${it.message}"
                }
            }
        }

        AppShell(
            title = "Build multisig TX",
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
                Text(
                    "Paste each signer's signed JSON below, then press Build. " +
                        "Each envelope must reference the same tx and multisig group.",
                    style = MaterialTheme.typography.body2,
                )
                Spacer(Modifier.height(12.dp))

                slots.forEachIndexed { idx, text ->
                    CryptoIoBlock(
                        value = text,
                        onValueChange = { v ->
                            slots = slots.mapIndexed { i, old -> if (i == idx) v else old }
                            error = null
                            signedHex = null
                        },
                        label = "Signed JSON #${idx + 1}",
                        placeholder = "Paste the RawTxInfo JSON with this signer's fidSigMap entry",
                        heightDp = 140,
                        onClear = {
                            slots = slots.mapIndexed { i, old -> if (i == idx) "" else old }
                            error = null
                            signedHex = null
                        },
                        enableQr = true,
                        onQrError = { error = it },
                    )
                    if (slots.size > 1) {
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                            SafeButton(onClick = {
                                slots = slots.filterIndexed { i, _ -> i != idx }
                                error = null
                                signedHex = null
                            }) { Text("Remove #${idx + 1}") }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(
                        enabled = !busy,
                        onClick = {
                            slots = slots + ""
                        },
                    ) { Text("+ Add signer") }
                    SafeButton(
                        enabled = !busy && slots.any { it.isNotBlank() },
                        onClick = ::build,
                    ) { Text("Build") }
                    SafeButton(
                        enabled = !busy,
                        onClick = {
                            slots = listOf("", "")
                            signedHex = null
                            decoded = null
                            error = null
                            status = null
                            pendingSaved = false
                        },
                    ) { Text("Clear") }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }
                status?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.primary)
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

                    decoded?.let { raw ->
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Decoded from hex",
                            style = MaterialTheme.typography.subtitle2,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Verify the recipients and amounts match what you expect " +
                                "before broadcasting. Input values are blank because " +
                                "they aren't embedded in the tx.",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(8.dp))
                        TxPreview(raw)
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SafeButton(
                            onClick = { showImport = true },
                        ) { Text("Import my UTXOs…") }
                        SafeButton(
                            enabled = !busy && decoded != null && !pendingSaved,
                            onClick = ::savePending,
                        ) {
                            Text(if (pendingSaved) "Saved ✓" else "Save to Pending Broadcasts")
                        }
                    }
                }
            }
        }

        if (showImport) {
            ImportCashFromTxDialog(
                initialTxHex = signedHex.orEmpty(),
                onDone = { list ->
                    showImport = false
                    error = null
                    status = null
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                val db: LocalDB<Cash> = WalletSession.openDb(
                                    "cashes", Cash::class.java
                                )
                                list.forEach { c ->
                                    val id = c.id ?: c.makeId(c.birthTxId, c.birthIndex)
                                    db.put(id, c)
                                }
                                db.commit()
                            }
                            status = "Saved ${list.size} UTXO(s) to My Cash"
                        } catch (t: Throwable) {
                            log.warn("Persist imported UTXOs failed", t)
                            error = "Save failed: ${t.message}"
                        }
                    }
                },
                onDismiss = { showImport = false },
            )
        }
    }
}
