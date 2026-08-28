package com.fc.safe.desktop

import com.fc.safe.desktop.avatar.AvatarMaker
import java.io.File

/**
 * Throwaway sanity check: composite a few avatars and dump sizes.
 * Run with `./gradlew :app-desktop:avatarSmoke`.
 */
fun main() {
    val samples = listOf(
        "FEk3M1oQfHrCZ4HLxwZrAyJraWcT1Un4Uv",
        "1PikachuAddress111111111111111111111",
        "FHwPnGHqpNkSjs1XPdH6nFaTpSEDAGSe2P",
    )
    val outDir = File("build/avatar-smoke").apply { mkdirs() }
    samples.forEach { addr ->
        val bytes = AvatarMaker.makeAvatar(addr)
        if (bytes == null) {
            println("FAIL: $addr")
        } else {
            val f = File(outDir, "${addr.take(12)}.png").apply { writeBytes(bytes) }
            println("OK : $addr -> ${bytes.size} bytes -> ${f.path}")
        }
    }
}
