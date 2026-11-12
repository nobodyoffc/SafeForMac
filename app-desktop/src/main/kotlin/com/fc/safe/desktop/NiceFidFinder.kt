package com.fc.safe.desktop

import core.crypto.KeyTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/** A freshly made key that has not been saved: its FID and the private key behind it. */
internal class NewKeyCandidate(val fid: String, val prikey32: ByteArray) {
    companion object {
        fun random(random: SecureRandom = SecureRandom()): NewKeyCandidate {
            val prikey32 = ByteArray(32).also(random::nextBytes)
            // A copy, in case the library keeps or clears what it is given.
            return NewKeyCandidate(KeyTools.prikeyToFid(prikey32.copyOf()), prikey32)
        }
    }
}

/**
 * Makes random keys until enough have an FID ending in the text asked for.
 * Android Safe's Find a Nice FID, spread over the machine's cores.
 */
internal object NiceFidFinder {

    private const val BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val MATCHABLE = BASE58.lowercase().toSet()

    /** FIDs are 34 characters and start with F, so a longer ending can't be chosen freely. */
    private const val MAX_LENGTH = 32

    /** Why no FID can end with [suffix], or null when one can. */
    fun problemWith(suffix: String): String? {
        val bad = suffix.lowercase().filterNot { it in MATCHABLE }.toSet()
        return when {
            bad.isNotEmpty() -> "An FID never has ${bad.joinToString(" ") { "\"$it\"" }} in it."
            suffix.length > MAX_LENGTH -> "Too long for an FID."
            else -> null
        }
    }

    fun matches(fid: String, suffix: String): Boolean = fid.endsWith(suffix, ignoreCase = true)

    /**
     * Hands [onFound] each match, on the caller's context, until [count] are found
     * or the caller is cancelled. Keys that do not match are wiped as it goes.
     */
    suspend fun find(suffix: String, count: Int, onFound: (NewKeyCandidate) -> Unit) {
        val caller = currentCoroutineContextOf()
        val found = AtomicInteger(0)
        val workers = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        coroutineScope {
            repeat(workers) {
                launch(Dispatchers.Default) {
                    val random = SecureRandom()
                    while (found.get() < count) {
                        ensureActive()
                        val candidate = NewKeyCandidate.random(random)
                        if (matches(candidate.fid, suffix) && found.incrementAndGet() <= count) {
                            withContext(caller) { onFound(candidate) }
                        } else {
                            candidate.prikey32.fill(0)
                        }
                    }
                }
            }
        }
    }

    private suspend fun currentCoroutineContextOf() = kotlin.coroutines.coroutineContext
}
