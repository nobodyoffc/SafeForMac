package com.fc.safe.desktop

import com.fc.safe.desktop.backup.BackupCodec
import com.fc.safe.desktop.backup.BackupHeader
import com.fc.safe.desktop.backup.BackupKey
import com.fc.safe.desktop.backup.ExportedSecret
import com.google.gson.GsonBuilder
import core.crypto.CryptoDataByte
import core.crypto.Decryptor
import core.crypto.Encryptor
import data.fcData.AlgorithmId
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import kotlin.system.exitProcess

/**
 * Round-trip smoke for secret export — does NOT go through
 * WalletSession (that would require an unlocked session with a
 * database). Exercises the wire format directly: build the
 * Android-shaped blob, walk it with [BackupCodec], decrypt the
 * per-secret CryptoDataStr wrapper, parse the inner JSON.
 *
 * Run: `./gradlew :app-desktop:secretBackupInteropSmoke`
 */
fun main() {
    val log = LoggerFactory.getLogger("SecretBackupInteropSmoke")
    val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    // 1. Cook up a secret
    val original = ExportedSecret().apply {
        id = "abc123"
        title = "Example login"
        type = "password"
        memo = "Account I use for testing"
        content = "hunter2"
    }
    log.info("[1/4] Secret: title={} content={}", original.title, original.content)

    // 2. Encrypt the whole secret body under a password (matches
    //    what SecretExporter does for CURRENT_PASSWORD / RANDOM_PASSWORD).
    val password = "correct-horse-battery-staple".toCharArray()
    val body = gson.toJson(original).toByteArray(Charsets.UTF_8)
    val cdb = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
        .encryptByPassword(body, password.copyOf())
    val cdbJson = cdb.toNiceJson()
    log.info("[2/4] CryptoDataStr JSON: {} bytes", cdbJson.length)

    // 3. Build the envelope: BackupKey + BackupHeader + cipher
    val backupKey = BackupKey().apply {
        time = "2026-04-23 12:00:00"
        keyName = "demoKey"
        hint = "demo"
    }
    val header = BackupHeader().apply {
        time = backupKey.time
        items = 1
        tClass = "Secret"
        alg = AlgorithmId.FC_AesGcm256_No1_NrC7.getDisplayName()
        keyName = backupKey.keyName
    }
    val blob = BackupCodec.makeJsonListString(
        listOf(gson.toJson(backupKey), gson.toJson(header), cdbJson)
    )
    log.info("[3/4] Envelope: {} bytes", blob.length)

    // 4. Walk the envelope and decrypt the cipher body
    val input = ByteArrayInputStream(blob.toByteArray(Charsets.UTF_8))
    var gotKey: BackupKey? = null
    var gotHeader: BackupHeader? = null
    var gotCipherJson: String? = null
    while (true) {
        val jsonBytes = BackupCodec.readOneJsonFromInputStream(input) ?: break
        val json = String(jsonBytes, Charsets.UTF_8).trim()
        when {
            json.contains("\"hint\"") -> gotKey = gson.fromJson(json, BackupKey::class.java)
            json.contains("\"tClass\"") -> gotHeader = gson.fromJson(json, BackupHeader::class.java)
            json.contains("\"cipher\"") && json.contains("\"iv\"") -> gotCipherJson = json
        }
    }
    check(gotKey != null) { "BackupKey not recovered" }
    check(gotHeader != null) { "BackupHeader not recovered" }
    check(gotCipherJson != null) { "CryptoDataStr not recovered" }

    val decrypted = Decryptor().decryptJsonByPassword(gotCipherJson, password.copyOf())
    check(decrypted.code == 0) { "decrypt code=${decrypted.code} msg=${decrypted.message}" }
    val recovered = gson.fromJson(String(decrypted.data, Charsets.UTF_8), ExportedSecret::class.java)
    check(recovered.id == original.id) { "id mismatch" }
    check(recovered.title == original.title) { "title mismatch" }
    check(recovered.content == original.content) { "content mismatch" }
    log.info("[4/4] Round-trip OK: title={} content={}", recovered.title, recovered.content)

    // Cleanup
    password.fill(Char.MIN_VALUE)
    decrypted.data?.fill(0)

    println()
    println("SECRET BACKUP INTEROP SMOKE PASSED")
    exitProcess(0)
}
