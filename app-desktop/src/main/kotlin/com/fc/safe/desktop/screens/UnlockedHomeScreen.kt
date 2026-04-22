package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.WalletSession

/**
 * Landing menu shown after a successful unlock. Each entry eventually
 * navigates to a dedicated feature screen; today only My Keys is wired.
 *
 * The back stack is replaced (not pushed) when transitioning from
 * HomeScreen → here, and again here → HomeScreen on lock, so the password
 * entry view is never reachable by hitting the back button after unlock.
 */
class UnlockedHomeScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val name = WalletSession.currentPasswordName() ?: "?"

        AppShell(title = "Safe — $name", scaffoldState = scaffoldState) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Wallet unlocked", style = MaterialTheme.typography.h5)
                Spacer(Modifier.height(24.dp))

                SafeButton(
                    onClick = { navigator.push(MyKeysScreen()) },
                    modifier = Modifier.widthIn(min = 200.dp),
                ) { Text("My Keys") }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Secrets, transactions, multisig — next screens land here as they're ported.",
                    style = MaterialTheme.typography.caption,
                )

                Spacer(Modifier.height(32.dp))

                SafeButton(
                    onClick = {
                        WalletSession.lock()
                        navigator.replaceAll(HomeScreen())
                    },
                    modifier = Modifier.widthIn(min = 200.dp),
                ) { Text("Lock") }
            }
        }
    }
}
