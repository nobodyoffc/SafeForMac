package com.fc.safe.platform.macos

import core.crypto.CryptoDataByte
import core.crypto.CryptoDataStr
import core.crypto.EncryptType
import core.crypto.Encryptor
import core.crypto.VaultKey
import core.crypto.VaultMigration
import data.fcData.AlgorithmId
import data.fcData.FcEntity
import utils.Hex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.system.exitProcess

/**
 * End-to-end checks of the move from legacy (password-keyed) wallets to
 * data-key vaults, against a throwaway `user.home` the Gradle task sets.
 * Never point it at a real home: it creates and deletes wallets.
 *
 *  1. migrate a legacy wallet; layout, records and ciphers afterwards
 *  2. a crash after each migration step, then the next unlock resumes
 *  3. a record the password does not open: the move aborts, nothing is lost
 *  4. unlock / create / change-password rules
 *  5. a legacy wallet whose config entry was never saved is still found
 *
 * Run: ./gradlew :platform-macos:vaultMigrationSmoke
 */
fun main() {
    val home = Path.of(System.getProperty("user.home"))
    check(home.toString().contains("vault-smoke-home")) { "refusing to run outside the smoke home: $home" }
    BootstrapLogging.preInit()
    DesktopApp.initialize()

    val t = Checks()
    t.section("1. migrate a legacy wallet") { migrateLegacy(t) }
    for (step in CrashAt.entries) t.section("2. crash $step, then resume") { crashAndResume(t, step) }
    t.section("3. unreadable record aborts the move") { unreadableRecord(t) }
    t.section("4. unlock / create / change password") { passwordRules(t) }
    t.section("5. wallet without its config entry (pre-1.1 createFor bug)") { orphanedWallet(t) }

    println(if (t.failed == 0) "VAULT MIGRATION SMOKE PASSED (${t.passed} checks)" else "VAULT MIGRATION SMOKE: ${t.failed} FAILED")
    exitProcess(if (t.failed == 0) 0 else 1)
}

// ---- fixtures ----

/** Stands in for DesktopKeyInfo: a top-level embedded cipher. */
class SmokeKey : FcEntity() {
    var label: String? = null
    var prikeyCipher: String? = null
    var savedAt: Long = 0
}

/** Stands in for a record whose ciphers sit deeper, inside a list of objects. */
class SmokeNote : FcEntity() {
    var title: String? = null
    var parts: List<Map<String, String>> = emptyList()
}

private class Legacy(val password: CharArray, val name: String, val prikeys: Map<String, String>, val notes: Map<String, List<String>>)

private val dbDir: Path get() = DesktopAppPaths.dbDir

/**
 * Builds a wallet exactly as builds before 1.1 did: a legacy config entry,
 * DBs keyed by the password, and Password ciphers inside the records.
 */
private fun makeLegacy(pwd: String, keys: Int = 3, poison: String? = null): Legacy {
    val password = pwd.toCharArray()
    val name = DesktopConfigureManager.createLegacyFor(password).passwordName
    WalletSession.unlock(password, name, null)
    try {
        WalletSession.openDb(VaultUnlocker.LEGACY_VAULT_DB, RawEntity::class.java)
            .putMeta("vault.created_at", 1_700_000_000_000L)

        val prikeys = LinkedHashMap<String, String>()
        val keyDb = WalletSession.openDb("keys", SmokeKey::class.java)
        repeat(keys) { i ->
            val prikey = Hex.toHex(ByteArray(32) { (it * 7 + i + 1).toByte() })
            val k = SmokeKey().apply {
                id = "key-$i"; label = "key $i"; savedAt = 1_700_000_000_000L + i
                prikeyCipher = WalletSession.encryptToJson(Hex.fromHex(prikey))
            }
            keyDb.put(k.id, k)
            prikeys[k.id] = prikey
        }
        if (poison != null) {
            // A record encrypted under some other password: the wallet's password cannot open it.
            val foreign = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7).encryptByPassword(ByteArray(32) { 9 }, poison.toCharArray())
            val cds = CryptoDataStr.fromCryptoDataByte(foreign).also { it.data = null }
            keyDb.put("key-poison", SmokeKey().apply { id = "key-poison"; prikeyCipher = cds.toJson() })
        }
        keyDb.putSetting("sort", "label")
        keyDb.putState("lastOpened", "key-0")

        val notes = LinkedHashMap<String, List<String>>()
        val noteDb = WalletSession.openDb("notes", SmokeNote::class.java)
        for (n in 0 until 2) {
            val texts = listOf("note $n part a", "note $n part b — ünïcødé")
            noteDb.put("note-$n", SmokeNote().apply {
                id = "note-$n"; title = "Note $n"
                parts = texts.map { mapOf("label" to "p", "cipher" to WalletSession.encryptToJson(it.toByteArray())) }
            })
            notes["note-$n"] = texts
        }
        return Legacy(password, name, prikeys, notes)
    } finally {
        WalletSession.lock()
    }
}

