package com.fc.safe.desktop.avatar

import org.slf4j.LoggerFactory
import java.awt.AlphaComposite
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

/**
 * Deterministic 10-layer avatar generator, ported from
 * `com.fc.fc_ajdk.feature.avatar.AvatarMaker` (Android).
 *
 * Layer PNGs live at `resources/avatar/avatar_<i>_<j>.png` (i ∈ 0..9, j ∈ 0..57).
 * For a given address, [makeAvatar] picks layer j from type i using a fixed
 * 10-char window of the address; same address → same avatar bytes.
 *
 * Swing/AWT-based (BufferedImage + ImageIO) so it runs on Compose Desktop
 * without Android dependencies. Decoded layers are cached in memory since
 * there are only 580 of them and they're small.
 */
object AvatarMaker {
    private val log = LoggerFactory.getLogger("AvatarMaker")
    private const val FEATURE_COUNT = 10

    private val charIndex: Map<Char, Int> = buildMap {
        val order = "123456789" +
            "ABCDEFGH" + "JKLMN" + "PQRSTUVWXYZ" +
            "abcdefghijk" + "mnopqrstuvwxyz"
        order.forEachIndexed { idx, ch -> put(ch, idx) }
    }

    // ConcurrentHashMap disallows null values, so sentinel-wrap misses.
    private val layerCache = ConcurrentHashMap<String, BufferedImage>()
    private val missingLayers = ConcurrentHashMap.newKeySet<String>()

    private fun loadLayer(resourceName: String): BufferedImage? {
        if (resourceName in missingLayers) return null
        layerCache[resourceName]?.let { return it }
        val path = "/avatar/$resourceName.png"
        val stream = AvatarMaker::class.java.getResourceAsStream(path)
        if (stream == null) {
            log.warn("Avatar layer not found: {}", path)
            missingLayers.add(resourceName)
            return null
        }
        val decoded = runCatching { stream.use { ImageIO.read(it) } }
            .onFailure { log.warn("Failed to decode {}: {}", path, it.message) }
            .getOrNull()
        if (decoded == null) {
            missingLayers.add(resourceName)
            return null
        }
        return layerCache.putIfAbsent(resourceName, decoded) ?: decoded
    }

    /**
     * Byte-for-byte port of Android's `getResourceIdByAddress`:
     * uses the **fixed** index `33 - 4 - i` (= 29 − i), i.e. characters
     * at positions 20..29 of the address, regardless of the address's
     * actual length. Using `address.length - 4 - i` would drift by one
     * for 34-char FCH FIDs and produce a different avatar than Android.
     *
     * Unmapped chars yield `null` (parity with Android's
     * `"avatar_" + i + "_" + null` path, which then fails to resolve and
     * the layer is skipped). The 10-position window must fit, so any
     * address shorter than 30 chars has no valid avatar.
     */
    private fun resourceNamesFor(address: String): Array<String?>? {
        if (address.length < 30) return null
        return Array(FEATURE_COUNT) { i ->
            val c = address[29 - i]
            charIndex[c]?.let { j -> "avatar_${i}_$j" }
        }
    }

    /** Composite avatar layers for [address] and return PNG bytes, or null on failure. */
    fun makeAvatar(address: String): ByteArray? {
        val names = resourceNamesFor(address) ?: return null
        val base = names[0]?.let { loadLayer(it) } ?: return null

        val result = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.drawImage(base, 0, 0, null)
            g.composite = AlphaComposite.SrcOver
            for (i in 1 until FEATURE_COUNT) {
                val layer = names[i]?.let { loadLayer(it) } ?: continue
                g.drawImage(layer, 0, 0, null)
            }
        } finally {
            g.dispose()
        }

        val out = ByteArrayOutputStream()
        return if (ImageIO.write(result, "png", out)) out.toByteArray() else null
    }
}
