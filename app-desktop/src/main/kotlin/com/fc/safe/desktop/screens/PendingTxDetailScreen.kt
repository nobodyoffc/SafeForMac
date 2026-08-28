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
import androidx.compose.material.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.DesktopKeyInfo
import com.fc.safe.desktop.DesktopMultisig
import com.fc.safe.desktop.DesktopPendingTx
import com.fc.safe.desktop.PENDING_TXS_DB_NAME
import com.fc.safe.desktop.fch.TxHandler
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CryptoIoBlock
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
import utils.FchUtils

private val log = LoggerFactory.getLogger("PendingTxDetailScreen")

// Redeclared locally — each screen in this module declares the DB
// names it opens. Keeping the pattern consistent makes each screen
// self-contained (see MyCashScreen / MyKeysScreen / MyMultisigsScreen).
private const val CASH_DB_NAME = "cashes"
private const val KEYS_DB_NAME = "keys"
private const val MULTISIGS_DB_NAME = "multisigs"

/**
 * View + reconcile a single [DesktopPendingTx]. Loaded by [txid] so
 * the record always reflects current DB state even if the list was
 * opened earlier.
 *
 * Three user actions:
 * - **Apply** — compute the cash-DB diff (remove inputs the user
 *   owns, add outputs paying any owned FID), show a confirm preview,
 *   commit on OK, then delete the pending record.
 * - **Discard** — delete the pending record without touching cash.
 * - *(View)* — everything else is view-only; the signed hex and
 *   RawTxInfo preview are always on display.
 *
 * [onChanged] is fired on any DB mutation so the caller (list screen)
 * can refresh its records before this screen pops.
 */
