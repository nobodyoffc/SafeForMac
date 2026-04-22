package com.fc.safe.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Modal showing a just-decrypted private key.
 *
 * The caller decrypts the prikey (via [com.fc.safe.platform.macos.WalletSession.decryptFromJson])
 * and passes the hex string in. This dialog is a display-only surface —
 * it does no crypto itself, and the caller is responsible for wiping
 * any intermediate byte arrays before disposing of the hex string.
 */
@Composable
fun PrikeyRevealDialog(
    prikeyHex: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Private key") },
        text = {
            Column(
                modifier = Modifier.widthIn(min = 360.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Anyone who sees this controls the wallet. Don't screenshot. Don't share.",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.error,
                )
                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .size(256.dp)
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCode(text = prikeyHex, modifier = Modifier.size(240.dp))
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = prikeyHex,
                    style = MaterialTheme.typography.body2.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
