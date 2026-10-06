package com.fc.safe.platform.macos

import core.crypto.CryptoDataByte
import core.crypto.CryptoDataStr
import core.crypto.Decryptor
import core.crypto.EncryptType
import core.crypto.Encryptor
import data.fcData.AlgorithmId
import data.fcData.FcEntity
import db.LocalDB
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.slf4j.LoggerFactory

/**
 * Process-level holder for the unlocked vault, so screens after unlock can
 * open its DBs and decrypt its records without prompting again.
 *
 * Lifetime: bound by [unlock]/[lock]. [lock] wipes the key and password and
 * closes every DB opened under this session via [DesktopDatabaseManager].
 *
 * **Vault mode** (every wallet from 1.1 on, and every legacy wallet once
 * [VaultUnlocker] has migrated it): [dek] is the vault's random data key.
 * It keys the vault's DBs and encrypts embedded record ciphers (prikeyCipher,
 * contentCipher) as Symkey ciphers, so reading a key costs no Argon2id run.
 *
 * **Legacy mode** ([isLegacy]): a wallet whose migration stopped on a record
 * it could not decrypt. Its DBs stay keyed by the password and new ciphers
 * are Password ciphers, exactly as before 1.1, until the next unlock retries.
 *
 * The password itself stays in RAM for the session too: the re-enter-password
 * gate compares against it and "export under the app password" encrypts with
 * it. [lock] must run on every lock signal (see [LockManager]).
 */
object WalletSession {

    private val log = LoggerFactory.getLogger(WalletSession::class.java)

    @Volatile private var password: CharArray? = null
    @Volatile private var vaultName: String? = null
    @Volatile private var dek: ByteArray? = null

    private val _isLockedFlow = MutableStateFlow(true)
    /**
     * Observable lock state. Flips false on [unlock], true on [lock].
     * The root navigator host watches this so it can drop back to
     * `HomeScreen` when [LockManager] (or anything else) calls
     * [lock] from outside the active screen.
     */
    val isLockedFlow: StateFlow<Boolean> = _isLockedFlow.asStateFlow()

    val isLocked: Boolean
        get() = password == null

    /** True while the open wallet is still a legacy, password-keyed one. */
    val isLegacy: Boolean
        get() = password != null && dek == null

    /** The open vault's id, or a legacy wallet's 6-hex name. */
    fun currentVaultName(): String? = vaultName

    /**
     * Defensive copy of the currently-unlocked password, or null if
     * locked. Caller **must** wipe the returned CharArray with
     * `fill(Char.MIN_VALUE)` after use. Used by flows that need to
     * re-encrypt under the wallet password without re-prompting the
     * user (e.g. export-with-current-password).
     */
    fun currentPasswordCopy(): CharArray? = password?.copyOf()

    /**
     * Called by [VaultUnlocker] once the vault is open. [dek] is null only for
     * a legacy wallet. Takes defensive copies; callers wipe their own arrays.
     */
    fun unlock(password: CharArray, vaultName: String, dek: ByteArray?) {
        this.password = password.copyOf()
        this.vaultName = vaultName
        this.dek = dek?.copyOf()
        // Reset auto-lock timers so the new session gets the full
        // idle window (rather than inheriting "stale" activity).
        LockManager.resetForNewSession()
        _isLockedFlow.value = false
        log.info("Wallet unlocked: {}{}", vaultName, if (dek == null) " (legacy)" else "")
    }

    /** After a password change: later re-enter-password checks compare against [newPassword]. */
    internal fun replacePassword(newPassword: CharArray) {
        check(!isLocked) { "WalletSession is locked" }
        val old = password
        password = newPassword.copyOf()
        old?.fill(Char.MIN_VALUE)
    }

    /**
     * Open (or reuse a cached) DB of the open vault. A vault DB opens with the
     * data key and runs no KDF; a legacy DB runs Argon2id once on first open.
     */
    fun <T : FcEntity> openDb(
        dbName: String,
        entityClass: Class<T>,
        sortType: LocalDB.SortType = LocalDB.SortType.KEY_ORDER,
    ): LocalDB<T> {
        val pwd = password ?: error("WalletSession is locked")
        val key = dek
        if (key != null) {
            return DesktopDatabaseManager.openWithKey(vaultName!!, key, dbName, entityClass, sortType)
        }
        return DesktopDatabaseManager.open(
            password = pwd.copyOf(),
            dbName = dbName,
            entityClass = entityClass,
            sortType = sortType,
        )
    }

    /**
     * End the session. Wipes the key and password and closes all DBs
     * opened under it. Safe to call repeatedly.
     */
    fun lock() {
        val hadSession = password != null
        password?.fill(Char.MIN_VALUE)
        password = null
        dek?.fill(0)
        dek = null
        val name = vaultName
        vaultName = null
        DesktopDatabaseManager.closeAll()
        _isLockedFlow.value = true
        if (hadSession) log.info("Wallet locked: {}", name)
    }

    /**
     * Encrypt [plaintext] for storing inside a record and return the cipher
     * JSON, with no plaintext or key in it.
     *
     * Vault mode: a Symkey cipher (AES-256-GCM) under the data key — what
     * [VaultUnlocker]'s migration converts every record to.
     * Legacy mode: a Password cipher under the password (Argon2id per call,
     * ~500ms), as before 1.1.
     */
    fun encryptToJson(plaintext: ByteArray): String {
        val pwd = password ?: error("WalletSession is locked")
        dek?.let { key ->
            return Encryptor.encryptBySymkeyToJson(plaintext, key) ?: error("encryption under the vault key failed")
        }
        val pwdCopy = pwd.copyOf()
        try {
            val cdb = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
                .encryptByPassword(plaintext, pwdCopy)
            val cds = CryptoDataStr.fromCryptoDataByte(cdb)
            cds.data = null
            return cds.toJson()
        } finally {
            pwdCopy.fill(Char.MIN_VALUE)
        }
    }

    /**
     * Decrypt a cipher JSON made by [encryptToJson] in either mode: a Symkey
     * cipher with the data key, a Password cipher with the password.
     * Caller owns the returned byte[] and must wipe it after use.
     */
    fun decryptFromJson(json: String): ByteArray {
        val pwd = password ?: error("WalletSession is locked")
        val type = runCatching { CryptoDataByte.fromJson(json).type }.getOrNull()
        val key = dek
        val cdb = if (type == EncryptType.Symkey) {
            check(key != null) { "a vault-key cipher in a legacy wallet" }
            Decryptor().decryptJsonBySymkey(json, key)
        } else {
            val pwdCopy = pwd.copyOf()
            try {
                Decryptor().decryptJsonByPassword(json, pwdCopy)
            } finally {
                pwdCopy.fill(Char.MIN_VALUE)
            }
        }
        check(cdb.code == 0) { "decrypt failed: code=${cdb.code} msg=${cdb.message}" }
        return cdb.data
    }
}
