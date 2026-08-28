package com.fc.safe.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cafe.adriel.voyager.navigator.Navigator
import com.fc.safe.desktop.screens.HomeScreen
import com.fc.safe.platform.macos.BootstrapLogging
import com.fc.safe.platform.macos.DesktopApp
import com.fc.safe.platform.macos.LockManager
import com.fc.safe.platform.macos.WalletSession
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.event.WindowFocusListener
import java.awt.event.WindowEvent

@OptIn(ExperimentalComposeUiApi::class, kotlinx.coroutines.DelicateCoroutinesApi::class)
fun main() {
    // Must run before any logger is acquired: sets safe.log.dir for logback.xml.
    BootstrapLogging.preInit()
    val log = LoggerFactory.getLogger("Main")
    DesktopApp.initialize()
    log.info("Safe desktop starting")

    // Auto-lock loop runs for the lifetime of the JVM. GlobalScope is
    // appropriate here — we want a process-wide background timer that
    // outlives any individual screen composition.
    GlobalScope.launch { LockManager.runLoop() }

    application {
        Window(
            onCloseRequest = {
                DesktopApp.shutdown()
                exitApplication()
            },
            title = "Safe",
            state = rememberWindowState(width = 720.dp, height = 840.dp),
        ) {
            // macOS menu bar. Standard Edit menu (Cut/Copy/Paste/
            // Select All) isn't here because Compose Desktop's
            // text fields handle Cmd-X/C/V/A natively without us
            // having to forward menu events to the focused widget.
            // Lock-now is the most useful Cmd shortcut for a
            // wallet — Cmd-L is conventional (browsers use it for
            // address-bar focus, but no other app on a wallet
            // workflow contests it).
            MenuBar {
                Menu("File") {
                    Item(
                        text = "Quit Safe",
                        shortcut = KeyShortcut(Key.Q, meta = true),
                        onClick = {
                            DesktopApp.shutdown()
                            exitApplication()
                        },
                    )
                }
                Menu("Wallet") {
                    Item(
                        text = "Lock now",
                        shortcut = KeyShortcut(Key.L, meta = true),
                        // Disabled when already locked so the
                        // shortcut doesn't no-op silently.
                        enabled = !WalletSession.isLockedFlow.value,
                        onClick = { WalletSession.lock() },
                    )
                }
            }
            // Bridge AWT focus events into LockManager. ComposeWindow
            // is the underlying Swing JFrame; LaunchedEffect installs
            // the listener once per window.
            val composeWindow: ComposeWindow = window
            LaunchedEffect(composeWindow) {
                val listener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) {
                        LockManager.onFocusChanged(true)
                    }
                    override fun windowLostFocus(e: WindowEvent?) {
                        LockManager.onFocusChanged(false)
                    }
                }
                composeWindow.addWindowFocusListener(listener)
                // Seed the manager with the current state so the
                // window's initial "I am focused" doesn't get missed.
                LockManager.onFocusChanged(composeWindow.isFocused)
            }

            // Root pointer/key listener at the highest level so every
            // child screen contributes to the activity timer. We don't
            // consume events; onPointerEvent observes them. Compose
            // doesn't expose a global key-event hook the same way, but
            // text-field interaction generates pointer events too, so
            // this is sufficient for v1.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onPointerEvent(PointerEventType.Move) { LockManager.markActive() }
                    .onPointerEvent(PointerEventType.Press) { LockManager.markActive() }
                    .onPointerEvent(PointerEventType.Scroll) { LockManager.markActive() },
            ) {
                Navigator(HomeScreen()) { navigator ->
                    // Drop back to HomeScreen whenever the session
                    // transitions to locked from anywhere — manual
                    // Lock button, idle timer, focus loss, etc.
                    val isLocked by WalletSession.isLockedFlow.collectAsState()
                    val priorScreen = remember { navigator.lastItem }
                    LaunchedEffect(isLocked) {
                        if (isLocked && navigator.lastItem !is HomeScreen) {
                            log.info("Locked — resetting navigator to HomeScreen")
                            navigator.replaceAll(HomeScreen())
                        }
                    }
                    @Suppress("UNUSED_EXPRESSION") priorScreen
                    cafe.adriel.voyager.navigator.CurrentScreen()
                }
            }
        }
    }
}
