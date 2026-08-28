package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Card
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.rememberScaffoldState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.fch.TxHandler
import com.fc.safe.desktop.ui.AddTxInputDialog
import com.fc.safe.desktop.ui.AddTxOutputDialog
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CashPickerDialog
import com.fc.safe.desktop.ui.CryptoIoBlock
import com.fc.safe.desktop.ui.FidAvatar
import com.fc.safe.desktop.ui.FidPickerDialog
import com.fc.safe.desktop.ui.KeyPickerDialog
import com.fc.safe.desktop.ui.KeyPickerFilter
import com.fc.safe.desktop.ui.MultisigPickerDialog
import com.fc.safe.desktop.ui.SafeButton
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.icons.filled.ArrowDropDown
import core.crypto.KeyTools
import core.fch.RawTxInfo
import data.fchData.Cash
import utils.FchUtils

/**
 * Compose-a-transaction flow. Paired with [SignTxScreen] via the
 * shared `RawTxInfo` JSON shape — Create builds the unsigned tx,
 * copy/ship the JSON, paste into Sign on a separate (ideally
 * airgapped) wallet.
 *
 * v1 scope: pick sender FID, add inputs via [AddTxInputDialog]
 * (manual UTXO entry; no Cash DB yet), add outputs via
 * [AddTxOutputDialog], enter OP_RETURN. Live fee estimate via
 * `TxCreator.calcFee`. Copy JSON or hand off directly to
 * [SignTxScreen] on the same navigator stack.
 *
 * Deferred: "My Cash" picker (needs the Cash DB), FID-list
 * output picker, QR scan inputs, signed-tx-on-the-same-screen.
 */
class CreateTxScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val clipboard = LocalClipboardManager.current

        // Mutable lists via copy-on-write to trigger recomposition.
        var inputs by remember { mutableStateOf<List<Cash>>(emptyList()) }
        var outputs by remember { mutableStateOf<List<Cash>>(emptyList()) }
        var sender by remember { mutableStateOf("") }
        var opReturn by remember { mutableStateOf("") }
        var showAddInput by remember { mutableStateOf(false) }
        var showAddOutput by remember { mutableStateOf(false) }
        var inputMenuOpen by remember { mutableStateOf(false) }
        var outputMenuOpen by remember { mutableStateOf(false) }
        var showCashPicker by remember { mutableStateOf(false) }
        var showFidPicker by remember { mutableStateOf(false) }
        var showKeyPicker by remember { mutableStateOf(false) }
        var showMultisigPicker by remember { mutableStateOf(false) }
        var pendingOutputFid by remember { mutableStateOf("") }
        var feeRateStr by remember { mutableStateOf(TxHandler.DEFAULT_FEE_RATE.toString()) }
        var error by remember { mutableStateOf<String?>(null) }

        val parsedFeeRate: Double? = feeRateStr.toDoubleOrNull()?.takeIf { it > 0 }

        // Fee estimate recomputed on every state change. Cheap —
        // calcFee is pure arithmetic.
        val totals = remember(inputs, outputs, opReturn, parsedFeeRate) {
            computeTotals(inputs, outputs, opReturn, parsedFeeRate)
        }

        fun buildRawTxInfo(): RawTxInfo {
            val raw = RawTxInfo()
            raw.inputs = inputs.toMutableList()
            raw.outputs = outputs.toMutableList()
            if (opReturn.isNotBlank()) raw.opReturn = opReturn
            parsedFeeRate?.let { raw.feeRate = it }
            if (sender.isNotBlank()) raw.sender = sender
            return raw
        }

        fun validate(): String? {
            if (inputs.isEmpty()) return "Add at least one input"
            inputs.forEach {
                if ((it.value ?: 0L) <= 0L) return "Input value must be > 0"
            }
            if (sender.isNotBlank() && !KeyTools.isGoodFid(sender)) {
                return "Sender is not a valid FID"
            }
            // No outputs is allowed: the tx becomes a consolidation — the
            // single (change) output carries all the input value minus fee,
            // paid to the change address (sender, else first input's owner).
            // We only need that change address to exist.
            if (outputs.isEmpty()) {
                val changeTo = sender.takeIf { it.isNotBlank() }
                    ?: inputs.firstOrNull()?.owner
                if (changeTo.isNullOrBlank()) {
                    return "No outputs: set a Sender FID to receive the change"
                }
            }
            if (totals.totalInput < totals.totalOutput + totals.fee) {
                return "Inputs don't cover outputs + fee"
            }
            return null
        }

        AppShell(
            title = "Create TX",
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
                // Sender — free text; most users fill this by adding
                // an input (auto-populates below) and then don't
                // touch it.
                CryptoIoBlock(
                    value = sender,
                    onValueChange = { sender = it; error = null },
                    label = "Sender FID (auto-filled from first input's owner)",
                    singleLine = true,
                    heightDp = 64,
                    onClear = { sender = "" },
                )

                Spacer(Modifier.height(4.dp))
                Text("Inputs", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(4.dp))
                inputs.forEachIndexed { idx, inp ->
                    InputCard(
                        cash = inp,
                        onDelete = {
                            inputs = inputs.filterIndexed { i, _ -> i != idx }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        SafeButton(onClick = { inputMenuOpen = true }) {
                            Text("+ Add input")
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = inputMenuOpen,
                            onDismissRequest = { inputMenuOpen = false },
                        ) {
                            DropdownMenuItem(onClick = {
                                inputMenuOpen = false
                                showCashPicker = true
                            }) { Text("From My Cash…") }
                            DropdownMenuItem(onClick = {
                                inputMenuOpen = false
                                showAddInput = true
                            }) { Text("Type manually") }
                        }
                    }
                    if (inputs.isNotEmpty()) {
                        Text(
                            "Spending ${FchUtils.satoshiToCoin(totals.totalInput)} FCH",
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text("Outputs", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(4.dp))
                outputs.forEachIndexed { idx, out ->
                    OutputCard(
                        output = out,
                        onDelete = {
                            outputs = outputs.filterIndexed { i, _ -> i != idx }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        SafeButton(onClick = { outputMenuOpen = true }) {
                            Text("+ Add output")
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = outputMenuOpen,
                            onDismissRequest = { outputMenuOpen = false },
                        ) {
                            DropdownMenuItem(onClick = {
                                outputMenuOpen = false
                                showFidPicker = true
                            }) { Text("Pick FID from address book…") }
                            DropdownMenuItem(onClick = {
                                outputMenuOpen = false
                                showKeyPicker = true
                            }) { Text("Pick from My Keys…") }
                            DropdownMenuItem(onClick = {
                                outputMenuOpen = false
                                showMultisigPicker = true
                            }) { Text("Pick from My Multisigs…") }
                            DropdownMenuItem(onClick = {
                                outputMenuOpen = false
                                showAddOutput = true
                            }) { Text("Type manually") }
                        }
                    }
                    if (outputs.isNotEmpty()) {
                        Text(
                            "Paying ${FchUtils.satoshiToCoin(totals.totalOutput)} FCH  ·  " +
                                "Fee ${FchUtils.satoshiToCash(totals.fee)} c  ·  " +
                                "Change ${FchUtils.satoshiToCoin(totals.change)} FCH",
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    } else if (inputs.isNotEmpty()) {
                        // No outputs → consolidation: one change output to
                        // the change address (sender, else first input owner).
                        val changeTo = sender.takeIf { it.isNotBlank() }
                            ?: inputs.firstOrNull()?.owner
                        Text(
                            "No outputs — consolidating " +
                                "${FchUtils.satoshiToCoin(totals.change)} FCH to " +
                                (changeTo ?: "?") +
                                "  ·  Fee ${FchUtils.satoshiToCash(totals.fee)} c",
                            style = MaterialTheme.typography.body2,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material.OutlinedTextField(
                        value = feeRateStr,
                        onValueChange = { feeRateStr = it; error = null },
                        label = { Text("Fee rate (FCH/KB)") },
                        singleLine = true,
                        isError = parsedFeeRate == null,
                        modifier = Modifier.width(220.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material.TextButton(onClick = {
                        feeRateStr = TxHandler.DEFAULT_FEE_RATE.toString()
                    }) { Text("Default") }
                    if (parsedFeeRate == null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Invalid — must be > 0",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.error,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                // Disable the carving field when any output is P2SH
                // (CLTV or multisig destination). The shim's calcFee
                // auto-generates an OP_RETURN with the redeem-script
                // list for those outputs and would otherwise silently
                // overwrite whatever the user typed here.
                val hasP2shOutput = outputs.any {
                    !it.redeemScript.isNullOrBlank() || it.owner?.startsWith("3") == true
                }
                LaunchedEffect(hasP2shOutput) {
                    if (hasP2shOutput && opReturn.isNotEmpty()) opReturn = ""
                }
                CryptoIoBlock(
                    value = opReturn,
                    onValueChange = { opReturn = it },
                    label = "OP_RETURN / carving (optional)",
                    placeholder = "Arbitrary text to embed on-chain",
                    heightDp = 100,
                    onClear = { opReturn = "" },
                    enabled = !hasP2shOutput,
                )
                if (hasP2shOutput) {
                    Text(
                        "Custom carving is disabled — this tx has a P2SH output " +
                            "(CLTV or multisig) whose redeem script auto-fills the " +
                            "OP_RETURN slot. Remove the P2SH output to carve.",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SafeButton(onClick = {
                        inputs = emptyList()
                        outputs = emptyList()
                        sender = ""
                        opReturn = ""
                        feeRateStr = TxHandler.DEFAULT_FEE_RATE.toString()
                        error = null
                    }) { Text("Clear") }
                    SafeButton(
                        enabled = inputs.isNotEmpty(),
                        onClick = {
                            val err = validate()
                            if (err != null) { error = err; return@SafeButton }
                            error = null
                            val raw = buildRawTxInfo()
                            // Strip the (possibly null) senderInfo so a
                            // third party doesn't receive our KeyInfo
                            // label in the exported JSON. They can fill
                            // it in themselves when signing.
                            raw.senderInfo = null
                            clipboard.setText(AnnotatedString(raw.toNiceJson() ?: ""))
                        },
                    ) { Text("Copy unsigned JSON") }
                    SafeButton(
                        enabled = inputs.isNotEmpty(),
                        onClick = {
                            val err = validate()
                            if (err != null) { error = err; return@SafeButton }
                            error = null
                            val raw = buildRawTxInfo()
                            // Navigate to SignTxScreen pre-seeded.
                            navigator.push(
                                SignTxScreen(raw.toJsonWithSenderInfo() ?: "")
                            )
                        },
                    ) { Text("Sign…") }
                }
            }
        }

        if (showAddInput) {
            AddTxInputDialog(
                onDone = { cash ->
                    inputs = inputs + cash
                    // Auto-fill sender from first input's owner if empty.
                    if (sender.isBlank() && !cash.owner.isNullOrBlank()) {
                        sender = cash.owner
                    }
                    showAddInput = false
                },
                onDismiss = { showAddInput = false },
            )
        }

        if (showAddOutput) {
            AddTxOutputDialog(
                restSatoshi = (totals.totalInput - totals.totalOutput - totals.fee)
                    .coerceAtLeast(0L),
                initialFid = pendingOutputFid,
                onDone = { cash ->
                    outputs = outputs + cash
                    showAddOutput = false
                    pendingOutputFid = ""
                },
                onDismiss = {
                    showAddOutput = false
                    pendingOutputFid = ""
                },
            )
        }

        if (showCashPicker) {
            // Single-sig requirement: every input must belong to the
            // same owner FID, since the eventual sign step uses ONE
            // private key. Filter the picker by the sender field
            // when set; otherwise show all UTXOs and the user is on
            // their own. (CreateTx auto-fills sender from the first
            // input's owner, so subsequent picks naturally narrow.)
            CashPickerDialog(
                ownerFilter = sender.takeIf { it.isNotBlank() },
                onPicked = { picked ->
                    inputs = inputs + picked
                    if (sender.isBlank()) {
                        // Adopt the picked-batch owner as sender
                        // for the same auto-fill behavior the
                        // manual flow uses.
                        picked.firstOrNull()?.owner?.let { sender = it }
                    }
                    showCashPicker = false
                },
                onDismiss = { showCashPicker = false },
            )
        }

        if (showFidPicker) {
            // Pick the recipient FID from the address book, then
            // open the existing AddTxOutputDialog with that FID
            // pre-selected so the user only types the amount.
            FidPickerDialog(
                onPicked = { fid ->
                    showFidPicker = false
                    pendingOutputFid = fid
                    showAddOutput = true
                },
                onDismiss = { showFidPicker = false },
            )
        }
        if (showKeyPicker) {
            // "Send to myself" convenience — pick any of my wallet
            // keys as the recipient (watch-only or signed). Uses the
            // WithPubkey filter so watch-only keys qualify too; we
            // only need the FID.
            KeyPickerDialog(
                filter = KeyPickerFilter.WithPubkey,
                onPicked = {
                    showKeyPicker = false
                    pendingOutputFid = it.id
                    showAddOutput = true
                },
                onDismiss = { showKeyPicker = false },
            )
        }
        if (showMultisigPicker) {
            // "Send to a saved multisig" convenience — the picked
            // group's FID becomes the output owner. AddTxOutputDialog
            // auto-resolves the same multisig when CLTV is ticked,
            // so no extra wiring is needed here.
            MultisigPickerDialog(
                onPicked = {
                    showMultisigPicker = false
                    pendingOutputFid = it.id
                    showAddOutput = true
                },
                onDismiss = { showMultisigPicker = false },
            )
        }
    }
}

/** Running totals recomputed on every state change. */
private data class Totals(
    val totalInput: Long,
    val totalOutput: Long,
    val fee: Long,
    val change: Long,
)

/**
 * Route through [TxHandler.calcFee] — the shim walks each output's
 * `redeemScript` and sizes P2SH/CLTV outputs correctly (they
 * produce a redeem-script list in opReturn which the simple
 * `calcFee(int, int, int, …)` overload doesn't account for).
 * Matches what `TxHandler.createTx` will compute at sign time, so
 * "Use max" doesn't underestimate into a sub-min-fee tx.
 *
 * Safe on empty lists — returns zeros so the initial render has
 * something to show before the user has added inputs.
 */
private fun computeTotals(
    inputs: List<Cash>,
    outputs: List<Cash>,
    opReturn: String,
    feeRate: Double?,
): Totals {
    val totalInput = inputs.sumOf { it.value ?: 0L }
    val totalOutput = outputs.sumOf { it.value ?: 0L }
    // Empty outputs is a valid consolidation tx: the lone change output
    // carries totalInput - fee. We still want a live fee/change estimate,
    // so only short-circuit when there's nothing to spend yet.
    if (inputs.isEmpty()) {
        return Totals(totalInput, totalOutput, 0L, 0L)
    }
    // TxHandler.calcFee requires changeTo-or-sender to be set (it calls
    // getChangeFee which does startsWith("3")). Real build path fills
    // this in via createTx; the standalone preview has to do it itself.
    // If the first input carries no owner (manual paste without owner
    // field), we can't infer the change-output type, so skip the fee
    // estimate entirely — the "Sign" button will still work since
    // createTx fills in changeTo at build time.
    val changeProxy = inputs.firstOrNull()?.owner
    if (changeProxy.isNullOrBlank()) {
        return Totals(totalInput, totalOutput, 0L, (totalInput - totalOutput).coerceAtLeast(0L))
    }
    val raw = RawTxInfo().apply {
        this.inputs = inputs.toMutableList()
        this.outputs = outputs.toMutableList()
        if (opReturn.isNotBlank()) this.opReturn = opReturn
        changeTo = changeProxy
        feeRate?.let { this.feeRate = it }
    }
    val fee = TxHandler.calcFee(raw).fee() ?: 0L
    val change = (totalInput - totalOutput - fee).coerceAtLeast(0L)
    return Totals(totalInput, totalOutput, fee, change)
}

@Composable
private fun InputCard(cash: Cash, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cash.owner?.takeIf { it.isNotBlank() }?.let { owner ->
                FidAvatar(fid = owner, size = 32.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${cash.birthTxId ?: "?"}:${cash.birthIndex ?: -1}",
                    style = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Text(
                    "${FchUtils.satoshiToCoin(cash.value ?: 0L)} FCH" +
                        (cash.owner?.let { "  ·  owner $it" } ?: ""),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
            TextButton(onClick = onDelete) { Text("Remove") }
        }
    }
}

@Composable
private fun OutputCard(output: Cash, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        elevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            output.owner?.takeIf { it.isNotBlank() }?.let { owner ->
                FidAvatar(fid = owner, size = 32.dp)
                Spacer(Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    output.owner ?: "(no recipient)",
                    style = MaterialTheme.typography.body2.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
                Text(
                    "${FchUtils.satoshiToCoin(output.value ?: 0L)} FCH",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                )
            }
            TextButton(onClick = onDelete) { Text("Remove") }
        }
    }
}
