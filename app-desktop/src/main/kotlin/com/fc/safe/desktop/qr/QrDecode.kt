package com.fc.safe.desktop.qr

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.client.j2se.BufferedImageLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.slf4j.LoggerFactory
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Decode a QR code (or any barcode ZXing recognises) from a
 * [BufferedImage], from the system clipboard (when it holds an
 * image), or from a file on disk. All three entry points return
 * `null` on "nothing decoded" — wrapped catches swallow ZXing's
 * NotFoundException + ChecksumException + FormatException because
 * the caller really only cares about success-vs-fail.
 *
 * The AWT clipboard path is macOS-friendly: Cmd-Shift-4 region
 * captures and most browser "copy image" actions drop an
 * `Image` onto the clipboard that `DataFlavor.imageFlavor` can
 * extract. Text-containing clipboards are ignored here — that's
 * the normal Paste button's job, not ours.
 *
 * We try both "no hints" and "TRY_HARDER" because screenshots of
 * QR codes are often low-contrast or slightly skewed, and the
 * relaxed decode pass picks them up at the cost of ~10× CPU.
 */
object QrDecode {
    private val log = LoggerFactory.getLogger("QrDecode")

    /** Decode a QR from [image]. `null` if no barcode is found. */
    fun decode(image: BufferedImage): String? {
        val source = BufferedImageLuminanceSource(image)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader()
        // First pass: fast default.
        runCatching { return reader.decode(bitmap).text }
        // Second pass: slower but more tolerant. Worth it for
        // screenshots of QR codes from web pages or photos.
        return runCatching {
            reader.decode(bitmap, mapOf(DecodeHintType.TRY_HARDER to true)).text
        }.getOrNull()
    }

    /**
     * Pull the clipboard's image (if any) and decode it. Returns
     * `null` if the clipboard doesn't hold an image OR if no QR
     * was found in it.
     */
    fun fromClipboardImage(): String? {
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
            val img = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
            decode(toBufferedImage(img))
        } catch (t: Throwable) {
            log.debug("Clipboard QR read failed: {}", t.message)
            null
        }
    }

    /**
     * Decode a QR from a PNG/JPG/etc. on disk. `null` on any
     * read/decode failure.
     */
    fun fromFile(file: File): String? {
        return try {
            val img = ImageIO.read(file) ?: return null
            decode(img)
        } catch (t: Throwable) {
            log.debug("File QR read failed ({}): {}", file.absolutePath, t.message)
            null
        }
    }

    /**
     * AWT `Image` → `BufferedImage` via the usual off-screen
     * paint. `getScaledInstance` + direct cast doesn't always work
     * because the clipboard returns implementation-specific `Image`
     * subclasses.
     */
    private fun toBufferedImage(src: Image): BufferedImage {
        if (src is BufferedImage) return src
        val w = src.getWidth(null).coerceAtLeast(1)
        val h = src.getHeight(null).coerceAtLeast(1)
        val buf = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = buf.createGraphics()
        try {
            g.drawImage(src, 0, 0, null)
        } finally {
            g.dispose()
        }
        return buf
    }
}
