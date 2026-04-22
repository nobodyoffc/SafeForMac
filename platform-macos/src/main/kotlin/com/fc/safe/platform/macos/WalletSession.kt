package com.fc.safe.platform.macos

import core.crypto.CryptoDataStr
import core.crypto.Decryptor
import core.crypto.Encryptor
import data.fcData.FcEntity
import db.LocalDB
import org.slf4j.LoggerFactory

/**
 * Process-level holder for an unlocked wallet's password, so screens after
 * unlock can open additional per-wallet DBs without prompting the user again.
 *
 * Lifetime: bound by [unlock]/[lock]. [lock] wipes the stored CharArray and
 * closes every DB opened under this session via [DesktopDatabaseManager].
 *
 * Security model: the password stays in RAM for the session's duration
 * (same model as every wallet that supports "unlock once, use many
 * screens"). A dedicated lock signal — focus loss, screen lock, sleep,
 * inactivity timer (§3.5 of the macOS plan) — MUST call [lock] before we
 * ship v1. Currently only the user-visible "Lock" button triggers it.
 */
object WalletSession {

    private val log = LoggerFactory.getLogger(WalletSession::class.java)

    @Volatile private var password: CharArray? = null
    @Volatile private var passwordName: String? = null

    val isLocked: Boolean
        get() = password == null

    fun currentPasswordName(): String? = passwordName

    /**
     * Called by the unlock flow once the password has been verified
     * against the wallet's vault DB. The caller retains ownership of its
     * own char[]; we take a defensive copy.
     */
    fun unlock(password: CharArray, passwordName: String) {
        this.password = password.copyOf()
        this.passwordName = passwordName
        log.info("Wallet unlocked: {}", passwordName)
    }

    /**
     * Open (or reuse a cached) per-wallet DB under this session's password.
     * First open of a given dbName runs Argon2 once inside SqliteDB;
     * subsequent opens of the same dbName are cache hits in
     * [DesktopDatabaseManager] and return immediately.
     */
    fun <T : FcEntity> openDb(
        dbName: String,
        entityClass: Class<T>,
        sortType: LocalDB.SortType = LocalDB.SortType.KEY_ORDER,
    ): LocalDB<T> {
        val pwd = password ?: error("WalletSession is locked")
        return DesktopDatabaseManager.open(
            password = pwd.copyOf(),
            dbName = dbName,
            entityClass = entityClass,
            sortType = sortType,
        )
    }

    /**
     * End the session. Wipes the stored password and closes all DBs
     * opened under it. Safe to call repeatedly.
     */
    fun lock() {
        val hadSession = password != null
        password?.fill(Char.MIN_VALUE)
        password = null
        val name = passwordName
        passwordName = null
        DesktopDatabaseManager.closeAll()
        if (hadSession) log.info("Wallet locked: {}", name)
    }

    /**
     * Encrypt [plaintext] with the session password and return a
     * [CryptoDataStr]-shaped JSON. The `data` field is nulled before
     * serialization so the plaintext never goes to disk — the JSON
     * contains only ciphertext + IV + sum + algorithm + KDF marker.
     *
     * Uses [Encryptor.encryptByPassword], which runs Argon2id on each
     * call (~500ms). Appropriate for per-key operations but don't call
     * in hot loops.
     */
    fun encryptToJson(plaintext: ByteArray): String {
        val pwd = password ?: error("WalletSession is locked")
        val pwdCopy = pwd.copyOf()
        try {
            val cdb = Encryptor().encryptByPassword(plaintext, pwdCopy)
            val cds = CryptoDataStr.fromCryptoDataByte(cdb)
            cds.data = null
            return cds.toJson()
        } finally {
            pwdCopy.fill(Char.MIN_VALUE)
        }
    }

    /**
     * Decrypt a [CryptoDataStr] JSON produced by [encryptToJson].
     * Caller owns the returned byte[] and must wipe it after use.
     */
    fun decryptFromJson(json: String): ByteArray {
        val pwd = password ?: error("WalletSession is locked")
        val pwdCopy = pwd.copyOf()
        try {
            val cdb = Decryptor().decryptJsonByPassword(json, pwdCopy)
            check(cdb.code == 0) { "decrypt failed: code=${cdb.code} msg=${cdb.message}" }
            return cdb.data
        } finally {
            pwdCopy.fill(Char.MIN_VALUE)
        }
    }
}