/** Every record of [l] reads back unchanged through the open session. */
private fun checkRecords(t: Checks, l: Legacy, expectSymkey: Boolean) {
    val keyDb = WalletSession.openDb("keys", SmokeKey::class.java)
    for ((id, prikey) in l.prikeys) {
        val k = keyDb.get(id)
        t.check("$id present") { k != null }
        val cipher = k?.prikeyCipher ?: continue
        t.check("$id cipher is ${if (expectSymkey) "Symkey" else "Password"}") {
            CryptoDataByte.fromJson(cipher).type == (if (expectSymkey) EncryptType.Symkey else EncryptType.Password)
        }
        t.check("$id decrypts to its prikey") { Hex.toHex(WalletSession.decryptFromJson(cipher)) == prikey }
    }
    t.check("savedAt kept") { keyDb.get("key-1")?.savedAt == 1_700_000_000_001L }
    t.check("settings kept") { keyDb.getSetting("sort") == "label" }
    t.check("state kept") { keyDb.getState("lastOpened") == "key-0" }
    val noteDb = WalletSession.openDb("notes", SmokeNote::class.java)
    for ((id, texts) in l.notes) {
        val parts = noteDb.get(id)?.parts.orEmpty()
        t.check("$id nested ciphers decrypt") {
            parts.map { String(WalletSession.decryptFromJson(it.getValue("cipher"))) } == texts
        }
    }
}

private fun checkMigratedLayout(t: Checks, l: Legacy, vaultId: String) {
    t.check("vault id is 12 hex") { vaultId.length == VaultKey.VAULT_ID_LENGTH && vaultId.all { it in "0123456789abcdef" } }
    t.check("legacy DB directory removed") { !dbDir.resolve(l.name).exists() }
    t.check("vault DB directory has the same DBs") {
        dbDir.resolve(vaultId).listDirectoryEntries("*.sqlite").map { it.name }.toSet() ==
            setOf("${VaultUnlocker.LEGACY_VAULT_DB}.sqlite", "keys.sqlite", "notes.sqlite")
    }
    val configText = Files.readString(DesktopAppPaths.configDir.resolve("configurations.json"))
    t.check("config no longer names the password hash") { !configText.contains(l.name) }
    val e = DesktopConfigureManager.get(vaultId)
    t.check("entry: dekCipher set, migration DONE, no pending fields") {
        e != null && e.dekCipher != null && e.migrationState == VaultMigration.State.DONE.name &&
            e.legacyName == null && e.pendingVaultId == null && e.pendingDekCipher == null
    }
    t.check("no plaintext prikey in any vault file") {
        val files = Files.walk(dbDir.resolve(vaultId)).use { s -> s.filter { !it.isDirectory() }.toList() }
        val blob = files.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }.lowercase()
        l.prikeys.values.none { blob.contains(it) }
    }
}

// ---- 1 ----

private fun migrateLegacy(t: Checks) {
    val l = makeLegacy("legacy-wallet-1")
    t.check("fixture is legacy") { DesktopConfigureManager.get(l.name)?.isLegacy == true && dbDir.resolve(l.name).isDirectory() }

    val r = VaultUnlocker.unlock(l.password)
    t.check("unlock opens and migrates") { r is VaultUnlocker.UnlockResult.Opened && !r.legacy }
    val vaultId = (r as? VaultUnlocker.UnlockResult.Opened)?.vaultName ?: return
    t.check("session is in vault mode") { !WalletSession.isLegacy && WalletSession.currentVaultName() == vaultId }
    checkRecords(t, l, expectSymkey = true)
    checkMigratedLayout(t, l, vaultId)

    // A record written after the move is a Symkey cipher too.
    val db = WalletSession.openDb("keys", SmokeKey::class.java)
    db.put("key-new", SmokeKey().apply { id = "key-new"; prikeyCipher = WalletSession.encryptToJson(ByteArray(32) { 5 }) })
    t.check("new record uses the data key") { CryptoDataByte.fromJson(db.get("key-new")!!.prikeyCipher).type == EncryptType.Symkey }
    WalletSession.lock()

    val again = VaultUnlocker.unlock(l.password)
    t.check("second unlock opens the same vault") { again == VaultUnlocker.UnlockResult.Opened(vaultId, legacy = false) }
    checkRecords(t, l, expectSymkey = true)
    WalletSession.lock()
    t.check("wrong password opens nothing") { VaultUnlocker.unlock("not-the-password".toCharArray()) == VaultUnlocker.UnlockResult.WrongPassword }
}

