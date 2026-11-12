package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fc.safe.desktop.i18n.tr

/**
 * Hands over the random password of an export (FTSP31). The export never
 * carries it and it is not shown again, so the dialog holding this block
 * must stay open until [saved] is checked.
 */
@Composable
fun RandomPasswordBlock(
    password: String,
    saved: Boolean,
    onSavedChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            tr("Random password — keep this password apart from the exported data. Anyone who has both can read it. The export does not contain this password, and it will not be shown again."),
            style = MaterialTheme.typography.body2,
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = MaterialTheme.typography.body1.copy(
                fontFamily = FontFamily.Monospace,
            ),
            // The password travels apart from the JSON — on an offline
            // machine that means a second QR, not a second clipboard hop.
            trailingIcon = {
                MakeQrIconButton(
                    text = password,
                    title = tr("Export password"),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = saved, onCheckedChange = onSavedChange)
            Spacer(Modifier.width(4.dp))
            Text(tr("I've saved the password"), style = MaterialTheme.typography.body2)
        }
    }
}
