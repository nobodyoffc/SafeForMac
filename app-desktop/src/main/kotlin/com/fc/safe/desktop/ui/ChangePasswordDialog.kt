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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fc.safe.platform.macos.VaultUnlocker
import com.fc.safe.platform.macos.WalletSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Changes the open wallet's password. Only the wrapped data key changes
 * ([VaultUnlocker.changePassword]); every record stays as it is, so this
 * is instant apart from the Argon2id runs that check both passwords.
 *
 * Like Android Safe, a new password that already opens a wallet on this
 * Mac is refused, so one password never opens two wallets.
 */
@Composable
fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    minLength: Int = 6,
) {
    var old by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val legacy = WalletSession.isLegacy

    fun attempt() {
        when {
            busy -> return
            pwd.length < minLength -> error = "Password must be at least $minLength characters"
            pwd != confirm -> error = "Passwords don't match"
            else -> {
                val oldChars = old.toCharArray()
                val newChars = pwd.toCharArray()
                busy = true
                error = null
                scope.launch {
                    val result = withContext(Dispatchers.Default) {
                        try {
                            runCatching { VaultUnlocker.changePassword(oldChars, newChars) }
                        } finally {
                            oldChars.fill(Char.MIN_VALUE)
                            newChars.fill(Char.MIN_VALUE)
                        }
                    }
                    busy = false
                    result.onSuccess {
                        when (it) {
                            VaultUnlocker.ChangeResult.Changed -> {
                                old = ""; pwd = ""; confirm = ""
                                onChanged()
                            }
                            VaultUnlocker.ChangeResult.WrongOldPassword -> error = "The current password is wrong"
                            VaultUnlocker.ChangeResult.NewPasswordInUse ->
                                error = "That password already opens a wallet on this Mac. Choose another."
                            VaultUnlocker.ChangeResult.LegacyWallet ->
                                error = "This wallet has not moved to its new key yet; its password can't be changed."
                        }
                    }.onFailure { error = "Change failed: ${it.message}" }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Change password") },
        text = {
            Column(modifier = Modifier.widthIn(min = 320.dp)) {
                Text(
                    if (legacy) "This wallet still uses its old key layout (its last move to the new key stopped), " +
                        "so its password can't be changed until the move succeeds."
                    else "Your keys and secrets stay as they are; only the password that opens them changes.",
                    style = MaterialTheme.typography.body2,
                )
                Spacer(Modifier.height(16.dp))
                PasswordField(
                    value = old,
                    onValueChange = { old = it; error = null },
                    label = "Current password",
                    enabled = !busy && !legacy,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = pwd,
                    onValueChange = { pwd = it; error = null },
                    label = "New password",
                    enabled = !busy && !legacy,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = "Confirm new password",
                    enabled = !busy && !legacy,
                    imeAction = ImeAction.Done,
                    onSubmit = ::attempt,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    Text("Checking the passwords…", style = MaterialTheme.typography.caption)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption)
                }
            }
        },
        confirmButton = {
            SafeButton(onClick = ::attempt, enabled = !busy && !legacy) { Text("Change") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}
