package com.fc.safe.desktop

import com.fc.safe.platform.macos.BootstrapLogging
import com.fc.safe.platform.macos.DesktopApp
import com.fc.safe.platform.macos.DesktopAppPaths
import com.fc.safe.platform.macos.DesktopDatabaseManager
import data.fcData.FcEntity
import db.LocalDB
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Phase 1 end-to-end: bootstrap logging, open a password-scoped DB context
 * via DesktopDatabaseManager (backed by FC-JDK's EasyDB), write a concrete
 * FcEntity subclass, read it back, close, reopen with the same context, and
 * verify the entity survived a round trip through disk.
 */
fun main() {
    BootstrapLogging.preInit()
    val log = LoggerFactory.getLogger("Phase1Smoke")

    DesktopApp.initialize()

    val passwordHashPrefix = "smoke-${System.currentTimeMillis()}"
    val dbName = "notes"
    val key = "note-001"

    log.info("[1/5] Opening context prefix={} db={}", passwordHashPrefix, dbName)
    val db1: LocalDB<TestNote> = DesktopDatabaseManager.open(
        passwordHashPrefix = passwordHashPrefix,
        dbName = dbName,
        entityClass = TestNote::class.java,
    )

    val original = TestNote().apply {
        setId(key)
        body = "hello from phase 1 smoke"
        count = 42
    }

    log.info("[2/5] Writing entity id={}", original.id)
    db1.put(key, original)
    db1.commit()
    DesktopDatabaseManager.close(passwordHashPrefix, dbName)
    log.info("[3/5] Closed after write; file should exist at {}", DesktopAppPaths.dbDir.resolve(passwordHashPrefix))

    // Reopen with same context → should load from disk
    val db2: LocalDB<TestNote> = DesktopDatabaseManager.open(
        passwordHashPrefix = passwordHashPrefix,
        dbName = dbName,
        entityClass = TestNote::class.java,
    )
    log.info("[4/5] Reopened; size={}", db2.size)

    val loaded = db2.get(key)
    check(loaded != null) { "entity vanished across reopen" }
    check(loaded.id == original.id) { "id mismatch: got=${loaded.id}" }
    check(loaded.body == original.body) { "body mismatch: got=${loaded.body}" }
    check(loaded.count == original.count) { "count mismatch: got=${loaded.count}" }
    log.info("[5/5] Round-trip verified: id={} body={} count={}", loaded.id, loaded.body, loaded.count)

    // Redaction sanity check — deliberately log a line with fake secret fields
    // and inspect the output (console + file) to confirm they're scrubbed.
    log.warn("[redaction check] prikey=deadbeef0123456789abcdef password: hunter2 seed=correcthorsebatterystaple symkey=aaaaaaaa")

    DesktopApp.shutdown()
    println()
    println("PHASE 1 SMOKE TEST PASSED")
    exitProcess(0)
}

/**
 * Concrete FcEntity subclass for the smoke test. Not a real domain type —
 * just enough fields to verify the round-trip.
 */
class TestNote : FcEntity() {
    var body: String? = null
    var count: Int = 0
}
