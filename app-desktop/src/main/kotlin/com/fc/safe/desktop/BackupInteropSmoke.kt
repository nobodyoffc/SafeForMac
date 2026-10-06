package com.fc.safe.desktop

import com.fc.safe.desktop.backup.BackupCodec
import com.fc.safe.desktop.backup.BackupHeader
import com.fc.safe.desktop.backup.BackupKey
import com.fc.safe.desktop.backup.ExportedKeyInfo
import com.google.gson.GsonBuilder
import core.crypto.Decryptor
import core.crypto.Encryptor
import core.crypto.KeyTools
import data.fcData.AlgorithmId
import org.slf4j.LoggerFactory
import utils.Hex
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * Phase 2 backup interop smoke: builds an Android-shaped export blob
 * using raw FC-JDK crypto (no WalletSession / no DB layer) and walks
 * it back through [BackupCodec] + Gson. Verifies base64/bundle
 * round-trip, Encryptor/Decryptor password symmetry, and the JSON
 * splitter. Runs in a few seconds.
 *
 * Intentionally does NOT go through our [com.fc.safe.desktop.backup.KeyImporter] /
 * [com.fc.safe.desktop.backup.KeyExporter] so we isolate the wire
 * format from the WalletSession + DB plumbing.
 *
 * Run: ./gradlew :app-desktop:backupInteropSmoke
 */
fun main() {
    val log = LoggerFactory.getLogger("BackupInteropSmoke")
    val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    // 1. Cook up a key
    val prikey = ByteArray(32).also(SecureRandom()::nextBytes)
    val fid = KeyTools.prikeyToFid(prikey)
    val pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey))
    log.info("[1/5] Key: FID={} pubkey={}", fid, pubkey.take(16) + "…")

    // 2. Encrypt under a password and base64 the bundle — mirrors
    //    Android BackupKeysActivity.addKeyInfoJson. AES-GCM (AEAD)
    //    replaces the legacy CBC + sum4 path.
    val password = "correct-horse-battery-staple".toCharArray()
    val cdb = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
        .encryptByPassword(prikey.copyOf(), password.copyOf())
    val base64 = cdb.toBase64() ?: error("base64 encoding failed")
    log.info("[2/5] Password-encrypted cipher bundle: {} bytes of base64", base64.length)

    // 3. Build the blob: BackupKey + BackupHeader + one ExportedKeyInfo.
    val header = BackupHeader().apply {
        time = "2026-04-22 12:00:00"
        items = 1
        tClass = "KeyInfo"
        alg = AlgorithmId.FC_AesGcm256_No1_NrC7.getDisplayName()
        keyName = "demoKeyName"
    }
    val backupKey = BackupKey().apply {
        time = header.time
        keyName = header.keyName
        hint = "demo"
    }
    val exportedKey = ExportedKeyInfo().apply {
        id = fid
        label = "smoke-test-key"
        saveTime = header.time
        prikeyCipher = base64
    }
    val blob = BackupCodec.makeJsonListString(
        listOf(
            gson.toJson(backupKey),
            gson.toJson(header),
            gson.toJson(exportedKey),
        )
    )
    log.info("[3/5] Backup blob: {} bytes", blob.length)

    // 4. Walk it back: expect three JSON objects.
    val input = ByteArrayInputStream(blob.toByteArray(Charsets.UTF_8))
    val parsed = generateSequence { BackupCodec.readOneJsonFromInputStream(input) }.toList()
    check(parsed.size == 3) { "expected 3 JSONs, got ${parsed.size}" }
    log.info("[4/5] JSON splitter recovered {} objects", parsed.size)

    // 5. Decrypt the key JSON's prikeyCipher and check FID derives back.
    //
    // Sanity probe: first show the direct path works — encrypt-then-decrypt
    // on the ORIGINAL `cdb` via toJson+decryptJsonByPassword (no bundle
    // round-trip). If that fails, the test has a bug. If that passes but
    // the base64/bundle path fails, we know toBundle is lossy.
    val directJson = cdb.toJson() ?: error("toJson(original cdb) returned null")
    val directDecrypt = Decryptor().decryptJsonByPassword(directJson, password.copyOf())
    check(directDecrypt.code == 0) {
        "sanity decrypt (no bundle) failed: code=${directDecrypt.code} msg=${directDecrypt.message}"
    }
    log.info("[5a/5] toJson-only decrypt OK")

    val recoveredEntry = gson.fromJson(String(parsed[2]), ExportedKeyInfo::class.java)
    check(recoveredEntry.id == fid) { "id mismatch after parse" }
    // The bundle records no KDF (type 3); FC-JDK tries Argon2id and then Sha256Iv.
    val decrypted = Decryptor().decryptBundleByPassword(
        java.util.Base64.getDecoder().decode(recoveredEntry.prikeyCipher), password.copyOf()
    )
    check(decrypted.code == 0) { "decrypt returned code=${decrypted.code} msg=${decrypted.message}" }
    check(decrypted.kdf == core.crypto.Kdf.Argon2id_No1_NrC7) { "expected Argon2id, got ${decrypted.kdf}" }
    val recoveredPrikey = decrypted.data ?: error("decrypt produced no bytes")
    val recoveredFid = KeyTools.prikeyToFid(recoveredPrikey)
    check(recoveredFid == fid) { "FID mismatch: expected $fid got $recoveredFid" }
    log.info("[5/5] Decrypted prikey derives the same FID — round-trip OK")

    // hygiene
    prikey.fill(0)
    password.fill(Char.MIN_VALUE)
    recoveredPrikey.fill(0)

    println()
    println("BACKUP INTEROP SMOKE PASSED")
    exitProcess(0)
}
