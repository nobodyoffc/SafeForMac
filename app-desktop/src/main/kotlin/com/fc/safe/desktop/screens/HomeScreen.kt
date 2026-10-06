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
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.fc.safe.desktop.BuildInfo
import com.fc.safe.desktop.ui.AppShell
import com.fc.safe.desktop.ui.CreatePasswordDialog
import com.fc.safe.desktop.ui.PasswordField
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.DesktopConfigureManager
import com.fc.safe.platform.macos.VaultUnlocker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

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
 * **Auth flow** ([VaultUnlocker], off the UI thread — Argon2id runs on every path):
 * - Unlock: the vault whose data key the password unwraps opens. A legacy
 *   (pre-1.1) wallet is confirmed by its password and moved to a data key on
 *   the way in. No match → "Wrong password", whatever the reason.
 * - Create: refused if the password already opens a wallet; otherwise a new
 *   vault with a random data key and vault id.
 */
class HomeScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var showCreateDialog by remember { mutableStateOf(false) }
        // Read once per visit; it only decides the wording of the busy hint.
        val hasLegacyWallet = remember { DesktopConfigureManager.all().values.any { it.isLegacy } }

        // VaultUnlocker has already unlocked WalletSession.
        fun goToUnlockedHome(notice: String?) {
            password = ""
            navigator.replaceAll(UnlockedHomeScreen(notice))
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
                            tryUnlock(
                                scope = scope,
                                password = password,
                                setError = { error = it },
                                setBusy = { busy = it },
                                onUnlocked = ::goToUnlockedHome,
                            )
                        }
                    },
                    modifier = Modifier.widthIn(min = 320.dp),
                )

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (hasLegacyWallet) "Checking the password… The first unlock after updating also moves " +
                            "the wallet to its new key and can take a minute."
                        else "Checking the password…",
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        enabled = !busy,
                        onClick = { showCreateDialog = true },
                    ) {
                        Text("Create password")
                    }

                    SafeButton(
                        enabled = !busy && password.isNotEmpty(),
                        onClick = {
                            tryUnlock(
                                scope = scope,
                                password = password,
                                setError = { error = it },
                                setBusy = { busy = it },
                                onUnlocked = ::goToUnlockedHome,
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
                }

                // The lock screen is the one surface every user sees on
                // every launch, and the first thing worth knowing when
                // someone reports a problem is which build they're on.
                Spacer(Modifier.height(32.dp))
                Text(
                    "v${BuildInfo.VERSION}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                )
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
                        onUnlocked = ::goToUnlockedHome,
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
        onUnlocked: (notice: String?) -> Unit,
    ) {
        setError(null)
        setBusy(true)
        val pwdChars = password.toCharArray()
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                try {
                    // Same error copy for "no such wallet" and "wrong password":
                    // don't leak which one it was.
                    when (val r = VaultUnlocker.unlock(pwdChars)) {
                        is VaultUnlocker.UnlockResult.WrongPassword -> Outcome.Wrong
                        is VaultUnlocker.UnlockResult.Opened -> Outcome.Ok(
                            r.unreadableRecordId?.let {
                                "This wallet could not move to its new key: record $it does not decrypt. " +
                                    "It still works as before, and the move is retried on the next unlock."
                            }
                        )
                    }
                } catch (e: Throwable) {
                    log.warn("Unlock error", e)
                    Outcome.Error(e.message ?: "unknown error")
                } finally {
                    pwdChars.fill(Char.MIN_VALUE)
                }
            }
            when (outcome) {
                is Outcome.Ok -> onUnlocked(outcome.notice)
                is Outcome.Wrong -> setError("Wrong password")
                is Outcome.Error -> setError("Error: ${outcome.msg}")
                is Outcome.Exists -> Unit
            }
            setBusy(false)
        }
    }

    private fun tryCreate(
        scope: kotlinx.coroutines.CoroutineScope,
        pwdChars: CharArray,
        setError: (String?) -> Unit,
        setBusy: (Boolean) -> Unit,
        onUnlocked: (notice: String?) -> Unit,
    ) {
        setError(null)
        setBusy(true)
        scope.launch {
            val outcome = withContext(Dispatchers.Default) {
                try {
                    when (VaultUnlocker.create(pwdChars)) {
                        is VaultUnlocker.CreateResult.Created -> Outcome.Ok(null)
                        is VaultUnlocker.CreateResult.AlreadyExists -> Outcome.Exists
                    }
                } catch (e: Throwable) {
                    log.warn("Create error", e)
                    Outcome.Error(e.message ?: "unknown error")
                } finally {
                    pwdChars.fill(Char.MIN_VALUE)
                }
            }
            when (outcome) {
                is Outcome.Ok -> onUnlocked(null)
                is Outcome.Exists -> setError("This password already opens a wallet. Unlock it, or choose another password.")
                is Outcome.Error -> setError("Create failed: ${outcome.msg}")
                is Outcome.Wrong -> Unit
            }
            setBusy(false)
        }
    }

    private sealed class Outcome {
        data class Ok(val notice: String?) : Outcome()
        data object Wrong : Outcome()
        data object Exists : Outcome()
        data class Error(val msg: String) : Outcome()
    }
}

