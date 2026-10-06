package com.fc.safe.desktop

import com.fc.safe.desktop.backup.KeyImporter
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
 * Run: ./gradlew :app-desktop:backupVectorsSmoke
 */
fun main(args: Array<String>) {
    check(System.getProperty("user.home").contains("backup-vectors-home")) { "refusing to run outside the smoke home" }
    BootstrapLogging.preInit()
    DesktopApp.initialize()
    check(VaultUnlocker.create("backup-vectors-vault".toCharArray()) is VaultUnlocker.CreateResult.Created)

    val file = File(args.firstOrNull() ?: "vectors/ftsp", "backup.json")
    val run = VectorRun()
    for (e in JsonParser.parseString(file.readText()).asJsonObject.getAsJsonArray("vectors")) {
        val v = e.asJsonObject
        run.check(v.str("id")!!) { checkBackupVector(v) }
    }
    WalletSession.lock()
    println(if (run.failed == 0) "BACKUP VECTORS PASSED (${run.passed})" else "BACKUP VECTORS: ${run.failed} FAILED of ${run.passed + run.failed}")
    exitProcess(if (run.failed == 0) 0 else 1)
}

private fun checkBackupVector(v: JsonObject) {
    val text = v.str("backupText")!!
    val password = if (v.str("mode") == "appPassword") v.str("password") else null
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
