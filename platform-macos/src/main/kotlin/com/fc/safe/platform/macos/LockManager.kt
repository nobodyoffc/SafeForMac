package com.fc.safe.platform.macos

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Auto-lock policy for the unlocked [WalletSession]. Watches two
 * signals and calls [WalletSession.lock] when either trips:
 *
 * 1. **Idle timer** — wall-clock time since the last user input.
 *    `markActive()` is called by the root composable on every
 *    pointer/key event. After [idleTimeoutMs] of silence, lock.
 * 2. **Window focus loss** — `onFocusChanged(false)` notes when the
 *    Safe window stops being the focused one. After [focusLossGraceMs]
 *    of unfocus, lock. The grace lets users alt-tab briefly to copy
 *    something without re-entering the password.
 *
 * Per the migration plan §3.5, this is the v1-acceptable minimum:
 * two independent signals, with the idle timer always on as a
 * backstop. Native screen-lock / sleep observers (NSDistributedNotificationCenter,
 * NSWorkspace) will be a JNA-based follow-up — wallet doesn't ship
 * with idle-only as the sole signal.
 */
object LockManager {
    private val log = LoggerFactory.getLogger(LockManager::class.java)

    /** Reasons we record for the lock event stream — surfaced in logs and UX hints. */
    enum class Reason { IDLE, FOCUS_LOSS, MANUAL }

    @Volatile
    var idleTimeoutMs: Long = TimeUnit.MINUTES.toMillis(5)

    @Volatile
    var focusLossGraceMs: Long = TimeUnit.SECONDS.toMillis(30)

    @Volatile
    private var lastActivityMs: Long = System.currentTimeMillis()

    @Volatile
    private var unfocusedSinceMs: Long? = null

    private val _events = MutableSharedFlow<Reason>(extraBufferCapacity = 8)
    /** Lock-event stream. Emits the [Reason] when [WalletSession] is locked by this manager. */
    val events: SharedFlow<Reason> = _events.asSharedFlow()

    /** Reset the idle timer. Hook this to root pointer + key events. */
    fun markActive() {
        lastActivityMs = System.currentTimeMillis()
    }

    /** Notify the manager that the window's focus state changed. */
    fun onFocusChanged(focused: Boolean) {
        if (focused) {
            unfocusedSinceMs = null
            markActive()
        } else if (unfocusedSinceMs == null) {
            unfocusedSinceMs = System.currentTimeMillis()
        }
    }

    /** Reset internal state on a fresh unlock. */
    fun resetForNewSession() {
        lastActivityMs = System.currentTimeMillis()
        unfocusedSinceMs = null
    }

    /**
     * Suspend loop — checks the timers once per second and triggers
     * [WalletSession.lock] when a threshold is crossed. Caller starts
     * this in a coroutine that lives for the application's lifetime.
     */
    suspend fun runLoop() {
        log.info(
            "LockManager started (idle={}ms, focus-grace={}ms)",
            idleTimeoutMs, focusLossGraceMs,
        )
        while (true) {
            delay(1000)
            if (WalletSession.isLocked) continue

            val now = System.currentTimeMillis()
            val idle = now - lastActivityMs
            if (idle >= idleTimeoutMs) {
                fire(Reason.IDLE)
                continue
            }

            val unfocusedAt = unfocusedSinceMs
            if (unfocusedAt != null && (now - unfocusedAt) >= focusLossGraceMs) {
                fire(Reason.FOCUS_LOSS)
                continue
            }
        }
    }

    /** Read-only snapshot used by status banners. Negative when overdue. */
    fun secondsUntilIdleLock(): Long {
        if (WalletSession.isLocked) return -1
        val left = idleTimeoutMs - (System.currentTimeMillis() - lastActivityMs)
        return left / 1000
    }

    /** Read-only snapshot used by status banners. Negative when overdue. -1 when focused. */
    fun secondsUntilFocusLossLock(): Long {
        if (WalletSession.isLocked) return -1
        val unfocusedAt = unfocusedSinceMs ?: return -1
        val left = focusLossGraceMs - (System.currentTimeMillis() - unfocusedAt)
        return left / 1000
    }

    private fun fire(reason: Reason) {
        log.info("Auto-lock fired: {}", reason)
        WalletSession.lock()
        _events.tryEmit(reason)
    }
}
