package com.fc.safe.platform.macos

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import core.crypto.VaultKey
import org.slf4j.LoggerFactory
import utils.BytesUtils
import utils.IdNameUtils
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * One entry per wallet vault. Stored plaintext JSON at:
 *
 *     ~/Library/Application Support/com.fc.safe/config/configurations.json
 *
 * Two kinds of entry, told apart by the length of [passwordName] (the map key):
 *
 * - **Vault** (12 hex chars, [VaultKey.VAULT_ID_LENGTH]): [passwordName] is a
 *   random vault id and [dekCipher] is the vault's data key wrapped under
 *   Argon2id(password). Nothing derived from the password is stored, so the
 *   file gives an attacker nothing to test guesses against faster than Argon2id.
 * - **Legacy** (6 hex chars): [passwordName] is the double-SHA256 prefix of the
 *   password, as every build before 1.1 wrote it. Its DBs are keyed by the
 *   password itself. [VaultUnlocker] migrates it to a vault on first unlock;
 *   the migration fields record how far that got, so a crash can resume.
 *
 * Same model and field names as Android Safe 2.4's Configure (dekCipher,
 * legacyName), so the two stay easy to compare.
 */
data class DesktopConfigure(
    val passwordName: String,
    val createdAt: Long,
    /** User-visible label. Optional; defaults to passwordName. */
    val label: String? = null,
    /** Data key wrapped under the password ([VaultKey.wrap]); null for a legacy entry. */
    val dekCipher: String? = null,
    /** On a vault made by migration, the legacy name it came from, until the legacy files are gone. */
    val legacyName: String? = null,
    /** [core.crypto.VaultMigration.State] name; null means LEGACY (or a vault made fresh). */
    val migrationState: String? = null,
    /** Vault id chosen by a migration that has not flipped yet. */
    val pendingVaultId: String? = null,
    /** Wrapped data key chosen by a migration that has not flipped yet. */
    val pendingDekCipher: String? = null,
) {
    val isLegacy: Boolean get() = VaultKey.isLegacyName(passwordName)
}

/**
 * JSON-backed replacement for Safe Android's ConfigureManager: which vaults
 * exist on this machine and how to open them. Holds no key material in
 * memory; [WalletSession] holds the open vault's key.
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

    /** Forgets the in-memory copy, so the next call reads the file again. */
    @Synchronized
    fun reload() {
        loaded = false
        ensureLoaded()
    }

    /**
     * Writes the whole map to a temp file, forces it to disk, and renames it
     * over the old file. A crash leaves either the old file or the new one,
     * never a torn one — the wrapped data key lives only here.
     */
    private fun save() {
        Files.createDirectories(configFile.parent)
        val tmp = configFile.resolveSibling("configurations.json.tmp")
        FileChannel.open(
            tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
        ).use { ch ->
            ch.write(StandardCharsets.UTF_8.encode(gson.toJson(configs.toSortedMap())))
            ch.force(true)
        }
        Files.move(tmp, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
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

    fun get(passwordName: String): DesktopConfigure? {
        ensureLoaded()
        return configs[passwordName]
    }

    /**
     * The legacy 6-hex name for [password]. Only legacy entries are found by
     * it; a vault is found by unwrapping its data key.
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

    /** Adds or replaces [entry], durably. */
    @Synchronized
    fun put(entry: DesktopConfigure) {
        ensureLoaded()
        configs[entry.passwordName] = entry
        save()
    }

    /** Removes [oldName] and adds [entry] in one durable write. */
    @Synchronized
    fun replace(oldName: String, entry: DesktopConfigure) {
        ensureLoaded()
        configs.remove(oldName)
        configs[entry.passwordName] = entry
        save()
    }

    /**
     * Registers a legacy, password-named wallet — what every build before 1.1
     * did on "Create password". Kept for tests that need a legacy vault to
     * migrate; the app creates vaults with [VaultUnlocker.create].
     */
    fun createLegacyFor(password: CharArray, label: String? = null): DesktopConfigure {
        ensureLoaded()
        val name = passwordNameFor(password)
        configs[name]?.let { return it }
        return DesktopConfigure(passwordName = name, createdAt = System.currentTimeMillis(), label = label)
            .also { put(it) }
    }

    @Synchronized
    fun remove(passwordName: String) {
        ensureLoaded()
        configs.remove(passwordName)?.let { save() }
    }

    @Synchronized
    fun clearAll() {
        configs.clear()
        if (Files.exists(configFile)) Files.delete(configFile)
    }
}