// ---- 2 ----

enum class CrashAt { AFTER_PREPARED, MID_COPY, AFTER_COPY, AFTER_COPIED, AFTER_FLIP, AFTER_DELETE_LEGACY }

private class Crash : RuntimeException("simulated crash")

/** Delegates to the real store and dies at [at], as a power cut would. */
private class CrashingStore(private val real: DesktopVaultStore, private val at: CrashAt) : VaultMigration.Store by real {
    override fun savePrepared(vaultId: String, dekCipher: String) {
        real.savePrepared(vaultId, dekCipher); if (at == CrashAt.AFTER_PREPARED) throw Crash()
    }
    override fun copyInto(vaultId: String, reencrypt: VaultMigration.Reencrypt) {
        var n = 0
        real.copyInto(vaultId) { id, c ->
            if (at == CrashAt.MID_COPY && ++n == 2) throw Crash()
            reencrypt.apply(id, c)
        }
        if (at == CrashAt.AFTER_COPY) throw Crash()
    }
    override fun saveCopied() { real.saveCopied(); if (at == CrashAt.AFTER_COPIED) throw Crash() }
    override fun flip(vaultId: String) { real.flip(vaultId); if (at == CrashAt.AFTER_FLIP) throw Crash() }
    override fun deleteLegacy() { real.deleteLegacy(); if (at == CrashAt.AFTER_DELETE_LEGACY) throw Crash() }
}

private fun crashAndResume(t: Checks, at: CrashAt) {
    val l = makeLegacy("crash-${at.name.lowercase()}")
    val crashed = runCatching {
        VaultMigration.run(CrashingStore(DesktopVaultStore(l.name, l.password), at), l.password, ByteArray(32) { 1 })
    }.exceptionOrNull()
    t.check("crash happened") { crashed is Crash }

    // "Restart": nothing in memory survives.
    DesktopDatabaseManager.closeAll()
    DesktopConfigureManager.reload()

    val r = VaultUnlocker.unlock(l.password)
    t.check("next unlock opens a vault") { r is VaultUnlocker.UnlockResult.Opened && !r.legacy }
    val vaultId = (r as? VaultUnlocker.UnlockResult.Opened)?.vaultName ?: return
    checkRecords(t, l, expectSymkey = true)
    checkMigratedLayout(t, l, vaultId)
    t.check("exactly one vault came from this wallet") {
        DesktopConfigureManager.all().values.count { it.dekCipher != null && VaultKey.unwrap(it.dekCipher, l.password) != null } == 1
    }
    t.check("no stray vault directories") {
        val known = DesktopConfigureManager.all().keys + DesktopConfigureManager.all().values.mapNotNull { it.pendingVaultId }
        dbDir.listDirectoryEntries().filter { it.isDirectory() }.all { it.name in known }
    }
    WalletSession.lock()
}

// ---- 3 ----

private fun unreadableRecord(t: Checks) {
    val l = makeLegacy("has-a-foreign-record", poison = "some-other-password")
    val before = dbDir.resolve(l.name).listDirectoryEntries().associate { it.name to it.readBytes().size }

    val r = VaultUnlocker.unlock(l.password)
    t.check("opens, still legacy, names the record") {
        r is VaultUnlocker.UnlockResult.Opened && r.legacy && r.vaultName == l.name &&
            r.unreadableRecordId == "keys/items/key-poison"
    }
    t.check("session in legacy mode") { WalletSession.isLegacy }
    checkRecords(t, l, expectSymkey = false)
    t.check("legacy files still there") { dbDir.resolve(l.name).listDirectoryEntries("*.sqlite").size == before.count { it.key.endsWith(".sqlite") } }
    t.check("the partial copy was discarded") {
        val pending = DesktopConfigureManager.get(l.name)?.pendingVaultId
        pending == null || !dbDir.resolve(pending).exists()
    }
    t.check("new record in legacy mode is a Password cipher") {
        CryptoDataByte.fromJson(WalletSession.encryptToJson(ByteArray(32) { 3 })).type == EncryptType.Password
    }
    t.check("change password refused while legacy") {
        VaultUnlocker.changePassword(l.password, "whatever-new".toCharArray()) == VaultUnlocker.ChangeResult.LegacyWallet
    }
    WalletSession.lock()

    // Retried on the next unlock, and still safe.
    val again = VaultUnlocker.unlock(l.password)
    t.check("retry still opens legacy") { again is VaultUnlocker.UnlockResult.Opened && again.legacy }
    checkRecords(t, l, expectSymkey = false)
    WalletSession.lock()

    // Once the bad record is gone, the move goes through.
    VaultUnlocker.unlock(l.password, migrate = false)
    WalletSession.openDb("keys", SmokeKey::class.java).remove("key-poison")
    WalletSession.lock()
    val fixed = VaultUnlocker.unlock(l.password)
    t.check("after removing it, the wallet migrates") { fixed is VaultUnlocker.UnlockResult.Opened && !fixed.legacy }
    checkRecords(t, l, expectSymkey = true)
    WalletSession.lock()
}

