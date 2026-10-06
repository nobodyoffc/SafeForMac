package com.fc.safe.platform.macos

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.slf4j.LoggerFactory
import java.awt.AWTEvent
import java.awt.Toolkit
import java.awt.event.WindowEvent

/**
 * Keeps Safe's windows out of screenshots and screen recordings — the macOS
 * counterpart of the FLAG_SECURE Android Safe sets on every activity. Sets
 * `NSWindow.sharingType = NSWindowSharingNone` on each of the app's windows,
 * and again whenever AWT opens one (Compose dialogs are windows of their own).
 *
 * Best effort: if AppKit can't be reached, it logs and the app runs as before.
 */
object ScreenCaptureGuard {

    private val log = LoggerFactory.getLogger(ScreenCaptureGuard::class.java)

    /** NSWindowSharingNone; also what a nil object argument passes as. */
    private const val NS_WINDOW_SHARING_NONE = 0L

    /**
     * objc_msgSend with fixed arities. On arm64 it is not variadic, so a
     * JNA varargs binding would put the arguments in the wrong place.
     */
    @Suppress("FunctionName")
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer?, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer, arg: Long): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer, sel: Pointer, obj: Pointer?, wait: Boolean): Pointer?
    }

    private val objc: ObjC? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return@lazy null
        runCatching { Native.load("objc", ObjC::class.java) }
            .onFailure { log.warn("Screen-capture protection unavailable: {}", it.toString()) }
            .getOrNull()
    }

    @Volatile private var installed = false

    /** Protects every window now open, and every window opened later. Call once at startup. */
    fun install() {
        if (installed || objc == null) return
        installed = true
        var reported = false
        Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
            if (e.id == WindowEvent.WINDOW_OPENED || e.id == WindowEvent.WINDOW_ACTIVATED) {
                protectAllWindows()
                if (!reported) {
                    reported = true
                    // The setter runs asynchronously on the AppKit thread; read it back a moment later.
                    javax.swing.Timer(1000) { log.info("Screen-capture protection: sharingType per window = {}", sharingTypes()) }
                        .apply { isRepeats = false }.start()
                }
            }
        }, AWTEvent.WINDOW_EVENT_MASK)
        protectAllWindows()
    }

    /** Sets NSWindowSharingNone on every NSWindow of the app; the setter runs on the AppKit main thread. */
    fun protectAllWindows() {
        val o = objc ?: return
        runCatching {
            val windows = windows(o) ?: return
            val setSharing = o.sel_registerName("setSharingType:")
            val onMain = o.sel_registerName("performSelectorOnMainThread:withObject:waitUntilDone:")
            for (w in windows) {
                // A nil object argument arrives as 0, which is NSWindowSharingNone.
                o.objc_msgSend(w, onMain, setSharing, null, false)
            }
        }.onFailure { log.warn("Failed to protect windows from capture: {}", it.toString()) }
    }

    /** The current sharingType of every app window — for checking that [protectAllWindows] took effect. */
    fun sharingTypes(): List<Long> {
        val o = objc ?: return emptyList()
        val sel = o.sel_registerName("sharingType")
        return windows(o).orEmpty().map { Pointer.nativeValue(o.objc_msgSend(it, sel)) }
    }

    fun isProtected(): Boolean = sharingTypes().let { it.isNotEmpty() && it.all { t -> t == NS_WINDOW_SHARING_NONE } }

    private fun windows(o: ObjC): List<Pointer>? {
        val app = o.objc_msgSend(o.objc_getClass("NSApplication"), o.sel_registerName("sharedApplication")) ?: return null
        val array = o.objc_msgSend(app, o.sel_registerName("windows")) ?: return null
        val count = Pointer.nativeValue(o.objc_msgSend(array, o.sel_registerName("count")))
        val at = o.sel_registerName("objectAtIndex:")
        return (0 until count).mapNotNull { o.objc_msgSend(array, at, it) }
    }
}
