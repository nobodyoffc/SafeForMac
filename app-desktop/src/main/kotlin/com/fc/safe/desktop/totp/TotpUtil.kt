package com.fc.safe.desktop.totp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 Time-based One-Time Password generator. Byte-for-byte
 * parity with Android Safe's `com.fc.safe.utils.TOTPUtil` — same
 * HmacSHA1, same 30-second window, same 6-digit default — so a seed
 * imported via `otpauth://` on either app produces identical codes.
 *
 * Defaults (30s step, 6 digits, SHA-1) match Google Authenticator
 * and the vast majority of TOTP providers. Varying providers exist
 * (8 digits, 60s step, SHA-256) but we match Safe's Android for now.
 */
object TotpUtil {
    const val DEFAULT_STEP_SECONDS: Long = 30
    const val DEFAULT_DIGITS: Int = 6

    /**
     * Compute the current TOTP code for [seed].
     * @param seed raw HMAC key (Base32-decoded from the `secret` param of `otpauth://`)
     * @param unixSeconds wall-clock seconds; caller typically passes `System.currentTimeMillis() / 1000`
     * @param digits number of decimal digits in the output, default 6
     * @param stepSeconds time step window, default 30s
     */
    fun generate(
        seed: ByteArray,
        unixSeconds: Long,
        digits: Int = DEFAULT_DIGITS,
        stepSeconds: Long = DEFAULT_STEP_SECONDS,
    ): String {
        val counter = unixSeconds / stepSeconds
        val data = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            data[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        val hash = hmacSha1(seed, data)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = (
            ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        )
        val modulo = pow10(digits)
        val otp = binary % modulo
        return otp.toString().padStart(digits, '0')
    }

    /** Seconds remaining in the current window for display purposes. */
    fun secondsRemaining(unixSeconds: Long, stepSeconds: Long = DEFAULT_STEP_SECONDS): Int {
        val intoWindow = unixSeconds % stepSeconds
        return (stepSeconds - intoWindow).toInt()
    }

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key, "RAW"))
        return mac.doFinal(data)
    }

    private fun pow10(n: Int): Int {
        var r = 1
        repeat(n) { r *= 10 }
        return r
    }
}
