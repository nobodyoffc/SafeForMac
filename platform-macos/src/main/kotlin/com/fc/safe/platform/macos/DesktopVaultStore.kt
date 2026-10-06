package com.fc.safe.platform.macos

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import core.crypto.CryptoDataByte
import core.crypto.Decryptor
import core.crypto.EncryptType
import core.crypto.Encryptor
import core.crypto.VaultKey
import core.crypto.VaultMigration
import db.LocalDB
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The desktop's storage for [VaultMigration]: moves one legacy wallet —
 * the `.sqlite` files in `db/{legacyName}`, each keyed by Argon2id(password, its salt) —
 * to the same files in `db/{vaultId}`, keyed by a random data key.
 *
 * Every row of every table is decrypted with the password and written
 * under the data key. Inside a row, any string that is a cipher JSON
 * (prikeyCipher, a secret's contentCipher, anything added later) is a
 * Password cipher under the same password; it is decrypted and handed to
 * [VaultMigration] as a data-key cipher, so every record leaves as a Symkey
 * cipher that [forEachCipherIn] then checks. A record that will not decrypt
 * — or a DB the password does not open — aborts the move; [VaultMigration]
 * then discards the copy and the legacy wallet stays exactly as it was.
 *
 * Progress lives in the wallet's [DesktopConfigure] entry, written durably
 * after each step, so a crash anywhere resumes on the next unlock.
 */
internal class DesktopVaultStore(
    private val legacyName: String,
    private val password: CharArray,
) : VaultMigration.Store {

    private val log = LoggerFactory.getLogger(DesktopVaultStore::class.java)
    private val gson = Gson()

    init {
        require(VaultKey.isLegacyName(legacyName)) { "$legacyName is not a legacy wallet name" }
    }

    /** The legacy entry, or after the flip the vault entry that came from it. */
    private fun entry(): DesktopConfigure {
        val configs = DesktopConfigureManager.all()
        return configs[legacyName]
            ?: configs.values.firstOrNull { it.legacyName == legacyName }
            ?: error("No configuration for wallet $legacyName")
    }

    override fun state(): VaultMigration.State =
        entry().migrationState?.let(VaultMigration.State::valueOf) ?: VaultMigration.State.LEGACY

    override fun pendingVaultId(): String? = entry().let { if (it.isLegacy) it.pendingVaultId else it.passwordName }

    override fun pendingDekCipher(): String? = entry().let { if (it.isLegacy) it.pendingDekCipher else it.dekCipher }

    override fun savePrepared(vaultId: String, dekCipher: String) {
        DesktopConfigureManager.put(
            entry().copy(
                migrationState = VaultMigration.State.PREPARED.name,
                pendingVaultId = vaultId,
                pendingDekCipher = dekCipher,
            )
        )
    }

    override fun copyInto(vaultId: String, reencrypt: VaultMigration.Reencrypt) {
        discard(vaultId)
        val dek = unwrapPendingDek()
        try {
            for (dbName in dbNames(legacyDir())) {
                val src = SqliteDB(LocalDB.SortType.KEY_ORDER, RawEntity::class.java)
                val dst = SqliteDB(LocalDB.SortType.KEY_ORDER, RawEntity::class.java)
                try {
                    try {
                        src.initializeWithPassword(password.copyOf(), null, null, legacyDir().toString(), dbName)
                    } catch (_: WrongPasswordException) {
                        // Another password that shares this 6-hex name made this file.
                        throw VaultMigration.UnreadableRecordException(dbName)
                    }
                    dst.initializeWithKey(dek, vaultDir(vaultId).toString(), dbName)
                    for (table in SqliteDB.DATA_TABLES) {
                        val rows = src.rawRows(table).map { row ->
                            val recordId = "$dbName/$table/${row.key}"
                            val value = convertCiphers(row.value, recordId, dek, reencrypt)
                            SqliteDB.RawRow(row.key, value, row.createdAt, row.updatedAt)
                        }
                        dst.putRawRows(table, rows)
                    }
                    dst.checkpoint()
                } finally {
                    src.close()
                    dst.close()
                }
            }
        } finally {
            dek.fill(0)
        }
        log.info("Copied wallet {} into vault {}", legacyName, vaultId)
    }

    override fun forEachCipherIn(vaultId: String, check: VaultMigration.Check) {
        val dek = unwrapPendingDek()
        try {
            for (dbName in dbNames(vaultDir(vaultId))) {
                val db = SqliteDB(LocalDB.SortType.KEY_ORDER, RawEntity::class.java)
                try {
                    db.initializeWithKey(dek, vaultDir(vaultId).toString(), dbName)
                    for (table in SqliteDB.DATA_TABLES) {
                        for (row in db.rawRows(table)) {
                            val recordId = "$dbName/$table/${row.key}"
                            forEachCipher(parse(row.value) ?: continue) { check.accept(recordId, it) }
                        }
                    }
                } finally {
                    db.close()
                }
            }
        } finally {
            dek.fill(0)
        }
    }

    override fun discard(vaultId: String) {
        require(!VaultKey.isLegacyName(vaultId)) { "refusing to discard legacy name $vaultId" }
        deleteTree(vaultDir(vaultId))
    }

    override fun saveCopied() {
        DesktopConfigureManager.put(entry().copy(migrationState = VaultMigration.State.COPIED.name))
    }

    override fun flip(vaultId: String) {
        val legacy = entry()
        check(legacy.isLegacy) { "wallet $legacyName already flipped" }
        DesktopConfigureManager.replace(
            legacyName,
            DesktopConfigure(
                passwordName = vaultId,
                createdAt = legacy.createdAt,
                label = legacy.label,
                dekCipher = legacy.pendingDekCipher ?: error("no wrapped data key to flip to"),
                legacyName = legacyName,
                migrationState = VaultMigration.State.FLIPPED.name,
            ),
        )
    }

    override fun deleteLegacy() {
        deleteTree(legacyDir())
    }

    override fun saveDone() {
        DesktopConfigureManager.put(
            entry().copy(
                legacyName = null,
                migrationState = VaultMigration.State.DONE.name,
                pendingVaultId = null,
                pendingDekCipher = null,
            )
        )
    }

    // ---- helpers ----

    private fun legacyDir(): Path = DesktopAppPaths.dbDir.resolve(legacyName)
    private fun vaultDir(vaultId: String): Path = DesktopAppPaths.dbDir.resolve(vaultId)

    private fun unwrapPendingDek(): ByteArray =
        VaultKey.unwrap(pendingDekCipher(), password)
            ?: error("The pending data key of $legacyName does not open with this password")

    private fun dbNames(dir: Path): List<String> =
        if (!dir.isDirectory()) emptyList()
        else dir.listDirectoryEntries("*.sqlite").map { it.name.removeSuffix(".sqlite") }.sorted()

    /**
     * Returns [value] with every embedded cipher moved to the data key. A row
     * without one is returned byte for byte, so nothing else is re-serialised.
     */
    private fun convertCiphers(
        value: ByteArray,
        recordId: String,
        dek: ByteArray,
        reencrypt: VaultMigration.Reencrypt,
    ): ByteArray {
        val tree = parse(value) ?: return value
        var changed = false
        val out = mapCiphers(tree) { cipherJson ->
            changed = true
            reencrypt.apply(recordId, toVaultKeyCipher(cipherJson, recordId, dek))
        }
        return if (changed) gson.toJson(out).toByteArray(Charsets.UTF_8) else value
    }

    /** A Password cipher under the wallet password becomes a Symkey cipher under [dek]. */
    private fun toVaultKeyCipher(cipherJson: String, recordId: String, dek: ByteArray): String {
        if (cipherType(cipherJson) != EncryptType.Password) return cipherJson
        val pwd = password.copyOf()
        try {
            val r = Decryptor().decryptJsonByPassword(cipherJson, pwd)
            val plain = r.data
            if (r.code != 0 || plain == null) throw VaultMigration.UnreadableRecordException(recordId)
            try {
                return Encryptor.encryptBySymkeyToJson(plain, dek)
                    ?: throw VaultMigration.UnreadableRecordException(recordId)
            } finally {
                plain.fill(0)
            }
        } finally {
            pwd.fill(Char.MIN_VALUE)
        }
    }

    private fun parse(value: ByteArray): JsonElement? =
        runCatching { JsonParser.parseString(String(value, Charsets.UTF_8)) }.getOrNull()

    private fun forEachCipher(e: JsonElement, action: (String) -> Unit) {
        mapCiphers(e) { action(it); it }
    }

    private fun mapCiphers(e: JsonElement, f: (String) -> String): JsonElement = when {
        e.isJsonObject -> JsonObject().also { o -> e.asJsonObject.entrySet().forEach { (k, v) -> o.add(k, mapCiphers(v, f)) } }
        e.isJsonArray -> JsonArray().also { a -> e.asJsonArray.forEach { a.add(mapCiphers(it, f)) } }
        e.isJsonPrimitive && e.asJsonPrimitive.isString && cipherType(e.asString) != null -> JsonPrimitive(f(e.asString))
        else -> e
    }

    /** The cipher's type if [s] is an FVEP8 cipher JSON (Symkey or Password), else null. */
    private fun cipherType(s: String): EncryptType? {
        val t = s.trim()
        if (!t.startsWith("{") || !t.contains("\"cipher\"")) return null
        val c = runCatching { CryptoDataByte.fromJson(t) }.getOrNull() ?: return null
        if (c.cipher == null) return null
        return c.type?.takeIf { it == EncryptType.Password || it == EncryptType.Symkey }
    }

    private fun deleteTree(dir: Path) {
        val root = DesktopAppPaths.dbDir.toRealPath()
        if (!Files.exists(dir)) return
        check(dir.toRealPath().parent == root) { "refusing to delete $dir: not a vault directory" }
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
