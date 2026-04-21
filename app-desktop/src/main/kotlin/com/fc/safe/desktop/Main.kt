package com.fc.safe.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import cafe.adriel.voyager.navigator.Navigator
import com.fc.safe.desktop.screens.HomeScreen
import com.fc.safe.platform.macos.BootstrapLogging
import com.fc.safe.platform.macos.DesktopApp
import org.slf4j.LoggerFactory

fun main() {
    // Must run before any logger is acquired: sets safe.log.dir for logback.xml.
    BootstrapLogging.preInit()
    val log = LoggerFactory.getLogger("Main")
    DesktopApp.initialize()
    log.info("Safe desktop starting")

    application {
        Window(
            onCloseRequest = {
                DesktopApp.shutdown()
                exitApplication()
            },
            title = "Safe",
            state = rememberWindowState(width = 720.dp, height = 560.dp),
        ) {
            Navigator(HomeScreen())
        }
    }
}
