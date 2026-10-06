package com.fc.safe.platform.macos

import core.crypto.VaultKey
import core.crypto.VaultMigration
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.security.SecureRandom

/**
 * Opens, creates and re-keys wallet vaults — the desktop counterpart of
 * Android Safe 2.4's VaultUnlocker and ConfigureManager.
 *
 * A vault is found by unwrapping its data key with the password; a legacy
 * wallet by its password-derived name, confirmed by opening its `vault` DB,
 * and then moved to a data key and a random vault id ([DesktopVaultStore],
 * driven by FC-JDK's [VaultMigration]). Every path runs Argon2id at least
 * once, so call these off the UI thread.
 */
object VaultUnlocker {

    private val log = LoggerFactory.getLogger(VaultUnlocker::class.java)

    /** The DB every legacy wallet has; opening it is how a legacy password is confirmed. */
    const val LEGACY_VAULT_DB = "vault"

    sealed class UnlockResult {
        /**
         * [WalletSession] is unlocked. [legacy] is true if the wallet is still
         * password-keyed because [unreadableRecordId] stopped its migration.
         */
        data class Opened(val vaultName: String, val legacy: Boolean, val unreadableRecordId: String? = null) : UnlockResult()
        data object WrongPassword : UnlockResult()
    }

    sealed class CreateResult {
        data class Created(val vaultId: String) : CreateResult()
        /** The password already opens a wallet on this Mac. */
        data object AlreadyExists : CreateResult()
    }

    sealed class ChangeResult {
        data object Changed : ChangeResult()
        data object WrongOldPassword : ChangeResult()
        /** The new password already opens a wallet (this one included). */
        data object NewPasswordInUse : ChangeResult()
        /** The open wallet has not moved to a data key yet, so there is nothing to re-wrap. */
        data object LegacyWallet : ChangeResult()
    }

    /**
     * Opens the wallet [password] belongs to and unlocks [WalletSession] with it.
     *
     * @param migrate false to open a legacy wallet as it is (tests only).
     */
    fun unlock(password: CharArray, migrate: Boolean = true): UnlockResult {
        DesktopConfigureManager.reload()
        val configs = DesktopConfigureManager.all()

        legacyFor(password, configs)?.let { legacy ->
            openLegacy(legacy, password, migrate)?.let { return it }
        }

        for (entry in configs.values) {
            if (entry.isLegacy || entry.dekCipher == null) continue
            val dek = VaultKey.unwrap(entry.dekCipher, password) ?: continue
            try {
                val from = entry.legacyName
                if (migrate && from != null && entry.migrationState == VaultMigration.State.FLIPPED.name) {
                    // A crash after the flip left the legacy files behind; finish removing them.
                    runCatching { VaultMigration.run(DesktopVaultStore(from, password), password, throwawayKey()) }
                        .onFailure { log.error("Failed to remove the legacy files of {}", from, it) }
                }
                WalletSession.unlock(password, entry.passwordName, dek)
                return UnlockResult.Opened(entry.passwordName, legacy = false)
            } finally {
                dek.fill(0)
            }
        }
        return UnlockResult.WrongPassword
    }

