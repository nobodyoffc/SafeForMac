package com.fc.safe.platform.macos

import java.io.File

/**
 * Called BEFORE any logger is acquired. Resolves the log directory and exposes
 * it via system property so safe-logback.xml can substitute `${safe.log.dir}`
 * into the file appender path. Must be invoked as the first line of `main()`.
 *
 * Also names our config explicitly. FC-JDK's jar carries its own logback.xml
 * (writing to a relative logs/fapiServer.log), and which `logback.xml` logback
 * finds first depends on jar order — in the packaged app that was FC-JDK's, so
 * safe.log was never written and its secret redaction never ran.
 */
object BootstrapLogging {
    const val LOG_DIR_PROPERTY: String = "safe.log.dir"
    private const val CONFIG_PROPERTY: String = "logback.configurationFile"

    fun preInit() {
        val logDir = DesktopAppPaths.logsDir.toString()
        File(logDir).mkdirs()
        System.setProperty(LOG_DIR_PROPERTY, logDir)
        if (System.getProperty(CONFIG_PROPERTY) == null) System.setProperty(CONFIG_PROPERTY, "safe-logback.xml")
    }
}
