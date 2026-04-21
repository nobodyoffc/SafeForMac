package com.fc.safe.platform.macos

import java.io.File

/**
 * Called BEFORE any logger is acquired. Resolves the log directory and exposes
 * it via system property so logback.xml can substitute `${safe.log.dir}` into
 * the file appender path. Must be invoked as the first line of `main()`.
 */
object BootstrapLogging {
    const val LOG_DIR_PROPERTY: String = "safe.log.dir"

    fun preInit() {
        val logDir = DesktopAppPaths.logsDir.toString()
        File(logDir).mkdirs()
        System.setProperty(LOG_DIR_PROPERTY, logDir)
    }
}