class PendingTxDetailScreen(
    private val txid: String,
    private val onChanged: () -> Unit,
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var rec by remember { mutableStateOf<DesktopPendingTx?>(null) }
        var raw by remember { mutableStateOf<RawTxInfo?>(null) }
        var loading by remember { mutableStateOf(true) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf<String?>(null) }
        var applyPreview by remember { mutableStateOf<ApplyPreview?>(null) }
        var confirmDiscard by remember { mutableStateOf(false) }

        LaunchedEffect(txid) {
            loading = true
            val loaded = withContext(Dispatchers.IO) {
                val db: LocalDB<DesktopPendingTx> = WalletSession.openDb(
                    PENDING_TXS_DB_NAME, DesktopPendingTx::class.java
                )
                db.get(txid)
            }
            rec = loaded
            raw = loaded?.rawJson?.let {
                runCatching { RawTxInfo.fromJson(it, RawTxInfo::class.java) }.getOrNull()
            }
            loading = false
        }

        fun buildPreview() {
            val r = rec ?: return
            val parsedRaw = raw ?: run {
                error = "Cannot parse stored rawJson — record may be corrupted."
                return
            }
            val hex = r.signedHex ?: run {
                error = "Record has no signed hex."
                return
            }
            error = null
            status = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { computeApplyPreview(parsedRaw, hex) }
                }
                busy = false
                outcome.onSuccess { applyPreview = it }
                outcome.onFailure {
                    log.warn("Compute apply preview failed", it)
                    error = "Preview failed: ${it.message}"
                }
            }
        }

        fun commitApply(preview: ApplyPreview) {
            applyPreview = null
            error = null
            status = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { applyToCashDb(txid, preview) }
                }
                busy = false
                outcome.onSuccess {
                    onChanged()
                    status = "Applied — removed ${preview.existingSpentIds.size} UTXO(s), " +
                        "added ${preview.bornCashes.size} UTXO(s)."
                    // Pop after a moment so the user sees the status.
                    // Simpler UX than auto-pop — they can also read the
                    // confirmation and back out manually.
                    navigator.pop()
                }
                outcome.onFailure {
                    log.warn("Apply pending tx failed", it)
                    error = "Apply failed: ${it.message}"
                }
            }
        }

        fun commitDiscard() {
            confirmDiscard = false
            error = null
            status = null
            busy = true
            scope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching {
                        val db: LocalDB<DesktopPendingTx> = WalletSession.openDb(
                            PENDING_TXS_DB_NAME, DesktopPendingTx::class.java
                        )
                        db.remove(txid)
                        db.commit()
                    }
                }
                busy = false
                outcome.onSuccess {
                    onChanged()
                    navigator.pop()
                }
                outcome.onFailure {
                    log.warn("Discard pending tx failed", it)
                    error = "Discard failed: ${it.message}"
                }
            }
        }

        AppShell(
            title = "Pending TX",
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
                val r = rec
                when {
                    loading -> Text("Loading…")
                    r == null -> Text(
                        "Record not found — it may have been applied or discarded already.",
                        color = MaterialTheme.colors.error,
                    )
                    else -> {
                        Text(
                            "TXID: ${r.id}",
                            style = MaterialTheme.typography.caption,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${r.flavor?.lowercase()} · sender ${r.senderFid ?: "(unknown)"} · " +
                                "${r.inputCount} in · ${r.outputCount} out · " +
                                "${FchUtils.satoshiToCoin(r.totalOut)} FCH out",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )

                        raw?.let {
                            Spacer(Modifier.height(12.dp))
                            Divider()
                            Spacer(Modifier.height(12.dp))
                            TxPreview(it)
                        }

                        r.signedHex?.let { hex ->
                            Spacer(Modifier.height(12.dp))
                            Divider()
                            Spacer(Modifier.height(12.dp))
                            CryptoIoBlock(
                                value = hex,
                                onValueChange = {},
                                label = "Signed tx (hex — broadcast this)",
                                readOnly = true,
                                heightDp = 180,
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                        Divider()
                        Spacer(Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SafeButton(
                                enabled = !busy && raw != null && r.signedHex != null,
                                onClick = ::buildPreview,
                            ) { Text("Apply…") }
                            SafeButton(
                                enabled = !busy,
                                onClick = { confirmDiscard = true },
                            ) { Text("Discard…") }
                        }

                        error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colors.error)
                        }
                        status?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = MaterialTheme.colors.primary)
                        }
                    }
                }
            }
        }

        applyPreview?.let { preview ->
            AlertDialog(
                onDismissRequest = { if (!busy) applyPreview = null },
                title = { Text("Apply to cash list?") },
                text = {
                    Column {
                        Text(
                            "This will update your local UTXO list to reflect the " +
                                "on-chain effect of this transaction.",
                            style = MaterialTheme.typography.body2,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "• Remove ${preview.existingSpentIds.size} UTXO(s) " +
                                "(of ${preview.allSpentIds.size} referenced as inputs)",
                            style = MaterialTheme.typography.body2,
                        )
                        Text(
                            "• Add ${preview.bornCashes.size} UTXO(s) paying your FIDs " +
                                "(${preview.ownedFidCount} FIDs scanned)",
                            style = MaterialTheme.typography.body2,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Only proceed after confirming the transaction was " +
                                "broadcast and included on-chain.",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { commitApply(preview) }) { Text("Apply") }
                },
                dismissButton = {
                    TextButton(onClick = { applyPreview = null }) { Text("Cancel") }
                },
            )
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { if (!busy) confirmDiscard = false },
                title = { Text("Discard pending tx?") },
                text = {
                    Text(
                        "This removes the pending record only — your cash list " +
                            "is not changed. Use this if the tx wasn't broadcast " +
                            "or failed to confirm.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = ::commitDiscard) { Text("Discard") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * The diff an Apply will produce, computed up-front so the user sees
 * exact counts before committing. Also held onto across the confirm
 * dialog so we don't re-derive (and potentially race with DB changes)
 * between preview and commit.
 */
private data class ApplyPreview(
    val allSpentIds: List<String>,
    val existingSpentIds: List<String>,
    val bornCashes: List<Cash>,
    val ownedFidCount: Int,
)

/**
 * Derives the cash-DB diff without mutating anything. Reads: pending
 * rawJson's inputs (spent ids) + keys/multisigs DBs (owned FIDs) +
 * cashes DB (to count which spent ids actually exist locally).
 *
 * Runs `TxHandler.getIssuedCashListForFid` once per owned FID against
 * the signed hex to collect born UTXOs. Multiple owned FIDs with
 * overlapping matches would produce duplicate Cash rows, but that
 * can't happen in practice — each output's address decodes to exactly
 * one FID, so at most one owned FID will match it.
 */
private fun computeApplyPreview(raw: RawTxInfo, signedHex: String): ApplyPreview {
    val allSpent = raw.inputs.orEmpty().mapNotNull { input ->
        input.birthTxId?.let { tx -> input.makeId(tx, input.birthIndex) }
    }

    val cashDb: LocalDB<Cash> = WalletSession.openDb(CASH_DB_NAME, Cash::class.java)
    val cashKeys = cashDb.all.keys
    val existingSpent = allSpent.filter { it in cashKeys }

    val keysDb: LocalDB<DesktopKeyInfo> = WalletSession.openDb(
        KEYS_DB_NAME, DesktopKeyInfo::class.java
    )
    val msDb: LocalDB<DesktopMultisig> = WalletSession.openDb(
        MULTISIGS_DB_NAME, DesktopMultisig::class.java
    )
    val ownedFids: Set<String> = (keysDb.all.keys + msDb.all.keys)
        .filter { it.isNotBlank() }
        .toSet()

    val born = ownedFids.flatMap { fid ->
        TxHandler.getIssuedCashListForFid(signedHex, fid) ?: emptyList()
    }

    return ApplyPreview(
        allSpentIds = allSpent,
        existingSpentIds = existingSpent,
        bornCashes = born,
        ownedFidCount = ownedFids.size,
    )
}

/**
 * Commits the diff: removes [ApplyPreview.existingSpentIds] from the
 * cashes DB, inserts [ApplyPreview.bornCashes], then removes the
 * pending record. Single committed transaction per DB touched — if
 * anything throws, the caller surfaces the error and the user can
 * retry.
 */
private fun applyToCashDb(txid: String, preview: ApplyPreview) {
    val cashDb: LocalDB<Cash> = WalletSession.openDb(CASH_DB_NAME, Cash::class.java)
    preview.existingSpentIds.forEach { cashDb.remove(it) }
    preview.bornCashes.forEach { c ->
        val id = c.id ?: c.makeId(c.birthTxId, c.birthIndex)
        cashDb.put(id, c)
    }
    cashDb.commit()

    val pendingDb: LocalDB<DesktopPendingTx> = WalletSession.openDb(
        PENDING_TXS_DB_NAME, DesktopPendingTx::class.java
    )
    pendingDb.remove(txid)
    pendingDb.commit()
}
