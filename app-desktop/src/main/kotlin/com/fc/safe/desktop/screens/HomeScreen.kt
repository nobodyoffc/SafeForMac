package com.fc.safe.desktop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.rememberScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.fc.safe.desktop.ui.PasswordField
import com.fc.safe.desktop.ui.SafeButton
import com.fc.safe.platform.macos.DesktopConfigureManager
import com.fc.safe.platform.macos.DesktopDatabaseManager
import com.fc.safe.platform.macos.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("HomeScreen")

/**
 * First screen of the port.
 *
 * Two flows, chosen by whether any wallets are registered:
 * - First launch (no configurations): "create password" with confirm field.
 *   On submit: register the password in [DesktopConfigureManager] and open
 *   the wallet's `vault` DB for the first time.
 * - Subsequent launches: "enter password" with single field. On submit:
 *   attempt to open the `vault` DB; `WrongPasswordException` → error shown,
 *   UI stays locked.
 *
 * The actual password authentication happens via `DesktopDatabaseManager.open`
 * — it derives the Argon2 symkey and decrypts the stored validator row.
 * `DesktopConfigureManager` is the cache of "known passwordNames on this
 * machine" and does not itself authenticate.
 */
class HomeScreen : Screen {

    @Composable
    override fun Content() {
        val scaffoldState = rememberScaffoldState()
        val scope = rememberCoroutineScope()

        // Snapshot of known wallets taken once when the screen enters composition.
        val configSnapshot = remember { mutableStateOf(DesktopConfigureManager.all()) }
        val isFirstLaunch = configSnapshot.value.isEmpty()

        var password by remember { mutableStateOf("") }
        var confirmPassword by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var unlockedAs by remember { mutableStateOf<String?>(null) }

        // Unlocked placeholder view — real home content comes in later Phase 2 steps.
        if (unlockedAs != null) {
            AppShell(title = "Safe — Unlocked ($unlockedAs)", scaffoldState = scaffoldState) { padding ->
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
                    SafeButton(onClick = {
                        DesktopDatabaseManager.closeAll()
                        unlockedAs = null
                        password = ""
                        confirmPassword = ""
                    }) { Text("Lock") }
                }
            }
            return
        }

        val title = if (isFirstLaunch) "Safe — Create wallet" else "Safe — Unlock"

        AppShell(title = title, scaffoldState = scaffoldState) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (isFirstLaunch) "Create your first wallet" else "Unlock wallet",
                    style = MaterialTheme.typography.h5,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (isFirstLaunch)
                        "Choose a strong password — it protects every key in the wallet."
                    else
                        "${configSnapshot.value.size} wallet(s) registered on this machine",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(24.dp))

                PasswordField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = "Password",
                    enabled = !busy,
                    modifier = Modifier.widthIn(min = 320.dp),
                )

                if (isFirstLaunch) {
                    Spacer(Modifier.height(12.dp))
                    PasswordField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; error = null },
                        label = "Confirm password",
                        enabled = !busy,
                        modifier = Modifier.widthIn(min = 320.dp),
                    )
                }

                error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = MaterialTheme.colors.error)
                }

                Spacer(Modifier.height(24.dp))

                SafeButton(
                    enabled = !busy,
                    onClick = {
                        error = null
                        when {
                            password.isEmpty() -> { error = "Password required"; return@SafeButton }
                            isFirstLaunch && password.length < 8 -> {
                                error = "Password must be at least 8 characters"
                                return@SafeButton
                            }
                            isFirstLaunch && password != confirmPassword -> {
                                error = "Passwords don't match"
                                return@SafeButton
                            }
                        }
                        busy = true
                        val pwdChars = password.toCharArray()
                        scope.launch(Dispatchers.Default) {
                            try {
                                attemptUnlock(pwdChars, isFirstLaunch) { name ->
                                    unlockedAs = name
                                    password = ""
                                    confirmPassword = ""
                                    configSnapshot.value = DesktopConfigureManager.all()
                                }
                            } catch (e: WrongPasswordException) {
                                withContext(Dispatchers.Main) { error = "Wrong password" }
                            } catch (e: Throwable) {
                                log.warn("Unlock failed: {}", e.message)
                                withContext(Dispatchers.Main) { error = "Unlock error: ${e.message}" }
                            } finally {
                                pwdChars.fill(Char.MIN_VALUE)
                                withContext(Dispatchers.Main) { busy = false }
                            }
                        }
                    },
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colors.onPrimary,
                        )
                    } else {
                        Text(if (isFirstLaunch) "Create" else "Unlock")
                    }
                }
            }
        }
    }

    private suspend fun attemptUnlock(
        pwdChars: CharArray,
        isFirstLaunch: Boolean,
        onUnlocked: suspend (name: String) -> Unit,
    ) {
        val name: String
        // Password hash-name is cheap (2× SHA256); safe to compute on default dispatcher.
        name = DesktopConfigureManager.passwordNameFor(pwdChars.copyOf().also { /* owned here */ })

        // Open the vault DB. This is the expensive step — Argon2 KDF ~100-500ms.
        // WrongPasswordException bubbles up to the catch block if decrypt fails.
        val db = DesktopDatabaseManager.open(
            password = pwdChars.copyOf(),
            dbName = "vault",
            entityClass = DesktopVault::class.java,
        )
        if (isFirstLaunch) {
            DesktopConfigureManager.createFor(pwdChars.copyOf())
            // Seed a vault-created record so the DB isn't entirely empty.
            db.putMeta("vault.created_at", System.currentTimeMillis())
        }

        withContext(Dispatchers.Main) { onUnlocked(name) }
    }
}
