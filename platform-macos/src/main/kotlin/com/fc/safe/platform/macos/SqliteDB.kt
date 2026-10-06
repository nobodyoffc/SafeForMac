package com.fc.safe.platform.macos

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import core.crypto.Decryptor
import core.crypto.Encryptor
import core.crypto.Kdf
import data.fcData.AlgorithmId
import data.fcData.FcEntity
import db.LocalDB
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.LinkedHashMap
import java.util.Locale
import java.util.NavigableMap
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Thrown when the password supplied to [SqliteDB] doesn't decrypt the stored validator. */
class WrongPasswordException(msg: String) : RuntimeException(msg)

/**
 * sqlite-jdbc-backed [LocalDB] with per-row AES-GCM value encryption.
 *
 * **Symkey lifecycle.** On first open, a 16-byte random salt is generated and
 * persisted plaintext in the `config` table. The symkey is derived once via
 * [Kdf.Argon2id_No1_NrC7] (password, salt) and held in memory until [close].
 * A known validator plaintext ("SafeForMac-v1") is encrypted and stored on
 * creation; subsequent opens decrypt it to verify the password — mismatch
 * raises [WrongPasswordException]. That is the legacy (pre-1.1) layout;
 * a data-key vault opens with [initializeWithKey] instead, where the
 * vault's DEK is the symkey and no KDF runs per DB.
 *
 * **Per-row encryption.** Every value BLOB is [Encryptor.encryptToBundleBySymkey]
 * (`FC_AesGcm256_No1_NrC7`), which prepends a fresh IV and appends a GCM
 * auth tag. Reads use [Decryptor.decryptBundleBySymkey]; a corrupted row
 * surfaces as a non-zero code on the returned [data.fcData.CryptoDataByte].
 *
 * **Scope.** Core CRUD, batch, meta/settings/state, clear, close are fully
 * implemented against SQLite. Exotic methods (pagination via getMap/getList,
 * named maps, ordered lists, index accessors, search) are currently stubbed
 * with [UnsupportedOperationException] — they'll be filled in on demand as
 * the Phase 2 UI port reveals actual call sites.
 */
