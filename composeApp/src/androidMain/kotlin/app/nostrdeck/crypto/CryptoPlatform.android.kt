package app.nostrdeck.crypto

import java.security.SecureRandom

private val rng = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { rng.nextBytes(it) }

actual fun currentUnixTime(): Long = System.currentTimeMillis() / 1000
