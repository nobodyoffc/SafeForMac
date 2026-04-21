package com.fc.safe.platform.macos

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Canonical macOS paths for the Safe app. All derived from the user's home
 * directory; no Android Context equivalent needed.
 */
object DesktopAppPaths {
    const val APP_ID: String = "com.fc.safe"

    val home: Path = Path(System.getProperty("user.home"))

    val supportDir: Path = home.resolve("Library/Application Support/$APP_ID")
    val dbDir: Path = supportDir.resolve("db")
    val configDir: Path = supportDir.resolve("config")
    val tmpDir: Path = supportDir.resolve("tmp")
    val logsDir: Path = home.resolve("Library/Logs/$APP_ID")

    fun ensureCreated() {
        listOf(supportDir, dbDir, configDir, tmpDir, logsDir).forEach {
            Files.createDirectories(it)
        }
    }
}
