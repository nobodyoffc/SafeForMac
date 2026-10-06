package com.fc.safe.desktop

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import core.crypto.Algorithm.Bitcore
import core.crypto.CryptoDataByte
import core.crypto.Decryptor
import core.crypto.EncryptType
import core.crypto.Hash
import core.crypto.Kdf
import core.crypto.VaultKey
import data.fcData.AlgorithmId
import utils.Hex
import java.io.File
import java.util.Base64
import kotlin.system.exitProcess

/**
 * Runs the FTSP cross-implementation vectors (`vectors/ftsp`, from Freeverse
 * Protocols/FTSP/vectors) against the FC-JDK on SafeForMac's classpath, the
 * same checks FC-JDK's CryptoVectorsTest makes. `vectors/ftsp-android` holds
 * Android Safe's copies of the files that differ: Android 2.4 writes type-4
 * Password bundles, so its bundle.json marks those canonical instead.
 *
 * The FTSP31 backup vectors go through SafeForMac's own importers in
 * [BackupVectorsSmoke], since they need an unlocked vault.
 *
 * Run: ./gradlew :app-desktop:ftspVectorsSmoke
 */
fun main(args: Array<String>) {
    val dirs = args.map(::File).ifEmpty { listOf(File("vectors/ftsp")) }
    val run = VectorRun()
    for (dir in dirs) {
        println("== ${dir.path}")
        for (file in listOf("kdf", "phrase", "cipher-json", "bundle", "algorithms", "vault")) {
            val f = File(dir, "$file.json")
            if (!f.exists()) continue
            val vectors = JsonParser.parseString(f.readText()).asJsonObject.getAsJsonArray("vectors")
            for (e in vectors) {
                val v = e.asJsonObject
                run.check("${dir.name}/$file ${v.str("id")}") { checkVector(file, v) }
            }
        }
    }
    println(if (run.failed == 0) "FTSP VECTORS PASSED (${run.passed})" else "FTSP VECTORS: ${run.failed} FAILED of ${run.passed + run.failed}")
    exitProcess(if (run.failed == 0) 0 else 1)
}

internal class VectorRun {
    var passed = 0
    var failed = 0

    fun check(name: String, body: () -> Unit) {
        try {
            body()
            passed++
        } catch (t: Throwable) {
            failed++
            println("FAIL $name: ${t.message ?: t}")
        }
    }
}

private fun checkVector(file: String, v: JsonObject) {
    when (file) {
        "kdf" -> {
            val kdf = Kdf.fromDisplayName(v.str("kdf"))
            expect(kdf == Kdf.fromId(v.hex("kdfId")[0])) { "kdfId maps to ${Kdf.fromId(v.hex("kdfId")[0])}" }
            expectHex(v.str("symkey"), kdf.deriveSymkey(v.str("password")!!.toCharArray(), v.hex("salt")))
        }
        "phrase" -> {
            val utf8 = v.hex("phraseUtf8Hex")
            expect(v.str("phrase")!!.toByteArray(Charsets.UTF_8).contentEquals(utf8)) { "phraseUtf8Hex" }
            val key = if (v.str("scheme") == "sha256") Hash.sha256(utf8)
            else Kdf.Argon2id_No1_NrC7.deriveSymkey(v.str("phrase")!!.toCharArray(), v.hex("salt"))
            expectHex(v.str("priKey32"), key)
        }
        "cipher-json" -> {
            val s = v.getAsJsonObject("secret")
            val json = v.str("cipherJson")!!
            val d = Decryptor()
            val r = when (EncryptType.valueOf(v.str("type")!!)) {
                EncryptType.Symkey -> d.decryptJsonBySymkey(json, s.hex("symkey"))
                EncryptType.Password -> d.decryptJsonByPassword(json, s.str("password")!!.toCharArray())
                EncryptType.AsyOneWay -> d.decryptJsonByAsyOneWay(json, s.hex("prikey"))
                EncryptType.AsyTwoWay -> d.decryptJsonByAsyTwoWay(json, s.hex("prikey"), s.hex("pubkey"))
            }
            expectDecrypted(v, r)
        }
        "bundle" -> {
            val bundle = v.hex("bundleHex")
            expect(bundle.contentEquals(Base64.getDecoder().decode(v.str("bundleBase64")))) { "bundleBase64 != bundleHex" }
            val parsed = CryptoDataByte.fromBundle(bundle)
            if (v.str("expect") == "reject") {
                expect(parsed == null) { "malformed bundle was accepted" }
                return
            }
            expect(parsed != null) { "bundle did not parse" }
            expect(parsed.alg == AlgorithmId.fromDisplayName(v.str("alg"))) { "alg ${parsed.alg}" }
            expect(parsed.type == EncryptType.valueOf(v.str("type")!!)) { "type ${parsed.type}" }
            val recorded = v.str("kdfRecorded")?.let { Kdf.fromDisplayName(it) }
            expect(parsed.kdf == recorded) { "kdfRecorded ${parsed.kdf}" }
            // Canonical is relative to the writer that made the file: FC-JDK writes type 3,
            // Android 2.4 type 4. Only FC-JDK's own canonical bytes must round-trip here.
            if (v.get("canonical").asBoolean && v.get("typeByte").asInt != 4) {
                expect(bundle.contentEquals(parsed.toBundle())) { "toBundle does not reproduce the bundle" }
            }
            expectDecrypted(v, decryptBundle(bundle, parsed.type, v.getAsJsonObject("secret")))
        }
        "algorithms" -> {
            val reject = v.str("expect") == "reject-decrypt"
            val r = try {
                decryptAlgorithm(v)
            } catch (t: Throwable) {
                if (reject) return
                throw t
            }
            if (reject) expect(r?.code != 0) { "tampered cipher reported success" }
            else expectDecrypted(v, r)
        }
        "vault" -> {
            val dek = VaultKey.unwrap(v.str("dekCipher"), v.str("password")!!.toCharArray())
            if (v.str("expect") == "reject") {
                expect(dek == null) { "a non-Argon2id dekCipher opened the vault" }
            } else {
                expectHex(v.str("dekHex"), dek)
                expect(VaultKey.unwrap(v.str("dekCipher"), "not the password".toCharArray()) == null) { "wrong password unwrapped" }
            }
        }
    }
}