class SqliteDB<T : FcEntity>(
    private val sortType: LocalDB.SortType,
    private val entityClass: Class<T>,
) : LocalDB<T> {

    private val log = LoggerFactory.getLogger(SqliteDB::class.java)
    private val gson: Gson = GsonBuilder().create()
    private val lock = ReentrantReadWriteLock()

    @Volatile private var conn: Connection? = null
    private var symkey: ByteArray? = null
    @Volatile private var closed = false
    private var dbFile: File? = null

    private val tempIndex = ThreadLocal<Long>()
    private val tempId = ThreadLocal<String?>()

    internal companion object {
        const val VALIDATOR_PLAINTEXT = "SafeForMac-v1"
        const val CONFIG_KEY_SALT = "salt"
        const val CONFIG_KEY_VALIDATOR = "validator"
        const val CONFIG_KEY_MODE = "mode"
        const val MODE_VAULT_KEY = "vaultKey"
        val DATA_TABLES = listOf("items", "meta", "settings", "state")
    }

    /**
     * Open or create the DB file. Must be called exactly once per instance
     * before any other method. [LocalDB.initialize] throws because it has
     * no way to supply the password.
     */
    fun initializeWithPassword(
        password: CharArray,
        fid: String?,
        sid: String?,
        dbPath: String,
        dbName: String,
    ) {
        lock.write {
            val isNew = openConnection(dbPath, dbName)

            if (isNew) {
                val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                putConfigPlain(CONFIG_KEY_SALT, salt)
                symkey = Kdf.Argon2id_No1_NrC7.deriveSymkey(password, salt)
                val validatorCipher = encryptToBundle(VALIDATOR_PLAINTEXT.toByteArray(Charsets.UTF_8))
                putConfigPlain(CONFIG_KEY_VALIDATOR, validatorCipher)
                log.info("Created new SqliteDB: {}", dbFile)
            } else {
                val salt = getConfigPlain(CONFIG_KEY_SALT)
                    ?: error("DB corrupted: no salt row")
                symkey = Kdf.Argon2id_No1_NrC7.deriveSymkey(password, salt)
                val validatorCipher = getConfigPlain(CONFIG_KEY_VALIDATOR)
                    ?: error("DB corrupted: no validator row")
                val decrypted = try {
                    decryptFromBundle(validatorCipher)
                } catch (e: Exception) {
                    wipeKey()
                    throw WrongPasswordException("Password does not decrypt $dbName")
                }
                if (String(decrypted, Charsets.UTF_8) != VALIDATOR_PLAINTEXT) {
                    wipeKey()
                    throw WrongPasswordException("Validator mismatch for $dbName")
                }
                log.info("Opened existing SqliteDB: {}", dbFile)
            }
        }
    }

    /**
     * Open or create the DB file of a data-key vault. [key] is the vault's
     * 32-byte DEK and encrypts the rows directly, so no KDF runs here: the
     * one Argon2id run happened when the DEK was unwrapped. A file made by
     * [initializeWithPassword] has a salt row and is refused, so a legacy
     * file can never be opened as if it were already migrated.
     */
    fun initializeWithKey(key: ByteArray, dbPath: String, dbName: String) {
        lock.write {
            require(key.size == 32) { "vault key must be 32 bytes" }
            val isNew = openConnection(dbPath, dbName)
            symkey = key.copyOf()
            if (isNew) {
                putConfigPlain(CONFIG_KEY_MODE, MODE_VAULT_KEY.toByteArray(Charsets.UTF_8))
                putConfigPlain(CONFIG_KEY_VALIDATOR, encryptToBundle(VALIDATOR_PLAINTEXT.toByteArray(Charsets.UTF_8)))
                log.info("Created new SqliteDB: {}", dbFile)
                return@write
            }
            if (getConfigPlain(CONFIG_KEY_SALT) != null) {
                wipeKey()
                error("$dbName is a password-keyed DB; migrate it before opening it with a vault key")
            }
            val validatorCipher = getConfigPlain(CONFIG_KEY_VALIDATOR)
            val decrypted = validatorCipher?.let { runCatching { decryptFromBundle(it) }.getOrNull() }
            if (decrypted == null || String(decrypted, Charsets.UTF_8) != VALIDATOR_PLAINTEXT) {
                wipeKey()
                throw WrongPasswordException("Vault key does not open $dbName")
            }
            log.info("Opened existing SqliteDB: {}", dbFile)
        }
    }

    /** @return true if the file did not exist and was just created. */
    private fun openConnection(dbPath: String, dbName: String): Boolean {
        check(!closed) { "SqliteDB already closed" }
        require(conn == null) { "already initialized" }

        Files.createDirectories(Paths.get(dbPath))
        val fileName = "${dbName.lowercase(Locale.ROOT)}.sqlite"
        dbFile = File(dbPath, fileName)
        val isNew = !dbFile!!.exists()

        Class.forName("org.sqlite.JDBC")
        conn = DriverManager.getConnection("jdbc:sqlite:${dbFile!!.absolutePath}")

        conn!!.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = WAL")
            st.execute("PRAGMA synchronous = NORMAL")
            st.execute("PRAGMA foreign_keys = ON")
        }

        createSchema()
        return isNew
    }

    // ---- Raw rows, for the vault migration ----

    /** One decrypted row of any table; [value] is the row's plaintext JSON. */
    class RawRow(val key: String, val value: ByteArray, val createdAt: Long, val updatedAt: Long)

    /** Every row of [table] ("items", "meta", "settings" or "state"), decrypted. */
    fun rawRows(table: String): List<RawRow> = lock.read {
        requireOpen()
        require(table in DATA_TABLES) { "unknown table $table" }
        val timed = table == "items"
        val sql = if (timed) "SELECT key, value, created_at, updated_at FROM items" else "SELECT key, value FROM $table"
        val out = ArrayList<RawRow>()
        conn!!.createStatement().executeQuery(sql).use { rs ->
            while (rs.next()) {
                out += RawRow(
                    rs.getString(1), decryptFromBundle(rs.getBytes(2)),
                    if (timed) rs.getLong(3) else 0L, if (timed) rs.getLong(4) else 0L,
                )
            }
        }
        out
    }

    /** Writes [rows] into [table] in one transaction, encrypted under this DB's key. */
    fun putRawRows(table: String, rows: List<RawRow>) = lock.write {
        requireOpen()
        require(table in DATA_TABLES) { "unknown table $table" }
        if (rows.isEmpty()) return@write
        val c = conn!!
        c.autoCommit = false
        try {
            val sql = if (table == "items")
                "INSERT OR REPLACE INTO items(key, value, created_at, updated_at) VALUES (?, ?, ?, ?)"
            else "INSERT OR REPLACE INTO $table(key, value) VALUES (?, ?)"
            c.prepareStatement(sql).use { ps ->
                for (r in rows) {
                    ps.setString(1, r.key); ps.setBytes(2, encryptToBundle(r.value))
                    if (table == "items") { ps.setLong(3, r.createdAt); ps.setLong(4, r.updatedAt) }
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        } catch (t: Throwable) {
            runCatching { c.rollback() }
            throw t
        } finally {
            c.autoCommit = true
        }
    }

    /** Folds the WAL into the main file, so a copy is on disk before the caller records it as done. */
    fun checkpoint() = lock.write {
        requireOpen()
        conn!!.createStatement().use { it.execute("PRAGMA wal_checkpoint(FULL)") }
        Unit
    }

    private fun createSchema() {
        conn!!.createStatement().use { st ->
            st.execute("CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value BLOB NOT NULL)")
            st.execute(
                """
                CREATE TABLE IF NOT EXISTS items (
                    key TEXT PRIMARY KEY,
                    value BLOB NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            st.execute("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value BLOB NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value BLOB NOT NULL)")
            st.execute("CREATE TABLE IF NOT EXISTS state (key TEXT PRIMARY KEY, value BLOB NOT NULL)")
        }
    }

    // ---- Plaintext config (salt/validator) ----

    private fun putConfigPlain(key: String, value: ByteArray) {
        conn!!.prepareStatement("INSERT OR REPLACE INTO config(key, value) VALUES (?, ?)").use { ps ->
            ps.setString(1, key); ps.setBytes(2, value); ps.executeUpdate()
        }
    }

    private fun getConfigPlain(key: String): ByteArray? {
        conn!!.prepareStatement("SELECT value FROM config WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getBytes(1) else null }
        }
    }

    // ---- Crypto helpers ----

    private fun encryptToBundle(plaintext: ByteArray): ByteArray {
        val key = symkey ?: error("DB not initialized or closed")
        return Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7).encryptToBundleBySymkey(plaintext, key)
    }

    private fun decryptFromBundle(bundle: ByteArray): ByteArray {
        val key = symkey ?: error("DB not initialized or closed")
        val r = Decryptor().decryptBundleBySymkey(bundle, key)
        check(r.code == 0) { "decrypt failed code=${r.code} msg=${r.message}" }
        return r.data
    }

    private fun wipeKey() {
        symkey?.fill(0)
        symkey = null
    }

    // ---- JSON helpers ----

    private fun serializeT(value: T): ByteArray = gson.toJson(value).toByteArray(Charsets.UTF_8)
    private fun deserializeT(bytes: ByteArray): T = gson.fromJson(String(bytes, Charsets.UTF_8), entityClass)
    private fun serializeObj(value: Any?): ByteArray = gson.toJson(value).toByteArray(Charsets.UTF_8)
    private fun deserializeObj(bytes: ByteArray): Any? = gson.fromJson(String(bytes, Charsets.UTF_8), Any::class.java)

    private inline fun requireOpen() {
        check(!closed) { "SqliteDB is closed" }
        check(conn != null) { "SqliteDB not initialized" }
    }

    // ==================== LocalDB<T> ====================

    override fun initialize(fid: String?, sid: String?, dbPath: String, dbName: String) {
        throw UnsupportedOperationException("Use initializeWithPassword(...) — SqliteDB requires a password")
    }

    override fun getSortType(): LocalDB.SortType = sortType

    // ---- Main item CRUD ----

    override fun put(key: String, value: T) = lock.write {
        requireOpen()
        val cipher = encryptToBundle(serializeT(value))
        val now = System.currentTimeMillis()
        conn!!.prepareStatement(
            """
            INSERT INTO items(key, value, created_at, updated_at) VALUES (?, ?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, key); ps.setBytes(2, cipher); ps.setLong(3, now); ps.setLong(4, now)
            ps.executeUpdate()
        }
        Unit
    }

    override fun get(key: String): T? = lock.read {
        requireOpen()
        conn!!.prepareStatement("SELECT value FROM items WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@read null
                deserializeT(decryptFromBundle(rs.getBytes(1)))
            }
        }
    }

    override fun get(keys: List<String>): List<T> {
        if (keys.isEmpty()) return emptyList()
        return lock.read {
            requireOpen()
            val placeholders = keys.joinToString(",") { "?" }
            val out = ArrayList<T>(keys.size)
            conn!!.prepareStatement("SELECT value FROM items WHERE key IN ($placeholders)").use { ps ->
                keys.forEachIndexed { i, k -> ps.setString(i + 1, k) }
                ps.executeQuery().use { rs ->
                    while (rs.next()) out.add(deserializeT(decryptFromBundle(rs.getBytes(1))))
                }
            }
            out
        }
    }

    override fun remove(key: String) = lock.write {
        requireOpen()
        conn!!.prepareStatement("DELETE FROM items WHERE key = ?").use { ps ->
            ps.setString(1, key); ps.executeUpdate()
        }
        Unit
    }

    override fun remove(list: List<T>) {
        if (list.isEmpty()) return
        removeList(list.map { it.id })
    }

    override fun removeList(ids: List<String>) {
        if (ids.isEmpty()) return
        lock.write {
            requireOpen()
            val placeholders = ids.joinToString(",") { "?" }
            conn!!.prepareStatement("DELETE FROM items WHERE key IN ($placeholders)").use { ps ->
                ids.forEachIndexed { i, k -> ps.setString(i + 1, k) }
                ps.executeUpdate()
            }
        }
    }

    override fun putAll(items: Map<String, T>) {
        if (items.isEmpty()) return
        lock.write {
            requireOpen()
            val now = System.currentTimeMillis()
            val c = conn!!
            c.autoCommit = false
            try {
                c.prepareStatement(
                    """
                    INSERT INTO items(key, value, created_at, updated_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
                    """.trimIndent()
                ).use { ps ->
                    items.forEach { (k, v) ->
                        ps.setString(1, k); ps.setBytes(2, encryptToBundle(serializeT(v)))
                        ps.setLong(3, now); ps.setLong(4, now)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                c.commit()
            } finally {
                c.autoCommit = true
            }
        }
    }

    override fun putAll(items: List<T>, idField: String) {
        // idField is ignored: FcEntity.getId() is authoritative.
        putAll(items.associateBy { it.id })
    }

    override fun getAll(): Map<String, T> = lock.read {
        requireOpen()
        val out = LinkedHashMap<String, T>()
        conn!!.createStatement().executeQuery("SELECT key, value FROM items ORDER BY key").use { rs ->
            while (rs.next()) {
                out[rs.getString(1)] = deserializeT(decryptFromBundle(rs.getBytes(2)))
            }
        }
        out
    }

    override fun getItemMap(): Map<String, T> = getAll()

    override fun getSize(): Int = lock.read {
        requireOpen()
        conn!!.createStatement().executeQuery("SELECT COUNT(*) FROM items").use { rs ->
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    override fun clear() = lock.write {
        requireOpen()
        conn!!.createStatement().execute("DELETE FROM items")
        Unit
    }

    override fun clearDB() = lock.write {
        requireOpen()
        conn!!.createStatement().execute("DELETE FROM items")
        conn!!.createStatement().execute("DELETE FROM meta")
        conn!!.createStatement().execute("DELETE FROM settings")
        conn!!.createStatement().execute("DELETE FROM state")
        Unit
    }

    override fun commit() {
        // auto-commit is on outside batch operations; nothing to do
    }

    override fun close() = lock.write {
        if (closed) return@write
        try { conn?.close() } finally {
            wipeKey()
            closed = true
            conn = null
        }
    }

    override fun isClosed(): Boolean = closed

    override fun getTempIndex(): Long = tempIndex.get() ?: -1L
    override fun getTempId(): String? = tempId.get()

    // ---- KV-table plumbing (shared by meta/settings/state) ----

    private fun putKV(table: String, key: String, value: Any?) = lock.write {
        requireOpen()
        val cipher = encryptToBundle(serializeObj(value))
        conn!!.prepareStatement(
            "INSERT OR REPLACE INTO $table(key, value) VALUES (?, ?)"
        ).use { ps ->
            ps.setString(1, key); ps.setBytes(2, cipher); ps.executeUpdate()
        }
        Unit
    }

    private fun getKV(table: String, key: String): Any? = lock.read {
        requireOpen()
        conn!!.prepareStatement("SELECT value FROM $table WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                if (!rs.next()) return@read null
                deserializeObj(decryptFromBundle(rs.getBytes(1)))
            }
        }
    }

    private fun removeKV(table: String, key: String) = lock.write {
        requireOpen()
        conn!!.prepareStatement("DELETE FROM $table WHERE key = ?").use { ps ->
            ps.setString(1, key); ps.executeUpdate()
        }
        Unit
    }

    private fun clearKV(table: String) = lock.write {
        requireOpen()
        conn!!.createStatement().execute("DELETE FROM $table")
        Unit
    }

    private fun getAllKV(table: String): Map<String, Any?> = lock.read {
        requireOpen()
        val out = LinkedHashMap<String, Any?>()
        conn!!.createStatement().executeQuery("SELECT key, value FROM $table").use { rs ->
            while (rs.next()) {
                out[rs.getString(1)] = deserializeObj(decryptFromBundle(rs.getBytes(2)))
            }
        }
        out
    }

    private fun getAllKVAsString(table: String): Map<String, String> =
        getAllKV(table).mapValues { (_, v) -> v?.toString() ?: "" }

    // ---- Metadata ----

    override fun getMetaMap(): Map<String, Any> =
        getAllKV("meta").mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    override fun putMeta(key: String, value: Any) = putKV("meta", key, value)
    override fun getMeta(key: String): Any? = getKV("meta", key)
    override fun removeMeta(key: String) = removeKV("meta", key)

    // ---- Settings ----

    override fun getSettingsMap(): Map<String, Any> =
        getAllKV("settings").mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    override fun putSetting(key: String, value: Any) = putKV("settings", key, value)
    override fun getSetting(key: String): Any? = getKV("settings", key)
    override fun removeSetting(key: String) = removeKV("settings", key)
    override fun removeAllSettings() = clearKV("settings")
    override fun getAllSettings(): Map<String, String> = getAllKVAsString("settings")

    // ---- State ----

    override fun getStateMap(): Map<String, Any> =
        getAllKV("state").mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    override fun putState(key: String, value: Any) = putKV("state", key, value)
    override fun getState(key: String): Any? = getKV("state", key)
    override fun removeState(key: String) = removeKV("state", key)
    override fun clearAllState() = clearKV("state")
    override fun getAllState(): Map<String, String> = getAllKVAsString("state")

    // ==================== Stubs (fill in as Phase 2 needs them) ====================

    override fun getIndexById(id: String): Long? = notYet()
    override fun getIdByIndex(index: Long): String? = notYet()
    override fun getByIndex(index: Long): T? = notYet()
    override fun getIndexIdMap(): NavigableMap<Long, String> = TreeMap()
    override fun getIdIndexMap(): NavigableMap<String, Long> = TreeMap()
    override fun getMap(
        size: Int?, fromId: String?, fromIndex: Long?,
        isFromInclude: Boolean, toId: String?, toIndex: Long?,
        isToInclude: Boolean, isFromEnd: Boolean,
    ): LinkedHashMap<String, T> = notYet()

    override fun getList(
        size: Int?, fromId: String?, fromIndex: Long?,
        isFromInclude: Boolean, toId: String?, toIndex: Long?,
        isToInclude: Boolean, isFromEnd: Boolean,
    ): List<T> = notYet()

    override fun searchString(part: String): List<T> = notYet()

    override fun removeFromMap(mapName: String, key: String) = notYet()
    override fun removeFromMap(mapName: String, keys: List<String>) = notYet()

    override fun getMapNames(): Set<String> = emptySet()
    override fun <V : Any?> putInMap(mapName: String, key: String, value: V) = notYet()
    override fun <V : Any?> getFromMap(mapName: String, key: String): V = notYet()
    override fun <V : Any?> getAllFromMap(mapName: String): Map<String, V> = notYet()
    override fun clearMap(mapName: String) = notYet()
    override fun <V : Any?> getFromMap(mapName: String, keyList: List<String>): List<V> = notYet()
    override fun <V : Any?> putAllInMap(mapName: String, keyList: List<String>, valueList: List<V>) = notYet()
    override fun <V : Any?> putAllInMap(mapName: String, map: Map<String, V>) = notYet()
    override fun getMapSize(mapName: String): Int = 0

    override fun registerMapType(mapName: String, typeClass: Class<*>) {
        // no-op stub
    }
    override fun getMapType(mapName: String): Class<*>? = null
    override fun <V : Any?> createMap(mapName: String, vClass: Class<V>) {
        // no-op stub
    }

    override fun <V : Any?> createOrderedList(listName: String, vClass: Class<V>) = notYet()
    override fun <V : Any?> addToList(listName: String, value: V): Long = notYet()
    override fun <V : Any?> addAllToList(listName: String, values: List<V>): Long = notYet()
    override fun <V : Any?> getFromList(listName: String, index: Long): V = notYet()
    override fun <V : Any?> getAllFromList(listName: String): List<V> = notYet()
    override fun <V : Any?> getRangeFromList(listName: String, startIndex: Long, endIndex: Long): List<V> = notYet()
    override fun <V : Any?> getRangeFromListReverse(listName: String, startIndex: Long, endIndex: Long): List<V> = notYet()
    override fun removeFromList(listName: String, index: Long): Boolean = notYet()
    override fun removeFromList(listName: String, indices: List<Long>): Int = notYet()
    override fun getListSize(listName: String): Long = 0L
    override fun clearList(listName: String) = notYet()

    private fun notYet(): Nothing =
        throw UnsupportedOperationException("Method not yet implemented in SqliteDB — fill in when Phase 2 UI calls it")
}
