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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.fc.safe.desktop.DesktopMultisig
import com.fc.safe.platform.macos.WalletSession
import constants.Constants
import core.crypto.KeyTools
import data.fchData.Cash
import data.fchData.Multisig
import db.LocalDB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import utils.FchUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Constructs a [Cash] output (owner + amount) from user-typed recipient
 * FID + amount. FC-JDK's `RawTxInfo.outputs` is `List<Cash>` — the
 * older Android FC-AJDK shape used `List<SendTo>` but FC-JDK has moved
 * to a unified Cash type for both sides. Use the `Cash(String fid,
 * Double amount)` ctor which populates owner+value with no birth info.
 *
 * Mirrors Android validation:
 * - `KeyTools.isGoodFid` on the recipient
 * - min/max amount check against `Constants.MIN_AMOUNT/MAX_AMOUNT`
 * - a "max" helper tied to [restSatoshi] so users can one-click the
 *   largest output that still leaves room for the fee.
 *
 * `restSatoshi` is `totalInput − sum(existingOutputs) − estimatedFee`;
 * <=0 hides the Max helper.
 */
/**
 * Kind of CLTV value entered. Per BIP-65 the same lockTime field
 * carries both block heights and Unix timestamps, disambiguated
 * by magnitude: values below [CLTV_THRESHOLD] (~Nov 1985) are
 * treated as block heights, values at/above are Unix seconds.
 */
enum class CltvKind { BLOCK_HEIGHT, UNIX_TIME }

private const val CLTV_THRESHOLD = 500_000_000L

