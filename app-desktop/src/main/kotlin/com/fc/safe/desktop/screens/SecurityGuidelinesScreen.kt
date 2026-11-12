package com.fc.safe.desktop.screens

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.i18n.tr
import com.fc.safe.desktop.ui.AppShell

/** Android Safe's Security Guidelines, word for word (`security_guidelines_text`, 2.5). */
internal val SECURITY_GUIDELINES = listOf(
    tr("Keep the device offline at all times, meaning it should not be connected to any other devices via cables, mobile networks, Wi-Fi, Bluetooth, NFC, or any other means."),
    tr("Ensure that private keys and passphrases are never leaked or lost. A passphrase can always generate a private key, but a private key cannot be used to derive a passphrase."),
    tr("When displaying private keys or passphrases on the screen, make sure the surroundings are secure, with no one watching or any cameras recording."),
    tr("It is recommended to use an offline QR code scanning device for physical backup of private keys and passphrases, with at least two separate backups stored in different locations and regularly checked."),
    tr("If this device is lost or suspected to be compromised, immediately transfer assets to a secure address using a backup private key."),
    tr("When generating a private key from a passphrase, the passphrase should be as long and complex as possible, containing multiple types of characters. Do not use famous quotes or poems. Remember, you are up against hackers worldwide with immense computing power and advanced skills."),
    tr("When generating a private key from a passphrase, choose Argon2id: it is slow and memory-hard, so every guess costs an attacker time and memory. SHA-256 is fast and offers no protection against brute-force attacks; use it only for compatibility with existing SHA-256-derived keys. Never test an asset-storing passphrase online."),
    tr("You can generate a private key on a secure and trusted external device and import it into the system via scanning or manual entry. The security of this step depends on the source of the private key and your operational precautions."),
    tr("Mnemonics add complexity to private key security strategies; therefore, this system does not support mnemonic phrases. You can import a private key exported from a third-party device instead."),
    tr("Once again: Private keys and passphrases must never be leaked or lost, and the device must remain offline. This is the core purpose of this system."),
    tr("This application does not connect to the internet or collect any user or application data; therefore, it is not responsible for any data leaks. Users must ensure the security of their devices."),
)

class SecurityGuidelinesScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        AppShell(
            title = tr("Security guidelines"),
            navigationIcon = {
                IconButton(onClick = { navigator.pop() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Back"))
                }
            },
        ) { padding ->
            val scroll = rememberScrollState()
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                SelectionContainer(modifier = Modifier.align(Alignment.TopCenter)) {
                    Column(
                        modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()
                            .verticalScroll(scroll).padding(24.dp),
                    ) {
                        SECURITY_GUIDELINES.forEachIndexed { i, line ->
                            Text(
                                "${i + 1}. $line",
                                style = MaterialTheme.typography.body1,
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(scroll),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp),
                )
            }
        }
    }
}
