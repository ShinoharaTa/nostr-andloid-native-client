package app.nostrdeck.signer

import android.app.Activity

/** [#Nosskey] Credential Manager が要求する Activity を保持する。MainActivity がセットする。 */
object NosskeyBridge {
    @Volatile var activity: Activity? = null

    /** WebAuthn の Relying Party。assetlinks.json でアプリと関連付けたドメインでなければならない。 */
    const val RP_ID = "nostrism.shino3.net"
    const val RP_NAME = "Nostrism"

    /** PRF 評価用の固定ソルト（登録/解錠で同一 → 同じ PRF 出力＝同じ暗号鍵）。 */
    const val PRF_SALT = "app.nostrdeck.nosskey.prf.v1"
}