    /** Creates a vault for [password], unless the password already opens one, and unlocks it. */
    fun create(password: CharArray, label: String? = null): CreateResult {
        if (passwordInUse(password)) return CreateResult.AlreadyExists
        val dek = VaultKey.newDek()
        try {
            val vaultId = VaultKey.newVaultId()
            DesktopConfigureManager.put(
                DesktopConfigure(
                    passwordName = vaultId,
                    createdAt = System.currentTimeMillis(),
                    label = label,
                    dekCipher = VaultKey.wrap(dek, password),
                )
            )
            WalletSession.unlock(password, vaultId, dek)
            log.info("Created vault {}", vaultId)
            return CreateResult.Created(vaultId)
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Re-wraps the open vault's data key under [newPassword]. No record is
     * touched: the data key, and so every cipher, stays the same.
     */
    fun changePassword(oldPassword: CharArray, newPassword: CharArray): ChangeResult {
        check(!WalletSession.isLocked) { "WalletSession is locked" }
        if (WalletSession.isLegacy) return ChangeResult.LegacyWallet
        val entry = WalletSession.currentVaultName()?.let(DesktopConfigureManager::get)
            ?: return ChangeResult.LegacyWallet
        val dekCipher = entry.dekCipher ?: return ChangeResult.LegacyWallet
        if (VaultKey.unwrap(dekCipher, oldPassword) == null) return ChangeResult.WrongOldPassword
        if (passwordInUse(newPassword)) return ChangeResult.NewPasswordInUse
        val rewrapped = VaultKey.rewrap(dekCipher, oldPassword, newPassword) ?: return ChangeResult.WrongOldPassword
        DesktopConfigureManager.put(entry.copy(dekCipher = rewrapped))
        WalletSession.replacePassword(newPassword)
        log.info("Changed the password of vault {}", entry.passwordName)
        return ChangeResult.Changed
    }

    /** True if [password] opens a legacy wallet or unwraps any vault's data key. */
    fun passwordInUse(password: CharArray): Boolean {
        val configs = DesktopConfigureManager.all()
        if (legacyFor(password, configs, register = false) != null) return true
        return configs.values.any { e ->
            !e.isLegacy && e.dekCipher != null &&
                VaultKey.unwrap(e.dekCipher, password)?.also { it.fill(0) } != null
        }
    }

    /**
     * The legacy wallet named after [password], if there is one.
     *
     * Builds before 1.1 could leave a wallet without its config entry: their
     * `createFor` saved the map before adding the new entry, so a wallet made
     * in a session was lost from `configurations.json` and never unlocked
     * again. Such a wallet is found here by its `db/{name}/vault.sqlite`, and
     * only if [password] opens that DB — the same proof the entry stood for.
     * With [register] its entry is written back so it can migrate.
     */
    private fun legacyFor(
        password: CharArray,
        configs: Map<String, DesktopConfigure>,
        register: Boolean = true,
    ): DesktopConfigure? {
        val name = DesktopConfigureManager.passwordNameFor(password)
        configs[name]?.let { return it.takeIf { e -> e.isLegacy } }
        // Already migrated (flipped but not cleaned up): the vault entry owns it.
        if (configs.values.any { it.legacyName == name }) return null
        if (!legacyPasswordOpens(name, password)) return null
        if (!register) return DesktopConfigure(passwordName = name, createdAt = 0)
        log.warn("Found wallet {} on disk without a config entry; restoring its entry", name)
        return DesktopConfigureManager.createLegacyFor(password)
    }

    /** @return the result for a confirmed legacy wallet, or null if the password only shares its name. */
    private fun openLegacy(legacy: DesktopConfigure, password: CharArray, migrate: Boolean): UnlockResult? {
        val legacyName = legacy.passwordName
        if (!legacyPasswordOpens(legacyName, password)) return null

        if (!migrate) {
            WalletSession.unlock(password, legacyName, null)
            return UnlockResult.Opened(legacyName, legacy = true)
        }

        // Nothing may hold a file the migration copies or deletes.
        WalletSession.lock()
        try {
            val moved = VaultMigration.run(DesktopVaultStore(legacyName, password), password, throwawayKey())
            if (moved.isMigrated) {
                try {
                    WalletSession.unlock(password, moved.vaultId, moved.dek)
                } finally {
                    moved.dek.fill(0)
                }
                log.info("Moved legacy wallet {} to vault {}", legacyName, moved.vaultId)
                return UnlockResult.Opened(moved.vaultId, legacy = false)
            }
            log.warn("Wallet {} keeps its legacy key: {} cannot be decrypted", legacyName, moved.unreadableRecordId)
            WalletSession.unlock(password, legacyName, null)
            return UnlockResult.Opened(legacyName, legacy = true, unreadableRecordId = moved.unreadableRecordId)
        } catch (e: Exception) {
            log.error("Vault migration of {} stopped", legacyName, e)
            return reopenAfterFailure(legacyName, password)
        }
    }

    /** Opens whichever side of the flip the saved configuration is on. */
    private fun reopenAfterFailure(legacyName: String, password: CharArray): UnlockResult {
        DesktopDatabaseManager.closeAll()
        DesktopConfigureManager.reload()
        val configs = DesktopConfigureManager.all()
        if (configs[legacyName]?.isLegacy == true) {
            WalletSession.unlock(password, legacyName, null)
            return UnlockResult.Opened(legacyName, legacy = true)
        }
        for (flipped in configs.values) {
            if (flipped.legacyName != legacyName || flipped.dekCipher == null) continue
            val dek = VaultKey.unwrap(flipped.dekCipher, password) ?: continue
            try {
                WalletSession.unlock(password, flipped.passwordName, dek)
            } finally {
                dek.fill(0)
            }
            return UnlockResult.Opened(flipped.passwordName, legacy = false)
        }
        return UnlockResult.WrongPassword
    }

    /** True if [password] opens the legacy wallet's `vault` DB — its validator, not just its 6-hex name. */
    private fun legacyPasswordOpens(legacyName: String, password: CharArray): Boolean {
        val dir = DesktopAppPaths.dbDir.resolve(legacyName)
        if (!Files.exists(dir.resolve("$LEGACY_VAULT_DB.sqlite"))) return false
        val db = SqliteDB(db.LocalDB.SortType.KEY_ORDER, RawEntity::class.java)
        return try {
            db.initializeWithPassword(password.copyOf(), null, null, dir.toString(), LEGACY_VAULT_DB)
            true
        } catch (_: WrongPasswordException) {
            false
        } finally {
            db.close()
        }
    }

    /**
     * [VaultMigration.run] first tries each record with the legacy wallet's
     * symkey. Desktop legacy records have none — they are Password ciphers,
     * which [DesktopVaultStore] converts itself — so it gets a random key no
     * record can open, and the DEK path does the work.
     */
    private fun throwawayKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }
}

/** Stand-in entity type for SqliteDB instances that only move raw rows. */
internal class RawEntity : data.fcData.FcEntity()
