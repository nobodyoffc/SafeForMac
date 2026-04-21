package com.fc.safe.desktop.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.ButtonElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Primary button for the Safe app. Replaces Safe Android's
 * `@style/ButtonStyle`. Keep the surface narrow — changing this one file
 * restyles every button in the app.
 */
@Composable
fun SafeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    elevation: ButtonElevation? = ButtonDefaults.elevation(defaultElevation = 1.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minWidth = 120.dp).height(40.dp),
        enabled = enabled,
        elevation = elevation,
        content = content,
    )
}
