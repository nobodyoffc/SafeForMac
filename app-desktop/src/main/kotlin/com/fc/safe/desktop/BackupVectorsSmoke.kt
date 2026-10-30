package com.fc.safe.desktop

import com.fc.safe.desktop.backup.ExportMode
import com.fc.safe.desktop.backup.KeyExporter
import com.fc.safe.desktop.backup.KeyImporter
import com.fc.safe.desktop.backup.SecretExporter
import com.fc.safe.desktop.backup.SecretImporter
import com.fc.safe.platform.macos.BootstrapLogging
import com.fc.safe.platform.macos.DesktopApp
import com.fc.safe.platform.macos.VaultUnlocker
import com.fc.safe.platform.macos.WalletSession
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import core.crypto.CryptoDataByte
import core.crypto.EncryptType
import utils.Hex
import java.io.File
import kotlin.system.exitProcess

/**
 * The FTSP31 backup vectors (`vectors/ftsp/backup.json`) through
 * SafeForMac's own [KeyImporter] and [SecretImporter], into an unlocked
 * data-key vault in a throwaway `user.home`:
 *
 * - `expect: "import"`: the importer yields exactly the vector's items, and
 *   each stored cipher is a Symkey cipher under the vault's data key.
 * - `expect: "reject"`: the importer refuses the list — it throws or yields
 *   nothing, so the screen saves nothing.
 *
 * Then our own exports of the imported items in each encrypted mode: no
 * keyName, a random password that is 128 bits and not in the export, and
 * the export reads back only with its password.
 *
 * Run: ./gradlew :app-desktop:backupVectorsSmoke
 */
fun main(args: Array<String>) {
    check(System.getProperty("user.home").contains("backup-vectors-home")) { "refusing to run outside the smoke home" }
    BootstrapLogging.preInit()
    DesktopApp.initialize()
    check(VaultUnlocker.create("backup-vectors-vault".toCharArray()) is VaultUnlocker.CreateResult.Created)

    val file = File(args.firstOrNull() ?: "vectors/ftsp", "backup.json")
    val run = VectorRun()
    val vectors = JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("vectors").map { it.asJsonObject }
    for (v in vectors) {
        run.check(v.str("id")!!) { checkBackupVector(v) }
    }
    for (mode in listOf(ExportMode.RANDOM_PASSWORD, ExportMode.CURRENT_PASSWORD)) {
        run.check("EXPORT-KEYS-$mode") { checkKeyExport(vectors, mode) }
        run.check("EXPORT-SECRETS-$mode") { checkSecretExport(vectors, mode) }
    }
    WalletSession.lock()
    println(if (run.failed == 0) "BACKUP VECTORS PASSED (${run.passed})" else "BACKUP VECTORS: ${run.failed} FAILED of ${run.passed + run.failed}")
    exitProcess(if (run.failed == 0) 0 else 1)
}

private fun checkBackupVector(v: JsonObject) {
    val text = v.str("backupText")!!
    // A random password is handed over apart from the backup, so the vector carries it.
    val password = if (v.str("mode") in setOf("appPassword", "randomPassword")) v.str("password") else null
    val isKeys = v.str("tClass") == "KeyInfo"

    if (v.str("expect") == "reject") {
        val got = runCatching {
            if (isKeys) KeyImporter.importText(text, password) else SecretImporter.importText(text, password)
        }
        expect(got.isFailure || got.getOrNull().isNullOrEmpty()) { "accepted ${got.getOrNull()?.size} item(s): ${v.str("reason")}" }
        return
    }

    val items = v.getAsJsonArray("items").map { it.asJsonObject }
    if (isKeys) {
        val keys = KeyImporter.importText(text, password)
        expect(keys.size == items.size) { "imported ${keys.size} keys, expected ${items.size}" }
        for ((k, want) in keys.zip(items)) {
            expect(k.id == want.str("fid")) { "fid ${k.id} != ${want.str("fid")}" }
            expect(k.label == want.str("label")) { "label ${k.label} != ${want.str("label")}" }
            expect(k.pubkey.equals(want.str("pubkeyHex"), ignoreCase = true)) { "pubkey of ${k.id}" }
            val cipher = k.prikeyCipher ?: throw AssertionError("${k.id} has no prikeyCipher")
            expect(CryptoDataByte.fromJson(cipher).type == EncryptType.Symkey) { "${k.id} not stored under the vault key" }
            val prikey = WalletSession.decryptFromJson(cipher)
            expect(Hex.toHex(prikey).equals(want.str("prikeyHex"), ignoreCase = true)) { "prikey of ${k.id}" }
        }
    } else {
        val secrets = SecretImporter.importText(text, password)
        expect(secrets.size == items.size) { "imported ${secrets.size} secrets, expected ${items.size}" }
        for ((s, want) in secrets.zip(items)) {
            expect(s.title == want.str("title")) { "title ${s.title} != ${want.str("title")}" }
            expect(s.type == want.str("type")) { "type ${s.type} != ${want.str("type")}" }
            expect(s.memo == want.str("memo")) { "memo ${s.memo} != ${want.str("memo")}" }
            val cipher = s.contentCipher ?: throw AssertionError("${s.title} has no contentCipher")
            expect(CryptoDataByte.fromJson(cipher).type == EncryptType.Symkey) { "${s.title} not stored under the vault key" }
            expect(String(WalletSession.decryptFromJson(cipher)) == want.str("content")) { "content of ${s.title}" }
        }
    }
}

