package com.fc.safe.desktop.totp

import com.fc.safe.desktop.DesktopSecret
import com.google.gson.GsonBuilder
import java.net.URLEncoder

/**
 * Serialises selected TOTP secrets into the two formats other apps
 * speak. Both are plaintext: the Base32 seed IS the secret, and
 * password-wrapping it would break interop with Google Authenticator,
 * 1Password, Authy, etc. If you want a password-wrapped TOTP backup,
 * use the Secrets-level export (deferred follow-up) — that goes
 * through the same base64-CryptoDataByte envelope as the key export.
 */
enum class TotpExportFormat { OTPAUTH, JSON }

internal object TotpExporter {

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /**
     * Build one export blob for the selected secrets.
     *
     * - [OTPAUTH]: `otpauth://totp/<label>?secret=<seed>[&issuer=<x>]`,
     *   one per line. Label and issuer URL-encoded; if the title has
     *   the canonical "Issuer: account" shape we split it into the
     *   two fields so targets that display issuer separately light up.
     * - [JSON]: pretty-printed JSON array of `{"secret","label"}`.
     *   Matches Android `ImportTotpActivity.parseTotpInput`'s JSON
     *   branch so the two apps round-trip.
     *
     * `seedByFid` provides the already-decoded Base32 seed per
     * secret — the screen keeps a cache so we don't re-run Argon2
     * to export.
     */
    fun export(
        secrets: List<DesktopSecret>,
        seedByFid: Map<String, String>,
        format: TotpExportFormat,
    ): String {
        val entries = secrets.mapNotNull { s ->
            val seed = seedByFid[s.id] ?: return@mapNotNull null
            val label = s.title?.takeIf { it.isNotBlank() } ?: "TOTP"
            label to seed
        }
        return when (format) {
            TotpExportFormat.OTPAUTH -> entries.joinToString("\n") { (label, seed) ->
                toOtpauth(label, seed)
            }
            TotpExportFormat.JSON -> {
                val list = entries.map { (label, seed) ->
                    linkedMapOf("secret" to seed, "label" to label)
                }
                gson.toJson(list)
            }
        }
    }

    /** `otpauth://totp/<account>?secret=<seed>&issuer=<issuer>` — issuer split out when the title is "Issuer: account". */
    private fun toOtpauth(label: String, seed: String): String {
        val (issuer, account) = splitIssuerAccount(label)
        val pathLabel = if (!issuer.isNullOrBlank()) "$issuer:$account" else account
        val sb = StringBuilder("otpauth://totp/")
        sb.append(urlEncode(pathLabel))
        sb.append("?secret=").append(seed)
        if (!issuer.isNullOrBlank()) {
            sb.append("&issuer=").append(urlEncode(issuer))
        }
        return sb.toString()
    }

    /** "Issuer: account" → ("Issuer", "account"). Plain "account" → (null, "account"). */
    private fun splitIssuerAccount(label: String): Pair<String?, String> {
        val idx = label.indexOf(':')
        if (idx <= 0) return null to label
        val issuer = label.substring(0, idx).trim()
        val account = label.substring(idx + 1).trim()
        return issuer to account
    }

    /** java.net.URLEncoder normally encodes space as `+`; otpauth wants `%20`. */
    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
}
