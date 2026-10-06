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
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
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
import com.fc.safe.desktop.DesktopMultisig
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
import core.fch.RawTxInfo
import data.fchData.Cash
import data.fchData.Multisig
import utils.FchUtils

/**
 * Creator step of the multisig-TX flow. Builds an unsigned
 * [RawTxInfo] with `senderMultisig` populated, then hands it off as
 * JSON for the M signers to pick up in the (upcoming) SignMultisigTx
 * screen.
 *
 * Key differences vs single-sig [CreateTxScreen]:
 * - Sender is a saved [DesktopMultisig], not free-text. The
 *   multisig's FID (starts with "3") becomes the sender.
 * - Inputs are constrained to UTXOs owned by that multisig FID.
 * - Fee estimate uses the mature [TxHandler] shim rather than
 *   FC-JDK's [core.fch.TxCreator], because calcFee needs
 *   multisig-aware input sizing.
 * - No inline "Sign…" — multisig needs M separate signing passes
 *   coordinated via JSON handoff; single-pass sign lives in the
 *   next batch (SignMultisigTxScreen).
 */
class CreateMultisigTxScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val clipboard = LocalClipboardManager.current

        var picked by remember { mutableStateOf<DesktopMultisig?>(null) }
        var inputs by remember { mutableStateOf<List<Cash>>(emptyList()) }
        var outputs by remember { mutableStateOf<List<Cash>>(emptyList()) }
        var opReturn by remember { mutableStateOf("") }

        var showGroupPicker by remember { mutableStateOf(false) }
        var showAddInput by remember { mutableStateOf(false) }
        var showAddOutput by remember { mutableStateOf(false) }
        var inputMenuOpen by remember { mutableStateOf(false) }
        var outputMenuOpen by remember { mutableStateOf(false) }
        var showCashPicker by remember { mutableStateOf(false) }
        var showFidPicker by remember { mutableStateOf(false) }
        var showKeyPicker by remember { mutableStateOf(false) }
        var showRecipientMultisigPicker by remember { mutableStateOf(false) }
        var pendingOutputFid by remember { mutableStateOf("") }
        var feeRateStr by remember { mutableStateOf(TxHandler.DEFAULT_FEE_RATE.toString()) }
        var error by remember { mutableStateOf<String?>(null) }

        val parsedFeeRate: Double? = feeRateStr.toDoubleOrNull()?.takeIf { it > 0 }

        val totals = remember(picked, inputs, outputs, opReturn, parsedFeeRate) {
            computeMultisigTotals(picked, inputs, outputs, opReturn, parsedFeeRate)
        }

        fun buildRawTxInfo(): RawTxInfo? {
            val group = picked ?: return null
            val raw = RawTxInfo()
            raw.inputs = inputs.toMutableList()
            raw.outputs = outputs.toMutableList()
            if (opReturn.isNotBlank()) raw.opReturn = opReturn
            raw.sender = group.id
            raw.senderMultisig = toFcMultisig(group)
            parsedFeeRate?.let { raw.feeRate = it }
            return raw
        }

        fun validate(): String? {
            if (picked == null) return "Pick a multisig group first"
            if (inputs.isEmpty()) return "Add at least one input"
            if (outputs.isEmpty()) return "Add at least one output"
            inputs.forEach {
                if ((it.value ?: 0L) <= 0L) return "Input value must be > 0"
            }
            val groupFid = picked!!.id
            val badInput = inputs.firstOrNull { it.owner != null && it.owner != groupFid }
            if (badInput != null) {
                return "Input owner ${badInput.owner} doesn't match the picked multisig FID"
            }
            if (totals.totalInput < totals.totalOutput + totals.fee) {
                return "Inputs don't cover outputs + fee"
            }
            return null
        }

        AppShell(
            title = "Create multisig TX",
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
                // Sender: picked multisig group.
                Text("Sender multisig", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(4.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    elevation = 1.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            picked?.let { g ->
                                Text(
                                    g.label?.ifBlank { null } ?: "(no label)",
                                    style = MaterialTheme.typography.body1,
                                )
                                Text(
                                    "${g.m}-of-${g.n}  ·  ${g.id}",
                                    style = MaterialTheme.typography.caption.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                )
                            } ?: Text(
                                "No multisig group picked yet.",
                                style = MaterialTheme.typography.body2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        SafeButton(onClick = { showGroupPicker = true }) {
                            Text(if (picked == null) "Pick group…" else "Change")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Text("Inputs", style = MaterialTheme.typography.subtitle2)
                Spacer(Modifier.height(4.dp))
                inputs.forEachIndexed { idx, inp ->
                    InputCardMs(
                        cash = inp,
                        onDelete = {
                            inputs = inputs.filterIndexed { i, _ -> i != idx }
                        },
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box {
                        SafeButton(
                            enabled = picked != null,
                            onClick = { inputMenuOpen = true },
                        ) {
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
                    OutputCardMs(
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
                                showRecipientMultisigPicker = true
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
                // Same rule as single-sig CreateTxScreen: OP_RETURN
                // is auto-filled with the redeem-script list when any
                // output is P2SH (CLTV or `3`-prefix recipient), so
                // the user's carving would be clobbered. Disable the
                // field + auto-clear any value when that state is
                // entered.
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
                    enableQr = true,
                    onQrError = { error = it },
                )
                if (hasP2shOutput) {
                    Text(
                        "Custom carving is disabled — this tx has a P2SH output " +
                            "whose redeem script auto-fills the OP_RETURN slot.",
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
                        picked = null
                        inputs = emptyList()
                        outputs = emptyList()
                        opReturn = ""
                        feeRateStr = TxHandler.DEFAULT_FEE_RATE.toString()
                        error = null
                    }) { Text("Clear") }
                    SafeButton(
                        enabled = picked != null && inputs.isNotEmpty() && outputs.isNotEmpty(),
                        onClick = {
                            val err = validate()
                            if (err != null) { error = err; return@SafeButton }
                            error = null
                            val raw = buildRawTxInfo() ?: return@SafeButton
                            raw.senderInfo = null
                            clipboard.setText(AnnotatedString(raw.toNiceJson() ?: ""))
                        },
                    ) { Text("Copy unsigned JSON") }
                }
            }
        }

        if (showGroupPicker) {
            MultisigPickerDialog(
                onPicked = { g ->
                    picked = g
                    // Drop any inputs owned by a different FID — they
                    // wouldn't match the newly-picked group anyway.
                    inputs = inputs.filter { it.owner == null || it.owner == g.id }
                    showGroupPicker = false
                },
                onDismiss = { showGroupPicker = false },
            )
        }

        if (showAddInput) {
            AddTxInputDialog(
                onDone = { cash ->
                    // Force owner to the picked multisig's FID so the
                    // validate() check passes on manual entries too.
                    picked?.id?.let { cash.owner = it }
                    inputs = inputs + cash
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
            // Filtered by the picked multisig's FID — only UTXOs that
            // can actually unlock under this redeem script show up.
            CashPickerDialog(
                ownerFilter = picked?.id,
                onPicked = { cashList ->
                    inputs = inputs + cashList
                    showCashPicker = false
                },
                onDismiss = { showCashPicker = false },
            )
        }

        if (showFidPicker) {
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
        if (showRecipientMultisigPicker) {
            // Pick a saved multisig group as the *recipient* (distinct
            // from the sender picker above, which binds the tx's
            // senderMultisig). AddTxOutputDialog auto-resolves the
            // same group if CLTV is later ticked on that output.
            MultisigPickerDialog(
                onPicked = {
                    showRecipientMultisigPicker = false
                    pendingOutputFid = it.id
                    showAddOutput = true
                },
                onDismiss = { showRecipientMultisigPicker = false },
            )
        }
    }
}

/**
 * Convert our desktop entity back to FC-JDK's `Multisig` so [RawTxInfo]
 * and [TxHandler] can read the pubkey/M/N fields. The round-trip is
 * lossless since DesktopMultisig carries the exact same crypto-relevant
 * fields (see DesktopMultisig kdoc).
 */
private fun toFcMultisig(group: DesktopMultisig): Multisig = Multisig().apply {
    setId(group.id)
    m = group.m
    n = group.n
    redeemScript = group.redeemScript
    pubkeys = group.pubkeys
    fids = group.fids
}

private data class MsTotals(
    val totalInput: Long,
    val totalOutput: Long,
    val fee: Long,
    val change: Long,
)

/**
 * Fee estimate via the [TxHandler] shim. We briefly construct a
 * [RawTxInfo] just so `calcFee` has the multisig context — without
 * `senderMultisig` set the per-input size estimate falls back to
 * ~141 bytes (P2PKH), which underestimates dramatically for multisig.
 *
 * Safe on empty lists — returns zeros so the UI can render before
 * the user has added anything.
 */
private fun computeMultisigTotals(
    picked: DesktopMultisig?,
    inputs: List<Cash>,
    outputs: List<Cash>,
    opReturn: String,
    feeRate: Double?,
): MsTotals {
    val totalInput = inputs.sumOf { it.value ?: 0L }
    val totalOutput = outputs.sumOf { it.value ?: 0L }
    if (picked == null || inputs.isEmpty() || outputs.isEmpty()) {
        return MsTotals(totalInput, totalOutput, 0L, 0L)
    }
    val raw = RawTxInfo().apply {
        this.inputs = inputs.toMutableList()
        this.outputs = outputs.toMutableList()
        if (opReturn.isNotBlank()) this.opReturn = opReturn
        sender = picked.id
        senderMultisig = toFcMultisig(picked)
        feeRate?.let { this.feeRate = it }
    }
    val fee = TxHandler.calcFee(raw).fee() ?: 0L
    val change = (totalInput - totalOutput - fee).coerceAtLeast(0L)
    return MsTotals(totalInput, totalOutput, fee, change)
}

@Composable
private fun InputCardMs(cash: Cash, onDelete: () -> Unit) {
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
                    style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
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
private fun OutputCardMs(output: Cash, onDelete: () -> Unit) {
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
                    style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
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
