package com.fc.safe.desktop

import com.fc.safe.desktop.KeyInputDetector.Kind
import core.crypto.CryptoDataByte
import core.crypto.Encryptor
import core.crypto.Kdf
import core.crypto.KeyTools
import data.fcData.AlgorithmId
import utils.Hex
import kotlin.system.exitProcess

/**
 * Android Safe's `KeyInputDetectorTest`, run against the desktop port: each form
 * of key text is told apart, near-miss typos are reported rather than guessed,
 * and a cipher in either form opens back to the same private key.
 *
 * Run: `./gradlew :app-desktop:keyInputDetectorSmoke`
 */
private const val PASSWORD = "correct horse"

fun main() {
    var fail = 0
    fun check(name: String, ok: Boolean) {
        println((if (ok) "PASS  " else "FAIL  ") + name)
        if (!ok) fail++
    }
    fun kind(name: String, expected: Kind, input: String?) =
        check("$name -> $expected", KeyInputDetector.detect(input) == expected)

    val prikey = ByteArray(32) { (it + 1).toByte() }
    val prikeyHex = Hex.toHex(prikey)
    val wif = KeyTools.prikey32To38WifCompressed(prikeyHex)
    val pubkey = Hex.toHex(KeyTools.prikeyToPubkey(prikey.copyOf()))
    val fid = KeyTools.prikeyToFid(prikey.copyOf())
    fun typo(text: String) = text.dropLast(1) + (if (text.last() == '2') '3' else '2')
    fun encrypt(plaintext: ByteArray): CryptoDataByte {
        val encryptor = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
        encryptor.setKdf(Kdf.Sha256Iv_No1_NrC7)
        return encryptor.encryptByPassword(plaintext, PASSWORD.toCharArray()).also { check(it.code == 0) }
    }
    fun open(input: String) = KeyInputDetector.openCipher(input, PASSWORD.toCharArray())

    kind("null", Kind.EMPTY, null)
    kind("blank", Kind.EMPTY, "  \n ")

    kind("hex", Kind.PRIKEY, prikeyHex)
    kind("0x hex", Kind.PRIKEY, "0x$prikeyHex")
    kind("WIF", Kind.PRIKEY, wif)
    kind("padded WIF", Kind.PRIKEY, "  $wif\n")
    check("WIF decodes to the prikey", KeyInputDetector.toPrikey32(wif).contentEquals(prikey))

    kind("pubkey", Kind.PUBKEY, pubkey)
    kind("FID", Kind.FID, fid)

    kind("WIF typo", Kind.BAD_PRIKEY, typo(wif))
    kind("hex typo", Kind.BAD_PRIKEY, prikeyHex.substring(0, 63) + "g")
    kind("FID typo", Kind.BAD_FID, typo(fid))
    kind("pubkey typo", Kind.BAD_PUBKEY, "05" + pubkey.substring(2))

    kind("word", Kind.UNKNOWN, "hello")
    kind("unterminated JSON", Kind.UNKNOWN, "{\"unterminated\"")
    kind("two WIFs", Kind.MULTIPLE, "$wif $wif")

    val json = encrypt(prikey.copyOf()).toJson()
    kind("JSON cipher", Kind.CIPHER, json)
    check("JSON cipher opens to the prikey",
        KeyInputDetector.prikeyFromPlaintext(open(json)).contentEquals(prikey))
    check("wrong password opens nothing",
        KeyInputDetector.openCipher(json, "wrong".toCharArray()) == null)

    val base64 = encrypt(prikey.copyOf()).toBase64()
    kind("Base64 cipher", Kind.CIPHER, base64)
    check("Base64 cipher opens to the prikey",
        KeyInputDetector.prikeyFromPlaintext(open(base64)).contentEquals(prikey))

    val wifJson = encrypt(wif.toByteArray()).toJson()
    check("encrypted WIF text opens to the prikey",
        KeyInputDetector.prikeyFromPlaintext(open(wifJson)).contentEquals(prikey))

    val keyJsonCipher = encrypt("{\"id\":\"F\"}".toByteArray()).toJson()
    kind("encrypted key JSON", Kind.CIPHER, keyJsonCipher)
    val opened = open(keyJsonCipher)
    check("encrypted key JSON is not a prikey", KeyInputDetector.prikeyFromPlaintext(opened) == null)
    check("encrypted key JSON is JSON", KeyInputDetector.isJson(opened))
    check("a prikey is not JSON", !KeyInputDetector.isJson(prikey))

    kind("KeyInfo JSON", Kind.BACKUP, "{\"id\":\"$fid\"}")
    kind("two ciphers", Kind.BACKUP, encrypt(prikey.copyOf()).toJson() + "\n" + encrypt(prikey.copyOf()).toJson())

    val symCipher = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7).encryptBySymkey(prikey.copyOf(), ByteArray(32))
    kind("symkey cipher", Kind.NON_PASSWORD_CIPHER, symCipher.toJson())

    // Phrase keys: both schemes are deterministic, and they differ.
    val sha = phraseToPrikey32("correct horse battery staple", useArgon2id = false)
    val argon = phraseToPrikey32("correct horse battery staple", useArgon2id = true)
    check("SHA-256 phrase key is sha256(phrase)",
        Hex.toHex(sha) == "c4bbcb1fbec99d65bf59d85c8cb62ee2db963f0fe106f483d9afa73bd4e39a8a")
    check("Argon2id phrase key is 32 bytes and repeatable", argon.size == 32 &&
        argon.contentEquals(phraseToPrikey32("correct horse battery staple", useArgon2id = true)))
    check("the two schemes give different keys", !sha.contentEquals(argon))
    println("Argon2id phrase key FID: " + KeyTools.prikeyToFid(argon))

    // Find a nice FID: every hit ends as asked and belongs to its prikey.
    check("\"0\" can't be in an FID", NiceFidFinder.problemWith("a0") != null)
    check("\"l\" matches L", NiceFidFinder.problemWith("l") == null)
    val hits = ArrayList<NewKeyCandidate>()
    val started = System.nanoTime()
    kotlinx.coroutines.runBlocking { NiceFidFinder.find("Fc", 5) { hits += it } }
    val seconds = (System.nanoTime() - started) / 1e9
    check("finder stops at 5 hits", hits.size == 5)
    check("every hit ends with fc, any case", hits.all { it.fid.endsWith("fc", ignoreCase = true) })
    check("every hit is its prikey's FID", hits.all { KeyTools.prikeyToFid(it.prikey32.copyOf()) == it.fid })
    check("random keys differ", NewKeyCandidate.random().fid != NewKeyCandidate.random().fid)
    println("5 two-character hits in %.1fs: %s".format(seconds, hits.map { it.fid.takeLast(4) }))

    if (fail > 0) {
        println("$fail check(s) failed")
        exitProcess(1)
    }
    println("All checks passed")
}
