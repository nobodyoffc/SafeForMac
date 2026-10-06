package com.fc.safe.platform.macos

import data.fcData.FcEntity
import db.LocalDB
import org.slf4j.LoggerFactory
import utils.BytesUtils
import utils.IdNameUtils
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-vault [SqliteDB] lifecycle holder.
 *
 * A data-key vault ([openWithKey]) keeps its files under
 *
 *     ~/Library/Application Support/com.fc.safe/db/{vaultId}/{dbName}.sqlite
 *
 * One [SqliteDB] instance per `(passwordHashPrefix, dbName)` tuple of a
 * legacy wallet ([open]), backed by an encrypted file under:
 *
 *     ~/Library/Application Support/com.fc.safe/db/{passwordHashPrefix}/{dbName}.sqlite
 *
 * The caller provides the password each time a context is opened; the
 * manager derives the 6-char password-hash prefix via FC-JDK's
 * [IdNameUtils.makePasswordHashName] and passes the password straight through
 * to the SqliteDB constructor. Password bytes are wiped here after the symkey
 * has been derived inside SqliteDB.
 */
object DesktopDatabaseManager {

    private val log = LoggerFactory.getLogger(DesktopDatabaseManager::class.java)
    private val openDbs = ConcurrentHashMap<String, LocalDB<*>>()

    /**
     * Open or reopen a DB context.
     *
     * @throws WrongPasswordException if [password] does not decrypt an
     *   existing DB file under the same prefix + name.
     */
    fun <T : FcEntity> open(
        password: CharArray,
        dbName: String,
        entityClass: Class<T>,
        sortType: LocalDB.SortType = LocalDB.SortType.KEY_ORDER,
    ): LocalDB<T> {
        // Compute the 6-char password-hash prefix. This is what names the
        // per-wallet directory. Derived from UTF-8 password bytes, matching
        // Android's IdNameUtils.makePasswordHashName.
        val passwordBytes = BytesUtils.charArrayToByteArray(password, StandardCharsets.UTF_8)
        val passwordHashPrefix = try {
            IdNameUtils.makePasswordHashName(passwordBytes)
        } finally {
            // passwordBytes is our local copy; wipe it now
            passwordBytes.fill(0)
        }

        val cacheKey = "$passwordHashPrefix/$dbName"
        openDbs[cacheKey]?.let {
            @Suppress("UNCHECKED_CAST")
            return it as LocalDB<T>
        }

        val dbPath = DesktopAppPaths.dbDir.resolve(passwordHashPrefix)
        Files.createDirectories(dbPath)

        val db = SqliteDB(sortType, entityClass)
        try {
            db.initializeWithPassword(password, null, null, dbPath.toString(), dbName)
        } catch (e: Throwable) {
            db.close()
            throw e
        }
        openDbs[cacheKey] = db
        log.info("Opened db={} under context={}", dbName, passwordHashPrefix)
        return db
    }

    /**
     * Open or reopen a DB of a data-key vault, under
     * `db/{vaultId}/{dbName}.sqlite`. No KDF runs: [dek] keys the rows.
     *
     * @throws WrongPasswordException if [dek] does not open an existing file.
     */
    fun <T : FcEntity> openWithKey(
        vaultId: String,
        dek: ByteArray,
        dbName: String,
        entityClass: Class<T>,
        sortType: LocalDB.SortType = LocalDB.SortType.KEY_ORDER,
    ): LocalDB<T> {
        val cacheKey = "$vaultId/$dbName"
        openDbs[cacheKey]?.let {
            @Suppress("UNCHECKED_CAST")
            return it as LocalDB<T>
        }
        val db = SqliteDB(sortType, entityClass)
        try {
            db.initializeWithKey(dek, DesktopAppPaths.dbDir.resolve(vaultId).toString(), dbName)
        } catch (e: Throwable) {
            db.close()
            throw e
        }
        openDbs[cacheKey] = db
        log.info("Opened db={} in vault={}", dbName, vaultId)
        return db
    }

    fun close(password: CharArray, dbName: String) {
        val passwordBytes = BytesUtils.charArrayToByteArray(password, StandardCharsets.UTF_8)
        val prefix = try {
            IdNameUtils.makePasswordHashName(passwordBytes)
        } finally {
            passwordBytes.fill(0)
        }
        closeByPrefix(prefix, dbName)
    }

    fun closeByPrefix(passwordHashPrefix: String, dbName: String) {
        val cacheKey = "$passwordHashPrefix/$dbName"
        openDbs.remove(cacheKey)?.let {
            runCatching { it.close() }.onFailure { log.warn("close failed for {}: {}", cacheKey, it.message) }
        }
    }

    fun closeAll() {
        val snapshot = openDbs.toMap()
        openDbs.clear()
        snapshot.forEach { (key, db) ->
            runCatching { db.close() }.onFailure { log.warn("close failed for {}: {}", key, it.message) }
        }
    }

    fun openCount(): Int = openDbs.size
}
