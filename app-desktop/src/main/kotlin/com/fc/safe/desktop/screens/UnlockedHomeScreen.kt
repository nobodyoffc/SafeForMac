package com.fc.safe.desktop.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.FloatingActionButton
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.platform.macos.DesktopApp
import com.fc.safe.platform.macos.LockManager
import com.fc.safe.platform.macos.WalletSession
import com.fc.safe.desktop.ui.ChangePasswordDialog
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

/**
 * Landing menu shown after a successful unlock. Mirrors Safe
 * Android's `activity_home.xml` 3-column icon grid: data-entity
 * tiles (Keys / Secrets / TOTP / Cash / Freers / Multisig) navigate
 * directly, while the three grouped toolkits (Transactions / Tools
 * / Convert) open a dropdown so the grid stays compact regardless
 * of how many actions each group hosts.
 *
 * The back stack is replaced (not pushed) when transitioning from
 * HomeScreen → here, and again here → HomeScreen on lock, so the
 * password entry view is never reachable by hitting the back button
 * after unlock.
 */
class UnlockedHomeScreen(
    /** Shown once at the top, e.g. why a legacy wallet could not move to its new key. */
    private val notice: String? = null,
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val name = WalletSession.currentVaultName() ?: "?"
        var showChangePassword by remember { mutableStateOf(false) }
        var changedMessage by remember { mutableStateOf<String?>(null) }

        // Tick once a second to refresh the auto-lock countdown.
        // markActive() is wired at the root in Main.kt, so this loop
        // never gets in the way of input handling.
        var secondsLeft by remember { mutableStateOf(LockManager.secondsUntilIdleLock()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                secondsLeft = LockManager.secondsUntilIdleLock()
            }
        }

        AppShell(title = "Safe — $name", scaffoldState = scaffoldState) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Status panel — mirrors Android's top info container.
                    Surface(
                        modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colors.surface,
                        elevation = 1.dp,
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Protect your passwords, keys, secrets and privacy with cryptography",
                                style = MaterialTheme.typography.h6,
                                textAlign = TextAlign.Center,
                            )
                            (changedMessage ?: notice)?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    it,
                                    style = MaterialTheme.typography.caption,
                                    color = if (changedMessage != null) MaterialTheme.colors.primary else MaterialTheme.colors.error,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (secondsLeft in 0..60) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Auto-lock in ${secondsLeft}s — move the mouse to extend.",
                                    style = MaterialTheme.typography.caption,
                                    color = MaterialTheme.colors.error,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Column(
                        modifier = Modifier.widthIn(max = 720.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        IconRow(
                            IconTile("My Keys", Icons.Filled.Key) { navigator.push(MyKeysScreen()) },
                            IconTile("Secrets", Icons.Filled.Lock) { navigator.push(MySecretsScreen()) },
                            IconTile("TOTP", Icons.Filled.Schedule) { navigator.push(TotpScreen()) },
                        )
                        IconRow(
                            IconTile("Cash", Icons.Filled.AccountBalance) { navigator.push(MyCashScreen()) },
                            IconTile("Freers", Icons.Filled.Contacts) { navigator.push(MyFidsScreen()) },
                            IconTile("Multisig", Icons.Filled.Group) { navigator.push(MyMultisigsScreen()) },
                        )
                        IconRow(
                            DropdownTile(
                                label = "TX",
                                icon = Icons.AutoMirrored.Filled.Send,
                                items = listOf(
                                    "Create TX" to { navigator.push(CreateTxScreen()) },
                                    "Import TX info" to { navigator.push(ImportTxInfoScreen()) },
                                    "Sign TX" to { navigator.push(SignTxScreen()) },
                                    "Create multisig TX" to { navigator.push(CreateMultisigTxScreen()) },
                                    "Sign multisig TX" to { navigator.push(SignMultisigTxScreen()) },
                                    "Build multisig TX" to { navigator.push(BuildMultisigTxScreen()) },
                                    "Pending TX" to { navigator.push(PendingTxsScreen()) },
                                ),
                            ),
                            DropdownTile(
                                label = "Tools",
                                icon = Icons.Filled.Build,
                                items = listOf(
                                    "Encrypt" to { navigator.push(EncryptScreen()) },
                                    "Decrypt" to { navigator.push(DecryptScreen()) },
                                    "Hash" to { navigator.push(HashScreen()) },
                                    "Sign message" to { navigator.push(SignMsgScreen()) },
                                    "Verify message" to { navigator.push(VerifyScreen()) },
                                    "Change password" to { showChangePassword = true },
                                ),
                            ),
                            DropdownTile(
                                label = "Convert",
                                icon = Icons.Filled.SwapHoriz,
                                items = listOf(
                                    "Private key" to { navigator.push(PrikeyConverterScreen()) },
                                    "Public key" to { navigator.push(PubkeyConverterScreen()) },
                                    "Address" to { navigator.push(AddressConverterScreen()) },
                                    "JSON" to { navigator.push(JsonConvertScreen()) },
                                    "Decode string" to { navigator.push(DecodeScreen()) },
                                ),
                            ),
                        )
                    }
                }

                // Bottom-right action row — Lock then Exit. Positioned
                // absolutely so they don't push the grid's vertical
                // rhythm around.
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FloatingActionButton(
                        onClick = {
                            WalletSession.lock()
                            navigator.replaceAll(HomeScreen())
                        },
                        backgroundColor = MaterialTheme.colors.primary,
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = "Lock wallet")
                    }
                    FloatingActionButton(
                        onClick = {
                            // Mirror Main.kt's Window.onCloseRequest:
                            // let DesktopApp flush state, then exit.
                            // exitProcess ensures the AWT event pump
                            // doesn't keep the JVM alive after Compose
                            // returns.
                            DesktopApp.shutdown()
                            exitProcess(0)
                        },
                        backgroundColor = MaterialTheme.colors.error,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Quit Safe",
                        )
                    }
                }
            }
        }

        if (showChangePassword) {
            ChangePasswordDialog(
                onDismiss = { showChangePassword = false },
                onChanged = {
                    showChangePassword = false
                    changedMessage = "Password changed."
                },
            )
        }
    }
}

private sealed interface HomeTile {
    val label: String
    val icon: ImageVector
}

private data class IconTile(
    override val label: String,
    override val icon: ImageVector,
    val onClick: () -> Unit,
) : HomeTile

private data class DropdownTile(
    override val label: String,
    override val icon: ImageVector,
    val items: List<Pair<String, () -> Unit>>,
) : HomeTile

@Composable
private fun IconRow(a: HomeTile?, b: HomeTile?, c: HomeTile?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Cell(a)
        Cell(b)
        Cell(c)
    }
}

@Composable
private fun RowScope.Cell(tile: HomeTile?) {
    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
        when (tile) {
            null -> Spacer(Modifier.size(1.dp))
            is IconTile -> IconButtonTile(tile.icon, tile.label, tile.onClick)
            is DropdownTile -> DropdownIconTile(tile)
        }
    }
}

@Composable
private fun IconButtonTile(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colors.primary.copy(alpha = 0.12f),
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = MaterialTheme.colors.primary,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.body2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun DropdownIconTile(tile: DropdownTile) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButtonTile(tile.icon, tile.label) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            tile.items.forEach { (itemLabel, action) ->
                DropdownMenuItem(onClick = {
                    open = false
                    action()
                }) { Text(itemLabel) }
            }
        }
    }
}
