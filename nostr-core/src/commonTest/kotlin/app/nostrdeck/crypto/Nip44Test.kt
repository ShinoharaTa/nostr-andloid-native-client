package app.nostrdeck.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NIP-44 v2 の公式テストベクタで暗号化/復号を検証（valid.encrypt_decrypt）。
 * 会話鍵を直接与え、ChaCha20 / HKDF / パディングの正しさを確認する
 * （ECDH=secp256k1 は JNI ネイティブが JVM 単体テストで読めないため実機側で担保）。
 */
class Nip44Test {
    private val convKey = "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".hexToBytes()

    @Test
    fun encryptDecrypt_ascii() {
        val nonce = "0000000000000000000000000000000000000000000000000000000000000001"
        val payload = "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"
        assertEquals("a", Nip44.decrypt(convKey, payload))
        assertEquals(payload, Nip44.encrypt(convKey, "a", nonce.hexToBytes()))
    }

    /** [#967] 復号を頼む前の形の確認。版 2 の暗号文だけを通す。 */
    @Test
    fun isPayload_accepts_only_v2_ciphertext() {
        val payload = "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"
        assertTrue(Nip44.isPayload(payload))
        assertFalse(Nip44.isPayload("こんにちは、スパムです"))
        assertFalse(Nip44.isPayload("Aw" + payload.drop(2)))    // 版 3
        assertFalse(Nip44.isPayload("#" + payload.drop(1)))     // NIP-44: 先頭の # は未対応の版
        assertFalse(Nip44.isPayload(payload.take(64)))          // 短すぎる
        assertFalse(Nip44.isPayload(""))
    }

    @Test
    fun encryptDecrypt_multibyte() {
        val nonce = "f00000000000000000000000000000f00000000000000000000000000000000f"
        val payload = "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj"
        assertEquals("🍕🫃", Nip44.decrypt(convKey, payload))
        assertEquals(payload, Nip44.encrypt(convKey, "🍕🫃", nonce.hexToBytes()))
    }
}
