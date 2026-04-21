package com.fc.safe.desktop

import com.fc.safe.platform.macos.BootstrapLogging
import com.fc.safe.platform.macos.DesktopApp
import com.fc.safe.platform.macos.DesktopAppPaths
import com.fc.safe.platform.macos.DesktopDatabaseManager
import com.fc.safe.platform.macos.SqliteDB
import com.fc.safe.platform.macos.WrongPasswordException
import data.fcData.FcEntity
import db.LocalDB
import org.slf4j.LoggerFactory
import java.nio.file.Files
import kotlin.system.exitProcess

/**
 * Phase 1 end-to-end: bootstrap logging, open a password-encrypted SQLite DB
 * via [DesktopDatabaseManager], write/read a concrete [FcEntity] subclass,
 * close, reopen with the same password (must succeed), reopen with the
 * wrong password (must raise [WrongPasswordException]), and spot-check the
 * file on disk isn't plaintext.
 */
fun main() {
    BootstrapLogging.preInit()
    val log = LoggerFactory.getLogger("Phase1Smoke")

    DesktopApp.initialize()

    val password = "correct-horse-battery-staple".toCharArray()
    val wrongPassword = "correct-horse-battery-stapLE".toCharArray()
    val dbName = "notes-${System.currentTimeMillis()}"

    // 1. Create + write
    log.info("[1/6] Creating DB with password; writing entity")
    val db1: LocalDB<TestNote> = DesktopDatabaseManager.open(password.copyOf(), dbName, TestNote::class.java)
    val original = TestNote().apply {
        setId("note-001"); body = "hello from phase 1 smoke"; count = 42
    }
    db1.put(original.id, original)

    // Meta/settings/state round-trip
    db1.putMeta("schema_version", 1)
    db1.putSetting("ui.theme", "dark")
    db1.putState("last_unlock_ms", System.currentTimeMillis())

    db1.commit()
    DesktopDatabaseManager.close(password.copyOf(), dbName)

    // 2. Disk-level check: confirm the file doesn't expose our payload in plaintext
    log.info("[2/6] Checking on-disk bytes for plaintext leak")
    val sqliteFile = DesktopAppPaths.dbDir.resolve(run {
        // recompute prefix since we don't expose it from the manager
        val pwd = password.copyOf()
        val pb = utils.BytesUtils.charArrayToByteArray(pwd, java.nio.charset.StandardCharsets.UTF_8)
        val prefix = utils.IdNameUtils.makePasswordHashName(pb)
        pb.fill(0); pwd.fill(0.toChar())
        prefix
    }).resolve("$dbName.sqlite")
    val raw = Files.readAllBytes(sqliteFile)
    val rawStr = String(raw, Charsets.ISO_8859_1)  // byte-for-byte
    check(!rawStr.contains("hello from phase 1 smoke")) {
        "PLAINTEXT LEAK: entity body found unencrypted in $sqliteFile"
    }
    check(!rawStr.contains("dark")) {
        "PLAINTEXT LEAK: setting value 'dark' found unencrypted in $sqliteFile"
    }
    // Keys (note-001, ui.theme, schema_version) are stored plaintext by design
    // so SQLite can index them for lookup. Values are per-row AES-GCM encrypted.
    log.info("[2/6] On-disk plaintext leak check passed ({} bytes)", raw.size)

    // 3. Reopen with correct password
    log.info("[3/6] Reopening with correct password")
    val db2: LocalDB<TestNote> = DesktopDatabaseManager.open(password.copyOf(), dbName, TestNote::class.java)
    val loaded = db2.get("note-001") ?: error("entity vanished across reopen")
    check(loaded.body == original.body && loaded.count == original.count) {
        "round-trip mismatch: got body=${loaded.body} count=${loaded.count}"
    }
    check(db2.getMeta("schema_version").toString().toDouble().toInt() == 1) {
        "meta round-trip failed: got ${db2.getMeta("schema_version")}"
    }
    check(db2.getSetting("ui.theme") == "dark") { "settings round-trip failed" }
    check(db2.getState("last_unlock_ms") != null) { "state round-trip failed" }
    log.info("[3/6] Round-trip verified across reopen (id={} body={} count={})", loaded.id, loaded.body, loaded.count)

    DesktopDatabaseManager.close(password.copyOf(), dbName)

    // 4. Attacker path: given the actual wallet directory, opening the same
    // DB file with a wrong password MUST raise WrongPasswordException. We
    // bypass DesktopDatabaseManager here — it routes different passwords to
    // different directories by design (multi-wallet support) — and hit
    // SqliteDB directly with the known-to-exist path.
    log.info("[4/6] Pointing SqliteDB at existing wallet dir with WRONG password; expecting WrongPasswordException")
    val existingDir = sqliteFile.parent.toString()
    val attackerDb = SqliteDB(LocalDB.SortType.KEY_ORDER, TestNote::class.java)
    val wrongThrew = try {
        attackerDb.initializeWithPassword(wrongPassword.copyOf(), null, null, existingDir, dbName)
        attackerDb.close()
        false
    } catch (e: WrongPasswordException) {
        log.info("[4/6] Correctly rejected wrong password: {}", e.message)
        true
    }
    check(wrongThrew) { "SECURITY: wrong password did not raise WrongPasswordException" }

    // 5. Re-open correctly after a wrong attempt (must still work)
    log.info("[5/6] Re-opening with correct password after failed attempt")
    val db3: LocalDB<TestNote> = DesktopDatabaseManager.open(password.copyOf(), dbName, TestNote::class.java)
    check(db3.get("note-001") != null) { "entity lost after wrong-password attempt" }
    log.info("[5/6] Recovery after wrong-password attempt works")

    // 6. Redaction sanity
    log.warn("[6/6] [redaction check] prikey=deadbeef0123456789 password: hunter2 seed=foo symkey=bar")

    DesktopApp.shutdown()
    println()
    println("PHASE 1 SMOKE TEST PASSED")
    exitProcess(0)
}

class TestNote : FcEntity() {
    var body: String? = null
    var count: Int = 0
}
