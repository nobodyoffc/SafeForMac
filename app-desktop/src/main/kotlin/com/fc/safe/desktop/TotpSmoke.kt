package com.fc.safe.desktop

import com.fc.safe.desktop.backup.Base32Shim
import com.fc.safe.desktop.totp.TotpUtil
import kotlin.system.exitProcess

/**
 * RFC 6238 Appendix B test vectors (SHA-1 variant only). Validates
 * our TotpUtil against the same seed + unix time inputs as the spec
 * and as Google Authenticator. If this passes, a seed shared with
 * Android Safe will produce the same codes on both apps.
 *
 * Run: `./gradlew :app-desktop:totpSmoke`
 */
fun main() {
    // RFC 6238 reference seed: ASCII "12345678901234567890" = 20 bytes
    val asciiSeed = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    data class Vector(val t: Long, val expected8: String)
    val vectors = listOf(
        Vector(59L, "94287082"),
        Vector(1111111109L, "07081804"),
        Vector(1111111111L, "14050471"),
        Vector(1234567890L, "89005924"),
        Vector(2000000000L, "69279037"),
        Vector(20000000000L, "65353130"),
    )

    var pass = 0
    var fail = 0
    for (v in vectors) {
        val got8 = TotpUtil.generate(asciiSeed, v.t, digits = 8)
        val got6 = TotpUtil.generate(asciiSeed, v.t, digits = 6)
        val expected6 = v.expected8.takeLast(6)
        val ok = got8 == v.expected8 && got6 == expected6
        if (ok) pass++ else fail++
        println(
            "t=${v.t.toString().padStart(12)} " +
                "expected8=${v.expected8} got8=$got8 " +
                "expected6=$expected6 got6=$got6 " +
                if (ok) "OK" else "FAIL"
        )
    }

    // Base32 round-trip sanity: seed → Base32 → seed.
    val base32 = Base32Shim.toBase32(asciiSeed)
    val roundTripped = Base32Shim.fromBase32(base32)
    check(roundTripped.contentEquals(asciiSeed)) {
        "Base32 round-trip broken: back=${roundTripped.joinToString("") { "%02x".format(it) }}"
    }
    println("Base32 round-trip OK ($base32)")

    println()
    if (fail == 0) {
        println("TOTP SMOKE PASSED ($pass/${vectors.size})")
        exitProcess(0)
    } else {
        println("TOTP SMOKE FAILED ($fail/${vectors.size})")
        exitProcess(1)
    }
}