@Composable
fun AddTxOutputDialog(
    restSatoshi: Long,
    /**
     * Optional pre-filled FID — used when the FID picker dialog
     * already chose a recipient and only the amount remains. The
     * field stays editable in case the user wants to change it.
     */
    initialFid: String = "",
    onDone: (Cash) -> Unit,
    onDismiss: () -> Unit,
) {
    var fid by remember { mutableStateOf(initialFid) }
    var amountStr by remember { mutableStateOf("") }
    var enableCltv by remember { mutableStateOf(false) }
    var cltvKind by remember { mutableStateOf(CltvKind.BLOCK_HEIGHT) }
    var lockValue by remember { mutableStateOf("") }
    var resolvedMultisig by remember { mutableStateOf<DesktopMultisig?>(null) }
    var lookupHint by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Auto-lookup the recipient multisig whenever the FID is a
    // P2SH-prefix (3xxx) string. For CLTV-to-multisig we need the
    // pubkeys + M/N to build the combined redeem script; without a
    // saved record we can't construct it. For non-CLTV sends to a
    // multisig the lookup is informational only — the address
    // alone is enough for the scriptPubKey.
    LaunchedEffect(fid) {
        resolvedMultisig = null
        lookupHint = null
        if (fid.isBlank() || !fid.startsWith("3") || !KeyTools.isGoodFid(fid)) return@LaunchedEffect
        try {
            val ms = withContext(Dispatchers.IO) {
                val db: LocalDB<DesktopMultisig> =
                    WalletSession.openDb("multisigs", DesktopMultisig::class.java)
                db.get(fid)
            }
            if (ms != null) {
                resolvedMultisig = ms
                lookupHint = "Resolved saved multisig (${ms.m}-of-${ms.n})"
            } else {
                lookupHint = "Recipient is a P2SH address not saved locally — " +
                    "plain send OK, but CLTV-to-this-multisig needs the group definition."
            }
        } catch (_: Throwable) {
            // Non-fatal — lookup is best-effort.
        }
    }

    fun submit() {
        error = null
        val amount = amountStr.toDoubleOrNull()
        if (fid.isBlank() || amountStr.isBlank()) {
            error = "Fill both fields"; return
        }
        if (!KeyTools.isGoodFid(fid)) {
            error = "Recipient is not a valid FID"; return
        }
        if (amount == null || amount < Constants.MIN_AMOUNT || amount > Constants.MAX_AMOUNT) {
            error = "Amount must be between ${Constants.MIN_AMOUNT} and ${Constants.MAX_AMOUNT} FCH"
            return
        }
        if (restSatoshi > 0 && amount > FchUtils.satoshiToCoin(restSatoshi)) {
            error = "Amount exceeds the remaining balance from inputs"; return
        }

        val lockTime: Long? = if (enableCltv) {
            val lv = lockValue.toLongOrNull()
            when {
                lv == null -> { error = "Lock value must be a whole number"; return }
                lv <= 0L -> { error = "Lock value must be > 0"; return }
                cltvKind == CltvKind.BLOCK_HEIGHT && lv >= CLTV_THRESHOLD -> {
                    error = "Block height must be < $CLTV_THRESHOLD"; return
                }
                cltvKind == CltvKind.UNIX_TIME && lv < CLTV_THRESHOLD -> {
                    error = "Unix seconds must be ≥ $CLTV_THRESHOLD"; return
                }
                else -> lv
            }
        } else null

        val isMultisigRecipient = fid.startsWith("3")
        val cash = when {
            lockTime != null && isMultisigRecipient -> {
                val ms = resolvedMultisig ?: run {
                    error = "CLTV-to-multisig needs the group saved locally. " +
                        "Import the multisig definition first."
                    return
                }
                Cash(fid, amount, lockTime, toFcMultisig(ms))
            }
            lockTime != null -> Cash(fid, amount, lockTime)
            else -> Cash(fid, amount)
        }
        onDone(cash)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.requiredWidth(560.dp).height(if (enableCltv) 560.dp else 360.dp),
            shape = MaterialTheme.shapes.medium,
            elevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                Text("Add output", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))

                Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = fid,
                        onValueChange = { fid = it; error = null },
                        label = { Text("Recipient FID") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.body2.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    lookupHint?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it; error = null },
                        label = { Text("Amount (FCH)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (restSatoshi > 0) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Max: ${FchUtils.satoshiToCoin(restSatoshi)} FCH",
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                amountStr = FchUtils.satoshiToCoin(restSatoshi).toString()
                                error = null
                            }) { Text("Use max") }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = enableCltv,
                            onCheckedChange = { enableCltv = it; error = null },
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Time-lock this output (CLTV)",
                            style = MaterialTheme.typography.body2,
                        )
                    }

                    if (enableCltv) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Recipient cannot spend until the lock releases. Uses " +
                                "OP_CHECKLOCKTIMEVERIFY — a new P2SH address is derived " +
                                "from (recipient, lockTime).",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = cltvKind == CltvKind.BLOCK_HEIGHT,
                                onClick = { cltvKind = CltvKind.BLOCK_HEIGHT; error = null },
                            )
                            Text("Block height", style = MaterialTheme.typography.body2)
                            Spacer(Modifier.width(16.dp))
                            RadioButton(
                                selected = cltvKind == CltvKind.UNIX_TIME,
                                onClick = { cltvKind = CltvKind.UNIX_TIME; error = null },
                            )
                            Text("Unix time (s)", style = MaterialTheme.typography.body2)
                        }
                        OutlinedTextField(
                            value = lockValue,
                            onValueChange = { lockValue = it; error = null },
                            label = {
                                Text(
                                    when (cltvKind) {
                                        CltvKind.BLOCK_HEIGHT -> "Block height (< $CLTV_THRESHOLD)"
                                        CltvKind.UNIX_TIME -> "Unix seconds (≥ $CLTV_THRESHOLD)"
                                    }
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        lockValuePreview(cltvKind, lockValue)?.let {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colors.error)
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = ::submit) { Text("Add") }
                }
            }
        }
    }
}

/**
 * Round-trip [DesktopMultisig] → FC-JDK [Multisig]. Same helper
 * [com.fc.safe.desktop.screens.CreateMultisigTxScreen] uses; kept
 * local here so the dialog doesn't depend on that screen file.
 */
private fun toFcMultisig(group: DesktopMultisig): Multisig = Multisig().apply {
    setId(group.id)
    m = group.m
    n = group.n
    redeemScript = group.redeemScript
    pubkeys = group.pubkeys
    fids = group.fids
}

private val cltvDateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

private fun lockValuePreview(kind: CltvKind, raw: String): String? {
    val v = raw.toLongOrNull() ?: return null
    return when (kind) {
        CltvKind.UNIX_TIME -> if (v >= CLTV_THRESHOLD)
            "≈ " + cltvDateFmt.format(Date(v * 1000))
        else null
        CltvKind.BLOCK_HEIGHT -> null
    }
}
