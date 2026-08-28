package com.fc.safe.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fc.safe.desktop.avatar.AvatarMaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * Circular avatar rendered from an FID via [AvatarMaker]. Matches
 * Android's FID-avatar derivation byte-for-byte (see project memory
 * on the `29 - i` index). Works for both P2PKH (F/1-prefix) and
 * P2SH (3-prefix) 34-char addresses, since both feed into the same
 * byte-picker.
 *
 * Background rendering via [produceState] keeps the compositor off
 * the avatar's composite step — 10 PNG layers alpha-blended on the
 * Default dispatcher, cached internally by AvatarMaker. First paint
 * shows a grey Person placeholder for ~1 frame then swaps to the
 * real bitmap; callers don't need to pre-warm anything.
 */
@Composable
fun FidAvatar(fid: String, size: Dp = 40.dp) {
    val bytes by produceState<ByteArray?>(initialValue = null, fid) {
        value = withContext(Dispatchers.Default) {
            runCatching { AvatarMaker.makeAvatar(fid) }.getOrNull()
        }
    }
    val painter = remember(bytes) {
        bytes?.let { BitmapPainter(SkiaImage.makeFromEncoded(it).toComposeImageBitmap()) }
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = "Avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = "Avatar placeholder",
                tint = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}
