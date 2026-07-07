package app.nostrdeck.signer

import android.content.Context
import android.util.Base64
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import app.nostrdeck.crypto.toHex
import fr.acinq.secp256k1.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [#Nosskey] Android の Credential Manager + WebAuthn PRF 拡張で nsec を保護する実装。
 *  - enroll: パスキーを作成 → PRF 出力を得る → その鍵で nsec を AES-GCM 暗号化して保存。
 *  - unlock: パスキーで assertion → PRF 出力 → nsec を復号して NosskeySigner へ。
 *
 * 注意: パスキー作成には RP_ID ドメインとアプリの assetlinks.json による関連付けが必須。
 */
class AndroidNosskey(private val appContext: Context) : NosskeyProvider {

    override fun isAvailable(): Boolean = true // Credential Manager は 26+ で利用可

    override fun hasSession(): Boolean = load(appContext) != null
    override fun sessionPubkeyHex(): String? = load(appContext)?.pubkeyHex

    override suspend fun enroll(): String? {
        val activity = NosskeyBridge.activity ?: return null
        val vault = SignerProvider.vault()
        if (!vault.hasKey()) return null
        val nsec = vault.privateKey()
        val pubHex = pubkeyHexOf(nsec)
        val cm = CredentialManager.create(activity)

        // 1) パスキー作成（PRF 拡張つき）
        val createResp = cm.createCredential(
            activity,
            CreatePublicKeyCredentialRequest(buildCreateJson(pubHex)),
        ) as CreatePublicKeyCredentialResponse
        val reg = JSONObject(createResp.registrationResponseJson)
        val credentialId = reg.getString("id")

        // 2) PRF 出力を得る（作成応答に無ければ assertion で取得）
        var prf = readPrf(reg)
        if (prf == null) prf = assertionPrf(cm, activity, credentialId)
        prf ?: return null // PRF 非対応環境

        // 3) nsec を PRF 鍵で暗号化して保存
        val (nonce, ct) = aesGcmEncrypt(prf, nsec)
        save(appContext, Session(credentialId, pubHex, nonce, ct))
        SignerProvider.use(NosskeySigner(nsec))
        return pubHex
    }

    override suspend fun unlock(): String? {
        val activity = NosskeyBridge.activity ?: return null
        val s = load(appContext) ?: return null
        val cm = CredentialManager.create(activity)
        val prf = assertionPrf(cm, activity, s.credentialId) ?: return null
        val nsec = aesGcmDecrypt(prf, s.nonce, s.ciphertext)
        SignerProvider.use(NosskeySigner(nsec))
        return s.pubkeyHex
    }

    override fun logout() = clear(appContext)

    // ---- WebAuthn / PRF ----

    private suspend fun assertionPrf(cm: CredentialManager, activity: android.app.Activity, credentialId: String): ByteArray? {
        val resp = cm.getCredential(
            activity,
            GetCredentialRequest(listOf(GetPublicKeyCredentialOption(buildGetJson(credentialId)))),
        )
        val cred = resp.credential as? PublicKeyCredential ?: return null
        return readPrf(JSONObject(cred.authenticationResponseJson))
    }

    /** clientExtensionResults.prf.results.first を 32byte で取り出す。 */
    private fun readPrf(resp: JSONObject): ByteArray? {
        val results = resp.optJSONObject("clientExtensionResults")
            ?.optJSONObject("prf")?.optJSONObject("results") ?: return null
        val first = results.optString("first", "").ifEmpty { return null }
        return b64urlDecode(first).takeIf { it.size >= 32 }?.copyOf(32)
    }

    private fun buildCreateJson(pubHex: String): String = JSONObject().apply {
        put("rp", JSONObject().put("id", NosskeyBridge.RP_ID).put("name", NosskeyBridge.RP_NAME))
        put("user", JSONObject()
            .put("id", b64url(pubHex.encodeToByteArray()))
            .put("name", "nostr:$pubHex")
            .put("displayName", "Nostr Key"))
        put("challenge", b64url(randomBytes(32)))
        put("pubKeyCredParams", JSONArray()
            .put(JSONObject().put("type", "public-key").put("alg", -7))
            .put(JSONObject().put("type", "public-key").put("alg", -257)))
        put("authenticatorSelection", JSONObject()
            .put("residentKey", "required").put("userVerification", "required"))
        put("extensions", prfExtension())
    }.toString()

    private fun buildGetJson(credentialId: String): String = JSONObject().apply {
        put("challenge", b64url(randomBytes(32)))
        put("rpId", NosskeyBridge.RP_ID)
        put("allowCredentials", JSONArray().put(
            JSONObject().put("type", "public-key").put("id", credentialId)))
        put("userVerification", "required")
        put("extensions", prfExtension())
    }.toString()

    private fun prfExtension(): JSONObject = JSONObject().put(
        "prf", JSONObject().put("eval",
            JSONObject().put("first", b64url(NosskeyBridge.PRF_SALT.encodeToByteArray()))),
    )

    // ---- AES-GCM ----

    private fun aesGcmEncrypt(key: ByteArray, data: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return nonce to c.doFinal(data)
    }

    private fun aesGcmDecrypt(key: ByteArray, nonce: ByteArray, ct: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return c.doFinal(ct)
    }

    // ---- helpers ----

    private fun pubkeyHexOf(nsec: ByteArray): String {
        val secp = Secp256k1.get()
        return secp.pubKeyCompress(secp.pubkeyCreate(nsec)).copyOfRange(1, 33).toHex()
    }

    data class Session(val credentialId: String, val pubkeyHex: String, val nonce: ByteArray, val ciphertext: ByteArray)

    companion object {
        private const val PREF = "nosskey_session"
        private fun randomBytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
        private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        private fun b64urlDecode(s: String) = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

        /** 起動時: 登録済みなら未解錠 Nosskey を差し込む（identity は判るが署名は要解錠）。true=復元した。 */
        fun restore(appContext: Context): Boolean {
            val s = load(appContext) ?: return false
            SignerProvider.use(LockedNosskeySigner(s.pubkeyHex))
            return true
        }

        private fun save(c: Context, s: Session) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("credId", s.credentialId)
            .putString("pub", s.pubkeyHex)
            .putString("nonce", b64url(s.nonce))
            .putString("ct", b64url(s.ciphertext))
            .apply()

        private fun load(c: Context): Session? {
            val p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val credId = p.getString("credId", null) ?: return null
            val pub = p.getString("pub", null) ?: return null
            val nonce = p.getString("nonce", null)?.let { b64urlDecode(it) } ?: return null
            val ct = p.getString("ct", null)?.let { b64urlDecode(it) } ?: return null
            return Session(credId, pub, nonce, ct)
        }

        private fun clear(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
