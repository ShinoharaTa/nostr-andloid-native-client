package app.nostrdeck.signer

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.nostrdeck.crypto.Nip19

/**
 * [#39] commonMain の [ExternalSignerProvider] の Android 実装（NIP-55 / Amber）。
 * MainActivity が生成して [ExternalSignerHost.provider] にセットし、起動時に [restore] を呼ぶ。
 */
class AndroidExternalSigner(private val appContext: Context) : ExternalSignerProvider {

    override val label = "Amber"

    override fun isAvailable(): Boolean = Nip55Bridge.isSignerInstalled()

    override suspend fun login(): String? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:")).apply {
            putExtra("type", "get_public_key")
            putExtra("permissions", PERMISSIONS_JSON) // 署名/暗号をまとめて事前許可 → 以後は背景で処理
        }
        val res = Nip55Bridge.sendIntent(intent)
        val data = res.data ?: return null
        val raw = (data.getStringExtra("signature") ?: data.getStringExtra("result"))
            ?.takeIf { it.isNotBlank() } ?: return null
        val pkg = data.getStringExtra("package")?.takeIf { it.isNotBlank() }
            ?: Nip55Bridge.DEFAULT_SIGNER_PACKAGE
        val hex = if (raw.startsWith("npub1")) Nip19.npubToHex(raw) else raw
        val npub = if (raw.startsWith("npub1")) raw else Nip19.hexToNpub(raw)
        save(appContext, hex, npub, pkg)
        SignerProvider.use(Nip55Signer(hex, npub, pkg))
        return hex
    }

    override fun logout() = clear(appContext)

    companion object {
        private const val PREF = "nip55_session"
        private const val PERMISSIONS_JSON =
            """[{"type":"sign_event"},{"type":"nip44_encrypt"},{"type":"nip44_decrypt"},{"type":"nip04_decrypt"}]"""

        /** 起動時: 保存済み NIP-55 セッションがあれば署名者を復元して true（無ければ false）。 */
        fun restore(appContext: Context): Boolean {
            val p = appContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val hex = p.getString("hex", null) ?: return false
            val npub = p.getString("npub", null) ?: return false
            val pkg = p.getString("package", null) ?: Nip55Bridge.DEFAULT_SIGNER_PACKAGE
            SignerProvider.use(Nip55Signer(hex, npub, pkg))
            return true
        }

        private fun save(c: Context, hex: String, npub: String, pkg: String) =
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("hex", hex).putString("npub", npub).putString("package", pkg).apply()

        private fun clear(c: Context) =
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
