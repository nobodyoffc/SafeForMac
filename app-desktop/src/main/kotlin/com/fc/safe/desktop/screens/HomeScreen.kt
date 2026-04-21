package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import com.fc.safe.desktop.DesktopVault
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CreatePasswordDialog
import com.fc.safe.desktop.ui.PasswordField
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.DesktopAppPaths
import com.fc.safe.platform.macos.DesktopConfigureManager
import com.fc.safe.platform.macos.DesktopDatabaseManager
import com.fc.safe.platform.macos.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files

private val log = LoggerFactory.getLogger("HomeScreen")

/**
 * First screen of the port.
 *
 * **UX invariant**: the default surface never reveals whether a wallet
 * exists on this machine. Always shows a single "Enter password" field
 * plus an "Unlock" primary button. Wallet creation is an explicit user
 * action via a secondary "Create password" button that opens
 * [CreatePasswordDialog].
 *
 * **Auth flow**:
 * - Unlock: derive passwordName, look up in [DesktopConfigureManager].
 *   If not registered → "Wrong password" (same message as bad decrypt).
 *   If registered → open the wallet's vault DB via
 *   [DesktopDatabaseManager]; [WrongPasswordException] → "Wrong password".
 * - Create: [DesktopDatabaseManager.open] writes a new encrypted DB;
 *   [DesktopConfigureManager.createFor] registers the passwordName.
 */
class HomeScreen : Screen {

    @Composable
    override fun Content() {
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var unlockedAs by remember { mutableStateOf<String?>(null) }
        var showCreateDialog by remember { mutableStateOf(false) }

        if (unlockedAs != null) {
            UnlockedContent(
                passwordName = unlockedAs!!,
                scaffoldState = scaffoldState,
                onLock = {
                    DesktopDatabaseManager.closeAll()
                    unlockedAs = null
                    password = ""
                    error = null
                },
            )
            return
        }

        AppShell(title = "Safe", scaffoldState = scaffoldState) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Enter password", style = MaterialTheme.typography.h5)
                Spacer(Modifier.height(24.dp))

                PasswordField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = "Password",
                    enabled = !busy,
                    onSubmit = {
                        if (password.isNotEmpty() && !busy) {
                            tryUnlock(scope, password, setError = { error = it }, setBusy = { busy = it }, onUnlocked = { unlockedAs = it; password = "" })
                        }
                    },
                    modifier = Modifier.widthIn(min = 320.dp),
                )

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SafeButton(
                        enabled = !busy && password.isNotEmpty(),
                        onClick = {
                            tryUnlock(
                                scope = scope,
                                password = password,
                                setError = { error = it },
                                setBusy = { busy = it },
                                onUnlocked = { unlockedAs = it; password = "" },
                            )
                        },
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colors.onPrimary,
                            )
                        } else {
                            Text("Unlock")
                        }
                    }

                    TextButton(
                        enabled = !busy,
                        onClick = { showCreateDialog = true },
                    ) {
                        Text("Create password")
                    }
                }
            }
        }

        if (showCreateDialog) {
            CreatePasswordDialog(
                busy = busy,
                onDismiss = { showCreateDialog = false },
                onCreate = { newPwdChars ->
                    showCreateDialog = false
                    tryCreate(
                        scope = scope,
                        pwdChars = newPwdChars,
                        setError = { error = it },
                        setBusy = { busy = it },
                        onUnlocked = { unlockedAs = it; password = "" },
                    )
                },
            )
        }
    }

    private fun tryUnlock(
        scope: kotlinx.coroutines.CoroutineScope,
        password: String,
        setError: (String?) -> Unit,
        setBusy: (Boolean) -> Unit,
        onUnlocked: (passwordName: String) -> Unit,
    ) {
        setError(null)
        setBusy(true)
        val pwdChars = password.toCharArray()
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                try {
                    val name = DesktopConfigureManager.passwordNameFor(pwdChars.copyOf())
                    // "Known wallet?" check happens BEFORE opening so an
                    // unknown-password attempt can't silently create a
                    // new wallet. Same error copy as decrypt failure —
                    // don't leak whether the password is unknown vs wrong.
                    val known = DesktopConfigureManager.all().containsKey(name)
                    val vaultFile = DesktopAppPaths.dbDir.resolve(name).resolve("vault.sqlite")
                    if (!known || !Files.exists(vaultFile)) {
                        return@withContext Outcome.Wrong
                    }
                    DesktopDatabaseManager.open(
                        password = pwdChars.copyOf(),
                        dbName = "vault",
                        entityClass = DesktopVault::class.java,
                    )
                    Outcome.Ok(name)
                } catch (e: WrongPasswordException) {
                    Outcome.Wrong
                } catch (e: Throwable) {
                    log.warn("Unlock error", e)
                    Outcome.Error(e.message ?: "unknown error")
                } finally {
                    pwdChars.fill(Char.MIN_VALUE)
                }
            }
            when (outcome) {
                is Outcome.Ok -> onUnlocked(outcome.name)
                is Outcome.Wrong -> setError("Wrong password")
                is Outcome.Error -> setError("Error: ${outcome.msg}")
            }
            setBusy(false)
        }
    }

    private fun tryCreate(
        scope: kotlinx.coroutines.CoroutineScope,
        pwdChars: CharArray,
        setError: (String?) -> Unit,
        setBusy: (Boolean) -> Unit,
        onUnlocked: (passwordName: String) -> Unit,
    ) {
        setError(null)
        setBusy(true)
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                try {
                    val name = DesktopConfigureManager.passwordNameFor(pwdChars.copyOf())
                    val db = DesktopDatabaseManager.open(
                        password = pwdChars.copyOf(),
                        dbName = "vault",
                        entityClass = DesktopVault::class.java,
                    )
                    db.putMeta("vault.created_at", System.currentTimeMillis())
                    DesktopConfigureManager.createFor(pwdChars.copyOf())
                    Outcome.Ok(name)
                } catch (e: Throwable) {
                    log.warn("Create error", e)
                    Outcome.Error(e.message ?: "unknown error")
                } finally {
                    pwdChars.fill(Char.MIN_VALUE)
                }
            }
            when (outcome) {
                is Outcome.Ok -> onUnlocked(outcome.name)
                is Outcome.Error -> setError("Create failed: ${outcome.msg}")
                is Outcome.Wrong -> { /* unreachable in create flow */ }
            }
            setBusy(false)
        }
    }

    private sealed class Outcome {
        data class Ok(val name: String) : Outcome()
        data object Wrong : Outcome()
        data class Error(val msg: String) : Outcome()
    }
}

@Composable
private fun UnlockedContent(
    passwordName: String,
    scaffoldState: androidx.compose.material.ScaffoldState,
    onLock: () -> Unit,
) {
    AppShell(title = "Safe — Unlocked ($passwordName)", scaffoldState = scaffoldState) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Unlocked.", style = MaterialTheme.typography.h5)
            Spacer(Modifier.height(8.dp))
            Text(
                "Phase 2 placeholder — key management, transactions, and multisig screens will land here.",
                style = MaterialTheme.typography.body2,
            )
            Spacer(Modifier.height(24.dp))
            SafeButton(onClick = onLock) { Text("Lock") }
        }
    }
}
