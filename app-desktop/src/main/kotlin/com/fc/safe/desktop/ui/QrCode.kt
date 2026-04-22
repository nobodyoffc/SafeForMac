package com.fc.safe.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Draws a QR code for [text] at the size of this composable. Uses ZXing's
 * [QRCodeWriter] to build the BitMatrix and renders each dark module as a
 * black square on Compose Canvas.
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
    val bitMatrix = remember(text) {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 256, 256, hints)
    }
    Canvas(modifier = modifier) { drawQr(bitMatrix, darkColor, lightColor) }
}

private fun DrawScope.drawQr(
    matrix: com.google.zxing.common.BitMatrix,
    darkColor: Color,
    lightColor: Color,
) {
    val w = matrix.width
    val h = matrix.height
    val cellSize = size.width / w
    drawRect(lightColor, topLeft = Offset.Zero, size = size)
    for (x in 0 until w) {
        for (y in 0 until h) {
            if (matrix[x, y]) {
                drawRect(
                    color = darkColor,
                    topLeft = Offset(x * cellSize, y * cellSize),
                    size = Size(cellSize, cellSize),
                )
            }
        }
    }
}
