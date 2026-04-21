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

/**
 * Dialog for creating a new wallet password. Offered via an explicit
 * "Create password" button on [com.fc.safe.desktop.screens.HomeScreen] so
 * the default unlock surface never leaks whether a wallet already exists.
 *
 * [onCreate] is called only once the two fields agree and meet the length
 * floor. The caller is responsible for wiping the [CharArray] after use.
 */
@Composable
fun CreatePasswordDialog(
    onDismiss: () -> Unit,
    onCreate: (password: CharArray) -> Unit,
    busy: Boolean = false,
    minLength: Int = 6,
) {
    var pwd by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun attempt() {
        when {
            pwd.length < minLength -> error = "Password must be at least $minLength characters"
            pwd != confirm -> error = "Passwords don't match"
            else -> {
                val chars = pwd.toCharArray()
                pwd = ""; confirm = ""
                onCreate(chars)
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Create new wallet") },
        text = {
            Column(modifier = Modifier.widthIn(min = 320.dp)) {
                Text(
                    "Choose a strong password. You'll use it every time you unlock this wallet.",
                    style = MaterialTheme.typography.body2,
                )
                Spacer(Modifier.height(16.dp))
                PasswordField(
                    value = pwd,
                    onValueChange = { pwd = it; error = null },
                    label = "New password",
                    enabled = !busy,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it; error = null },
                    label = "Confirm password",
                    enabled = !busy,
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
            SafeButton(onClick = ::attempt, enabled = !busy) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}