// ---- 4 ----

private fun passwordRules(t: Checks) {
    val a = "vault-a-password".toCharArray()
    val b = "vault-b-password".toCharArray()
    val created = VaultUnlocker.create(a)
    t.check("create makes a vault") { created is VaultUnlocker.CreateResult.Created }
    val idA = (created as VaultUnlocker.CreateResult.Created).vaultId
    t.check("new vault has no legacy name anywhere") { DesktopConfigureManager.all().keys.none { it == DesktopConfigureManager.passwordNameFor(a) } }
    val db = WalletSession.openDb("keys", SmokeKey::class.java)
    db.put("k", SmokeKey().apply { id = "k"; prikeyCipher = WalletSession.encryptToJson(ByteArray(32) { 4 }) })
    WalletSession.lock()

    t.check("create with a password in use is refused") { VaultUnlocker.create(a) == VaultUnlocker.CreateResult.AlreadyExists }
    t.check("create with a legacy wallet's password is refused") {
        val l = makeLegacy("legacy-for-create-check", keys = 1)
        VaultUnlocker.create(l.password) == VaultUnlocker.CreateResult.AlreadyExists
    }
    VaultUnlocker.create(b); WalletSession.lock()

    VaultUnlocker.unlock(a)
    t.check("wrong old password refused") {
        VaultUnlocker.changePassword("nope-nope".toCharArray(), "fresh-password-1".toCharArray()) == VaultUnlocker.ChangeResult.WrongOldPassword
    }
    t.check("new password that opens another vault refused") {
        VaultUnlocker.changePassword(a, b) == VaultUnlocker.ChangeResult.NewPasswordInUse
    }
    val c = "vault-a-new-password".toCharArray()
    t.check("change password") { VaultUnlocker.changePassword(a, c) == VaultUnlocker.ChangeResult.Changed }
    t.check("session compares against the new password") { WalletSession.currentPasswordCopy()?.contentEquals(c) == true }
    WalletSession.lock()

    t.check("old password no longer opens it") { VaultUnlocker.unlock(a) == VaultUnlocker.UnlockResult.WrongPassword }
    t.check("new password opens the same vault") { VaultUnlocker.unlock(c) == VaultUnlocker.UnlockResult.Opened(idA, legacy = false) }
    t.check("its records are untouched") {
        WalletSession.decryptFromJson(WalletSession.openDb("keys", SmokeKey::class.java).get("k")!!.prikeyCipher!!).all { it == 4.toByte() }
    }
    WalletSession.lock()
}

// ---- 5 ----

private fun orphanedWallet(t: Checks) {
    val l = makeLegacy("orphaned-wallet", keys = 2)
    // What builds before 1.1 left behind: the files, but no entry.
    DesktopConfigureManager.remove(l.name)
    t.check("entry is gone, files remain") { DesktopConfigureManager.get(l.name) == null && dbDir.resolve(l.name).isDirectory() }
    t.check("create with its password is refused") { VaultUnlocker.create(l.password) == VaultUnlocker.CreateResult.AlreadyExists }
    t.check("a different password does not open it") { VaultUnlocker.unlock("orphaned-walleT".toCharArray()) == VaultUnlocker.UnlockResult.WrongPassword }
    val r = VaultUnlocker.unlock(l.password)
    t.check("its password opens and migrates it") { r is VaultUnlocker.UnlockResult.Opened && !r.legacy }
    checkRecords(t, l, expectSymkey = true)
    (r as? VaultUnlocker.UnlockResult.Opened)?.let { checkMigratedLayout(t, l, it.vaultName) }
    WalletSession.lock()
}

// ---- harness ----

private class Checks {
    var passed = 0
    var failed = 0

    fun section(name: String, body: () -> Unit) {
        println("== $name")
        try {
            body()
        } catch (e: Throwable) {
            failed++
            println("FAIL $name threw: $e")
            e.printStackTrace(System.out)
        } finally {
            WalletSession.lock()
        }
    }

    fun check(name: String, ok: () -> Boolean) {
        val result = runCatching(ok)
        if (result.getOrDefault(false)) passed++ else {
            failed++
            println("FAIL $name${result.exceptionOrNull()?.let { ": $it" } ?: ""}")
        }
    }
}
