package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import core.fch.RawTxInfo
import data.fchData.Cash
import utils.FchUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Read-only tx summary used by both `ImportTxInfoScreen` (preview
 * only) and `SignTxScreen` (preview + sign). Derived from the
 * Android `SignTxActivity.setupSender/SendTo/Summary/Carve` layout
 * but flattened into a single Compose column because Compose Desktop
 * doesn't need the imperative View-inflation dance.
 *
 * Takes a raw [RawTxInfo] — the caller is responsible for parsing
 * JSON and handling parse errors before rendering this block.
 */
@Composable
fun TxPreview(rawTx: RawTxInfo, modifier: Modifier = Modifier) {
    val inputs: List<Cash> = rawTx.inputs ?: emptyList()
    val outputs: List<Cash> = rawTx.outputs ?: emptyList()
    val spendingSum = inputs.sumOf { it.value ?: 0L }
    val payingSum = outputs.sumOf { it.value ?: 0L }
    val fee = spendingSum - payingSum
    val sender = rawTx.sender

    Column(modifier = modifier.fillMaxWidth()) {
        if (!sender.isNullOrBlank()) {
            SectionLabel("Sender")
            FidLine(sender)
            Spacer(Modifier.height(8.dp))
        }

        if (outputs.isNotEmpty()) {
            SectionLabel("Send to")
            outputs.forEach { out ->
                val owner = out.owner
                val amount = FchUtils.satoshiToCoin(out.value ?: 0L)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!owner.isNullOrBlank()) {
                        FidAvatar(fid = owner, size = 28.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text = owner ?: "(no owner)",
                        style = MaterialTheme.typography.body2.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$amount FCH",
                        style = MaterialTheme.typography.body2,
                    )
                }
                // Surface CLTV lock so signers can see what they're
                // approving before clicking Sign.
                val lock = out.lockTime
                if (lock != null && lock > 0L) {
                    Text(
                        "  ↳ locked until " + formatLockTime(lock),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.padding(start = 36.dp, bottom = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (!rawTx.opReturn.isNullOrBlank()) {
            SectionLabel("Carving (OP_RETURN)")
            Text(
                rawTx.opReturn,
                style = MaterialTheme.typography.body2,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        if (inputs.isNotEmpty()) {
            SectionLabel("Spending inputs")
            inputs.forEach { inp ->
                // Cash.makeId requires (txId, index) — Android's no-arg
                // convenience isn't in FC-JDK. Fall through to the
                // birthTxId:birthIndex form if id is null (same shape
                // the chain uses as the canonical UTXO reference).
                val cashId = inp.id
                    ?: runCatching {
                        inp.makeId(inp.birthTxId, inp.birthIndex)
                    }.getOrNull()
                    ?: "(unknown)"
                val amount = FchUtils.satoshiToCoin(inp.value ?: 0L)
                KeyValueLine(cashId, "$amount FCH")
            }
            Spacer(Modifier.height(8.dp))
        }

        KeyValueLine("Spending sum", "${FchUtils.satoshiToCoin(spendingSum)} FCH")
        KeyValueLine("Paying sum", "${FchUtils.satoshiToCoin(payingSum)} FCH")
        KeyValueLine(
            "Fee",
            "${FchUtils.satoshiToCash(fee)} cash (= ${FchUtils.satoshiToCoin(fee)} FCH)",
        )
        rawTx.feeRate?.let {
            KeyValueLine("Fee rate", "$it FCH/KB")
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.subtitle2.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colors.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun MonoLine(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun FidLine(fid: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FidAvatar(fid = fid, size = 28.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = fid,
            style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun KeyValueLine(name: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.body2.copy(
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.body2,
        )
    }
}

private val lockDateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

/**
 * BIP-65 dual-semantics formatter — values < 500,000,000 are block
 * heights, ≥ are Unix seconds. Same threshold [AddTxOutputDialog]
 * uses on the other side of the wire.
 */
private fun formatLockTime(lockTime: Long): String {
    return if (lockTime < 500_000_000L) "block $lockTime"
    else lockDateFmt.format(Date(lockTime * 1000)) + " (ts $lockTime)"
}
