package app.nostrdeck.crypto

import java.security.SecureRandom

// [#218] Desktop(JVM) 実装。Android と同一の java.security / javax.crypto を用いる。
private val rng = SecureRandom()

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { rng.nextBytes(it) }

actual fun currentUnixTime(): Long = System.currentTimeMillis() / 1000

actual fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
    val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(
        javax.crypto.Cipher.DECRYPT_MODE,
        javax.crypto.spec.SecretKeySpec(key, "AES"),
        javax.crypto.spec.IvParameterSpec(iv),
    )
    return cipher.doFinal(ciphertext)
}

actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}