private fun decryptBundle(bundle: ByteArray, type: EncryptType, s: JsonObject): CryptoDataByte {
    val d = Decryptor()
    return when (type) {
        EncryptType.Symkey -> d.decryptBundleBySymkey(bundle, s.hex("symkey"))
        EncryptType.Password -> d.decryptBundleByPassword(bundle, s.str("password")!!.toCharArray())
        EncryptType.AsyOneWay -> d.decryptBundleByAsyOneWay(bundle, s.hex("prikey"))
        EncryptType.AsyTwoWay -> d.decryptBundleByAsyTwoWay(bundle, s.hex("prikey"), s.hex("pubkey"))
    }
}

private fun decryptAlgorithm(v: JsonObject): CryptoDataByte? {
    val s = v.getAsJsonObject("secret")
    val d = Decryptor()
    return when (v.str("form")) {
        "json" -> {
            val json = v.str("cipherJson")!!
            when (EncryptType.valueOf(v.str("type")!!)) {
                EncryptType.Symkey -> d.decryptJsonBySymkey(json, s.hex("symkey"))
                EncryptType.Password -> d.decryptJsonByPassword(json, s.str("password")!!.toCharArray())
                EncryptType.AsyOneWay -> d.decryptJsonByAsyOneWay(json, s.hex("prikey"))
                EncryptType.AsyTwoWay -> d.decryptJsonByAsyTwoWay(json, s.hex("prikey"), s.hex("pubkey"))
            }
        }
        "bundle" -> {
            val bundle = v.hex("bundleHex")
            val parsed = CryptoDataByte.fromBundle(bundle) ?: error("bundle did not parse")
            expect(parsed.alg == AlgorithmId.fromDisplayName(v.str("alg"))) { "alg ${parsed.alg}" }
            decryptBundle(bundle, parsed.type, s)
        }
        "bitcoreEncbuf" -> CryptoDataByte().apply {
            data = Bitcore.decrypt(v.hex("encbufHex"), s.hex("prikey"))
            set0CodeMessage()
        }
        else -> error("unknown form ${v.str("form")}")
    }
}

private fun expectDecrypted(v: JsonObject, r: CryptoDataByte?) {
    expect(r != null) { "no result" }
    expect(r.code == 0) { "code ${r.code} ${r.message}" }
    expectHex(v.str("plaintextHex"), r.data)
    v.str("derivedWith")?.let { expect(r.kdf == Kdf.fromDisplayName(it)) { "reported KDF ${r.kdf}, expected $it" } }
}

private fun expectHex(expected: String?, actual: ByteArray?) =
    expect(actual != null && Hex.toHex(actual).equals(expected, ignoreCase = true)) { "got ${actual?.let(Hex::toHex)}, expected $expected" }

@OptIn(kotlin.contracts.ExperimentalContracts::class)
internal inline fun expect(ok: Boolean, msg: () -> String) {
    kotlin.contracts.contract { returns() implies ok }
    if (!ok) throw AssertionError(msg())
}

internal fun JsonObject.str(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
private fun JsonObject.hex(key: String): ByteArray = str(key)?.takeIf { it.isNotEmpty() }?.let(Hex::fromHex) ?: ByteArray(0)
