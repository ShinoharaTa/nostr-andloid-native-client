package app.nostrdeck.signer

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.nostrdeck.crypto.secureRandomBytes
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore で 32byte の nsec を保護する KeyVault（追加依存なし）。
 *
 * 方式（envelope encryption）:
 *  1. "AndroidKeyStore" に AES-256 ラップ鍵を生成（[KEY_ALIAS]）。鍵自体は端末の
 *     セキュアハードウェア / TEE に閉じ、アプリは取り出せない。
 *  2. その鍵で AES/GCM により秘密鍵を暗号化し、IV+暗号文を通常の
 *     SharedPreferences に Base64 で保存する。GCM が機密性と完全性を担保する。
 *
 * 注意: 秘密鍵そのものは privateKey() の戻り値として平文 ByteArray でメモリに出る
 * （secp256k1 署名に必要なため）。保存・永続化は常に暗号化済み。
 */
class KeystoreKeyVault(context: Context) : KeyVault {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun hasKey(): Boolean =
        prefs.contains(PREF_CIPHERTEXT) && prefs.contains(PREF_IV)

    override fun privateKey(): ByteArray {
        val ctB64 = prefs.getString(PREF_CIPHERTEXT, null) ?: error("鍵が未設定です")
        val ivB64 = prefs.getString(PREF_IV, null) ?: error("鍵が未設定です")
        val ciphertext = base64Decode(ctB64)
        val iv = base64Decode(ivB64)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val priv = cipher.doFinal(ciphertext)
        check(priv.size == 32) { "復号した秘密鍵長が不正: ${priv.size}" }
        return priv
    }

    override fun importPrivateKey(privateKey: ByteArray) {
        require(privateKey.size == 32) { "秘密鍵は 32byte" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrapKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(privateKey)
        prefs.edit()
            .putString(PREF_CIPHERTEXT, base64Encode(ciphertext))
            .putString(PREF_IV, base64Encode(iv))
            .apply()
    }

    override fun generate(): ByteArray {
        val k = secureRandomBytes(32)
        importPrivateKey(k)
        return k
    }

    /** Keystore からラップ用 AES 鍵を取得。無ければ生成する。 */
    private fun wrapKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // setUserAuthenticationRequired(true) を付けると生体/PIN を強制できる（TODO: 設定で切替）
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    private fun base64Encode(bytes: ByteArray): String =
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)

    private fun base64Decode(s: String): ByteArray =
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "nostr_nsec_wrap"
        private const val PREFS_NAME = "nostr_keyvault"
        private const val PREF_CIPHERTEXT = "nsec_ct"
        private const val PREF_IV = "nsec_iv"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
