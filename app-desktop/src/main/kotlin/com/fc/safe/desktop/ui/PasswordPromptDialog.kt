package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fc.safe.platform.macos.WalletSession

/**
 * Generic "re-enter the wallet password" gate. Used before revealing
 * high-value material (private keys) so that a momentarily-unattended
 * unlocked wallet can't be rifled through without at least the
 * password in hand.
 *
 * Verification compares the entered string against the in-memory
 * session password via [WalletSession.currentPasswordCopy] — this is
 * safe because the session is already unlocked; we're only asserting
 * the user in front of the screen is the same one who unlocked it.
 *
 * On success [onVerified] fires with the zeroable [CharArray] so
 * callers that want to forward the password (e.g. re-encrypt flows)
 * can use it. For pure gating, callers can ignore the argument and
 * wipe it immediately.
 */
@Composable
fun PasswordPromptDialog(
    title: String,
    message: String,
    confirmLabel: String = "Continue",
    onDismiss: () -> Unit,
    onVerified: (CharArray) -> Unit,
) {
    var pwd by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun attempt() {
        val stored = WalletSession.currentPasswordCopy()
        if (stored == null) {
            error = "Wallet is locked"
            return
        }
        try {
            val entered = pwd.toCharArray()
            val match = entered.size == stored.size &&
                entered.indices.all { entered[it] == stored[it] }
            if (!match) {
                entered.fill(Char.MIN_VALUE)
                error = "Incorrect password"
                return
            }
            pwd = ""
            onVerified(entered)
        } finally {
            stored.fill(Char.MIN_VALUE)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.widthIn(min = 320.dp)) {
                Text(message, style = MaterialTheme.typography.body2)
                Spacer(Modifier.height(12.dp))
                PasswordField(
                    value = pwd,
                    onValueChange = { pwd = it; error = null },
                    label = "Wallet password",
                    imeAction = ImeAction.Done,
                    onSubmit = ::attempt,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                }
            }
        },
        confirmButton = {
            SafeButton(onClick = ::attempt) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
