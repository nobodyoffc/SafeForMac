package com.fc.safe.platform.macos

import org.slf4j.LoggerFactory

/**
 * Process-level lifecycle holder. Replaces Android's SafeApplication.
 *
 * Contract: BootstrapLogging.preInit() must have been called before any
 * reference to DesktopApp, otherwise the logback file appender will write to
 * ./logs/ instead of ~/Library/Logs/com.fc.safe/.
 */
object DesktopApp {
    private val log = LoggerFactory.getLogger(DesktopApp::class.java)

    @Volatile private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            DesktopAppPaths.ensureCreated()
            log.info("Safe desktop initialized — supportDir={}", DesktopAppPaths.supportDir)
            initialized = true
        }
    }

    fun shutdown() {
        if (!initialized) return
        synchronized(this) {
            if (!initialized) return
            try {
                DesktopDatabaseManager.closeAll()
                log.info("Safe desktop shut down cleanly")
            } finally {
                initialized = false
            }
        }
    }
}
