package com.fc.safe.platform.macos

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import org.slf4j.LoggerFactory
import utils.BytesUtils
import utils.IdNameUtils
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * One entry per known wallet password. Stored plaintext JSON at:
 *
 *     ~/Library/Application Support/com.fc.safe/config/configurations.json
 *
 * Safe to store: `passwordName` is a 6-char hex prefix of the double-SHA256
 * of the password bytes — it identifies a wallet but does not enable
 * decryption. No secret material is written here.
 */
data class DesktopConfigure(
    val passwordName: String,
    val createdAt: Long,
    /** User-visible label. Optional; defaults to passwordName. */
    val label: String? = null,
)

/**
 * JSON-backed replacement for Safe Android's ConfigureManager. Knows which
 * wallets exist on this machine and provides the lookup the UI needs to
 * decide "is this a new password or an existing wallet?".
 *
 * Deliberately narrow: holds no Configure/symkey/auth state — those
 * live in memory during a session and are managed by DesktopDatabaseManager.
 */
object DesktopConfigureManager {

    private val log = LoggerFactory.getLogger(DesktopConfigureManager::class.java)
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configs = ConcurrentHashMap<String, DesktopConfigure>()

    @Volatile private var loaded = false

    private val configFile: Path
        get() = DesktopAppPaths.configDir.resolve("configurations.json")

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        Files.createDirectories(configFile.parent)
        if (Files.exists(configFile)) {
            val json = Files.readString(configFile)
            val type = object : TypeToken<Map<String, DesktopConfigure>>() {}.type
            val parsed: Map<String, DesktopConfigure>? = gson.fromJson(json, type)
            if (parsed != null) {
                configs.clear()
                configs.putAll(parsed)
            }
        }
        loaded = true
        log.info("Loaded {} wallet configuration(s)", configs.size)
    }

    private fun save() {
        Files.createDirectories(configFile.parent)
        Files.writeString(configFile, gson.toJson(configs))
    }

    /** Every known wallet, keyed by passwordName. */
    fun all(): Map<String, DesktopConfigure> {
        ensureLoaded()
        return configs.toMap()
    }

    fun isEmpty(): Boolean {
        ensureLoaded()
        return configs.isEmpty()
    }

    /**
     * Compute the passwordName for [password] without mutating state.
     * Caller retains ownership of the char[] and is responsible for wiping it.
     */
    fun passwordNameFor(password: CharArray): String {
        val bytes = BytesUtils.charArrayToByteArray(password, StandardCharsets.UTF_8)
        return try {
            IdNameUtils.makePasswordHashName(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** True if a wallet with this password's hash-name already exists. */
    fun exists(password: CharArray): Boolean {
        ensureLoaded()
        return configs.containsKey(passwordNameFor(password))
    }

    /**
     * Register a new wallet for [password]. Idempotent — if the password
     * already maps to a configuration, returns the existing one.
     */
    fun createFor(password: CharArray, label: String? = null): DesktopConfigure {
        ensureLoaded()
        val name = passwordNameFor(password)
        return configs.getOrPut(name) {
            DesktopConfigure(passwordName = name, createdAt = System.currentTimeMillis(), label = label)
                .also { save() }
        }
    }

    fun remove(passwordName: String) {
        ensureLoaded()
        configs.remove(passwordName)?.let { save() }
    }

    fun clearAll() {
        configs.clear()
        if (Files.exists(configFile)) Files.delete(configFile)
    }
}
