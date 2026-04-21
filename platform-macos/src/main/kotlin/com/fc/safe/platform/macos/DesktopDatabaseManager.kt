package com.fc.safe.platform.macos

import data.fcData.FcEntity
import db.EasyDB
import db.LocalDB
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-password-context LocalDB lifecycle holder. One EasyDB instance per
 * (passwordHashPrefix, dbName) pair, backed by a file under:
 *
 *     ~/Library/Application Support/com.fc.safe/db/{passwordHashPrefix}/{dbName}.db
 *
 * The password-hash-prefix folder gives each password/wallet its own
 * directory. Value-layer encryption is a follow-up (Phase 1.5); at this point
 * EasyDB writes plaintext JSON to disk.
 */
object DesktopDatabaseManager {

    private val log = LoggerFactory.getLogger(DesktopDatabaseManager::class.java)
    private val openDbs = ConcurrentHashMap<String, LocalDB<*>>()

    fun <T : FcEntity> open(
        passwordHashPrefix: String,
        dbName: String,
        entityClass: Class<T>,
        sortType: LocalDB.SortType = LocalDB.SortType.KEY_ORDER,
    ): LocalDB<T> {
        val cacheKey = "$passwordHashPrefix/$dbName"
        @Suppress("UNCHECKED_CAST")
        return openDbs.computeIfAbsent(cacheKey) {
            val dbPath = DesktopAppPaths.dbDir.resolve(passwordHashPrefix)
            Files.createDirectories(dbPath)
            val db = EasyDB(sortType, entityClass)
            db.initialize(null, null, dbPath.toString(), dbName)
            log.info("Opened db={} under context={}", dbName, passwordHashPrefix)
            db
        } as LocalDB<T>
    }

    fun close(passwordHashPrefix: String, dbName: String) {
        val cacheKey = "$passwordHashPrefix/$dbName"
        openDbs.remove(cacheKey)?.let {
            try { it.close() } catch (e: Exception) { log.warn("close failed for {}: {}", cacheKey, e.message) }
        }
    }

    fun closeAll() {
        val snapshot = openDbs.toMap()
        openDbs.clear()
        snapshot.forEach { (key, db) ->
            try { db.close() } catch (e: Exception) { log.warn("close failed for {}: {}", key, e.message) }
        }
    }

    fun openCount(): Int = openDbs.size
}
