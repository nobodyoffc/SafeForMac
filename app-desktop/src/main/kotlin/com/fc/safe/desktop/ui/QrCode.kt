package com.fc.safe.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import kotlin.math.floor

/**
 * Encoder hints, kept identical to Android Safe's `QRCodeGenerator` so a
 * code made here scans the same way on a phone: UTF-8 payloads, error
 * correction M (~15% recovery — the sweet spot between density and
 * tolerance for a screen-to-camera hop), and a 2-module quiet zone.
 */
private val QR_HINTS = mapOf(
    EncodeHintType.CHARACTER_SET to "UTF-8",
    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    EncodeHintType.MARGIN to 2,
)

/**
 * Encode [text] to a module matrix, or `null` when ZXing refuses it —
 * empty input, or more data than the largest QR version can hold.
 * Callers must handle `null`: this is reached from composables, and an
 * exception on the AWT event thread takes the window down with it.
 *
 * Width/height 0 asks ZXing for the *natural* matrix — one cell per
 * module — instead of a pre-scaled bitmap. Scaling is the renderer's
 * job, and a 33×33 matrix beats a 256×256 one by ~60× on the per-cell
 * draw loop.
 */
fun encodeQrMatrix(text: String): BitMatrix? =
    runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, QR_HINTS) }
        .getOrNull()

/**
 * Draws a QR code for [text] at the size of this composable. Renders
 * nothing when [text] can't be encoded (see [encodeQrMatrix]).
 *
 * Size should be provided via [modifier] (typically `Modifier.size(...)`).
 */
@Composable
fun QrCode(
    text: String,
    modifier: Modifier = Modifier,
    darkColor: Color = Color.Black,
    lightColor: Color = Color.White,
) {
    val bitMatrix = remember(text) { encodeQrMatrix(text) }
    Canvas(modifier = modifier) {
        drawRect(lightColor, topLeft = Offset.Zero, size = size)
        if (bitMatrix != null) drawQr(bitMatrix, darkColor)
    }
}

/**
 * Paints the dark modules of [matrix] over the full draw area.
 *
 * Module edges are snapped to whole device pixels and each cell is drawn
 * to the *next* module's boundary rather than at a computed width. A
 * fractional cell size otherwise leaves antialiased hairlines between
 * neighbouring dark modules, which reads to a camera as a broken module
 * and costs decodes on the very payloads that need the most tolerance.
 */
private fun DrawScope.drawQr(matrix: BitMatrix, darkColor: Color) {
    val w = matrix.width
    val h = matrix.height
    val cellW = size.width / w
    val cellH = size.height / h
    for (x in 0 until w) {
        val left = floor(x * cellW)
        val right = floor((x + 1) * cellW)
        for (y in 0 until h) {
            if (!matrix[x, y]) continue
            val top = floor(y * cellH)
            val bottom = floor((y + 1) * cellH)
            drawRect(
                color = darkColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
            )
        }
    }
}

/**
 * Rasterise [matrix] to a black-on-white [BufferedImage] for saving to
 * disk, scaling each module up to [scale] pixels and keeping the quiet
 * zone ZXing already baked into the matrix.
 */
fun qrMatrixToImage(matrix: BitMatrix, scale: Int = 8): BufferedImage {
    val w = matrix.width * scale
    val h = matrix.height * scale
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    try {
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = java.awt.Color.BLACK
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                if (matrix[x, y]) g.fillRect(x * scale, y * scale, scale, scale)
            }
        }
    } finally {
        g.dispose()
    }
    return img
}
