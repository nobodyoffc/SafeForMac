package com.fc.safe.desktop.qr

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

/**
 * Bridges to the standalone macOS [ScanQR](file:///Applications/ScanQR.app)
 * companion app for live camera scanning. Avoids the AVFoundation+JNA
 * work the migration plan flags as the riskiest native integration —
 * a separate sandboxed app already has the camera entitlement and
 * the URL-scheme handshake to return a string.
 *
 * Protocol: launch via `open scanqr://return` — ScanQR pops up,
 * user scans / types, clicks Return, and the result lands in the
 * sandbox container at
 * `~/Library/Containers/com.scanqr.app/Data/Library/Caches/ScanQR/last_result.txt`.
 *
 * We poll that file from outside the sandbox (Safe is unsandboxed).
 * On success we read the text, delete the file (so the next call
 * starts from a clean slate), and return. On user cancel ScanQR
 * exits without writing — we time out and report cancel.
 */
object ScanQrLauncher {
    private val log = LoggerFactory.getLogger("ScanQrLauncher")

    private const val SCANQR_BUNDLE_ID = "com.scanqr.app"
    private const val SCANQR_APP_PATH = "/Applications/ScanQR.app"
    private val resultFile: File = File(
        System.getProperty("user.home"),
        "Library/Containers/$SCANQR_BUNDLE_ID/Data/Library/Caches/ScanQR/last_result.txt",
    )

    /**
     * `true` when `/Applications/ScanQR.app` is present. Cheap;
     * call from the UI thread to decide whether to render the
     * "From camera" menu entry.
     */
    fun isInstalled(): Boolean = File(SCANQR_APP_PATH).isDirectory

    /**
     * Outcome of [launchAndWait]. UX surfaces them differently —
     * `Cancelled` is silent (user backed out of ScanQR), `Failed`
     * gets an inline error message.
     */
    sealed class Result {
        data class Ok(val text: String) : Result()
        data object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * Launch ScanQR, poll for the result, return what was scanned.
     * Suspends — call from a coroutine on `Dispatchers.IO`.
     *
     * @param timeoutMs how long to wait for the user to scan + click
     *   Return. Default 2 minutes; covers fumbling with the QR
     *   without leaving a stuck poller running indefinitely.
     * @param pollIntervalMs how often to recheck the file. 250ms
     *   is responsive without burning CPU.
     */
    suspend fun launchAndWait(
        timeoutMs: Long = 120_000,
        pollIntervalMs: Long = 250,
    ): Result {
        if (!isInstalled()) {
            return Result.Failed("ScanQR not installed at $SCANQR_APP_PATH")
        }

        // Snapshot pre-launch state so we can tell "ScanQR wrote a
        // fresh result" from "an old result from a prior session
        // is still sitting on disk". `mtime + size` is a more
        // reliable signal than absence-vs-presence because the
        // user may have a leftover file.
        val priorMtime = if (resultFile.exists()) resultFile.lastModified() else -1L

        // `open scanqr://return` — handed to /usr/bin/open, the
        // standard URL-scheme dispatcher. Doesn't block on
        // ScanQR's lifetime; we read the result file instead.
        val launch = runCatching {
            ProcessBuilder("/usr/bin/open", "scanqr://return")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
        launch.onFailure { return Result.Failed("Failed to launch ScanQR: ${it.message}") }

        log.info("ScanQR launched, polling {}", resultFile)

        val outcome = withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(pollIntervalMs)
                if (resultFile.exists() && resultFile.lastModified() > priorMtime) {
                    return@withTimeoutOrNull readAndConsume()
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }

        return outcome ?: Result.Cancelled
    }

    /**
     * Read the result, then delete it so the next call doesn't
     * pick up the same string. Trims a single trailing newline
     * because the README's stdout output appends one.
     */
    private fun readAndConsume(): Result {
        return try {
            val raw = Files.readString(resultFile.toPath(), Charsets.UTF_8)
            val text = raw.removeSuffix("\n")
            // Ignore failure to delete — the next launch's mtime
            // check will skip stale content anyway.
            runCatching { Files.deleteIfExists(resultFile.toPath()) }
            if (text.isEmpty()) Result.Cancelled else Result.Ok(text)
        } catch (t: Throwable) {
            Result.Failed("Failed to read ScanQR result: ${t.message}")
        }
    }
}