private val VAULT_PASSWORD = "backup-vectors-vault".toCharArray()

private fun plainVector(vectors: List<JsonObject>, tClass: String) =
    vectors.first { it.str("mode") == "plain" && it.str("tClass") == tClass && it.str("expect") == "import" }

/** What an export must look like and how it reads back (FTSP31). */
private fun checkExportText(text: String, randomPassword: String?, mode: ExportMode) {
    expect(!text.contains("keyName")) { "export carries a keyName" }
    if (mode == ExportMode.RANDOM_PASSWORD) {
        val pwd = randomPassword ?: throw AssertionError("no random password returned")
        expect(pwd.length == 26) { "random password has ${pwd.length} characters, expected 26 (128 bits)" }
        expect(!text.contains(pwd)) { "export carries its random password" }
    } else {
        expect(randomPassword == null) { "random password returned for $mode" }
    }
}

private fun checkKeyExport(vectors: List<JsonObject>, mode: ExportMode) {
    val keys = KeyImporter.importText(plainVector(vectors, "KeyInfo").str("backupText")!!, null)
    val result = KeyExporter.export(keys, mode, if (mode == ExportMode.CURRENT_PASSWORD) VAULT_PASSWORD.copyOf() else null)
    checkExportText(result.text, result.randomPassword, mode)
    val password = result.randomPassword ?: String(VAULT_PASSWORD)
    expect(runCatching { KeyImporter.importText(result.text, null) }.exceptionOrNull() is KeyImporter.PasswordRequired) {
        "export reads back without a password"
    }
    val back = KeyImporter.importText(result.text, password)
    expect(back.map { it.id } == keys.map { it.id }) { "read back ${back.map { it.id }}, expected ${keys.map { it.id }}" }
    for ((b, k) in back.zip(keys)) {
        val got = WalletSession.decryptFromJson(b.prikeyCipher!!)
        val want = WalletSession.decryptFromJson(k.prikeyCipher!!)
        expect(got.contentEquals(want)) { "prikey of ${k.id} changed in the round trip" }
    }
}

private fun checkSecretExport(vectors: List<JsonObject>, mode: ExportMode) {
    val secrets = SecretImporter.importText(plainVector(vectors, "Secret").str("backupText")!!, null)
    val result = SecretExporter.export(secrets, mode, if (mode == ExportMode.CURRENT_PASSWORD) VAULT_PASSWORD.copyOf() else null)
    checkExportText(result.text, result.randomPassword, mode)
    val password = result.randomPassword ?: String(VAULT_PASSWORD)
    expect(runCatching { SecretImporter.importText(result.text, null) }.exceptionOrNull() is SecretImporter.PasswordRequired) {
        "export reads back without a password"
    }
    val back = SecretImporter.importText(result.text, password)
    expect(back.map { it.title } == secrets.map { it.title }) { "read back ${back.map { it.title }}, expected ${secrets.map { it.title }}" }
    for ((b, s) in back.zip(secrets)) {
        val got = String(WalletSession.decryptFromJson(b.contentCipher!!))
        val want = String(WalletSession.decryptFromJson(s.contentCipher!!))
        expect(got == want) { "content of ${s.title} changed in the round trip" }
    }
}
