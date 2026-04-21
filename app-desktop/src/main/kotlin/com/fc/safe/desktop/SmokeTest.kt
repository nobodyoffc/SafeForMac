package com.fc.safe.desktop

import core.crypto.Decryptor
import core.crypto.Encryptor
import core.crypto.Kdf
import core.crypto.KeyTools
import data.fcData.AlgorithmId
import java.security.SecureRandom
import kotlin.system.exitProcess

/**
 * Phase 0 smoke test: exercise FC-JDK crypto on the full Compose + FC-JDK
 * classpath. Proves the library loads on macOS JVM, BouncyCastle is usable,
 * and FC-JDK's flat packages (utils, core, data, ...) don't collide with
 * Compose/Kotlin transitives.
 */
fun main() {
    val rng = SecureRandom()

    // 1. Key-gen: random 32-byte prikey -> 33-byte compressed pubkey
    val prikey = ByteArray(32).also(rng::nextBytes)
    val pubkey = KeyTools.prikeyToPubkey(prikey)
    check(pubkey.size == 33) { "expected 33-byte compressed pubkey, got ${pubkey.size}" }
    println("[1/3] key-gen ok  pubkey=${pubkey.toHex()}")

    // 2. KDF: derive 32-byte symkey from password + iv via Argon2id.
    //    Exercises BouncyCastle's Argon2BytesGenerator through FC-JDK's Kdf enum.
    val password = "smoke-test-password".toCharArray()
    val iv = ByteArray(16).also(rng::nextBytes)
    val symkey = Kdf.Argon2id_No1_NrC7.deriveSymkey(password, iv)
    check(symkey.size == 32) { "expected 32-byte symkey, got ${symkey.size}" }
    println("[2/3] argon2id ok  symkey=${symkey.toHex()}")

    // 3. AES-GCM round trip. Also exercises the GCMParameterSpec fix I just
    //    committed to FC-JDK's Decryptor; the bundle format carries the IV
    //    and transformation so encrypt/decrypt must agree on auth-tag length.
    val message = "hello from Safe desktop smoke test".toByteArray(Charsets.UTF_8)
    val encryptor = Encryptor(AlgorithmId.FC_AesGcm256_No1_NrC7)
    val bundle = encryptor.encryptToBundleBySymkey(message, symkey)
    val decrypted = Decryptor().decryptBundleBySymkey(bundle, symkey)
    check(decrypted.code == 0) { "decrypt failed code=${decrypted.code} msg=${decrypted.message}" }
    val roundTripped = String(decrypted.data, Charsets.UTF_8)
    check(roundTripped == String(message, Charsets.UTF_8)) { "round-trip mismatch: '$roundTripped'" }
    println("[3/3] aes-gcm round trip ok  cipher=${bundle.size}B")

    println()
    println("SMOKE TEST PASSED — FC-JDK + Compose classpath is clean.")
    exitProcess(0)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
