package app.nostrdeck.signer

/**
 * [#39] 外部署名アプリ(NIP-55/Amber 等)ログインを、プラットフォーム側から注入する口。
 * commonMain の設定 UI はこの provider があれば「◯◯ でログイン」を出す。
 * Android は起動時に MainActivity が実装をセットする。iOS 等は null のまま（ボタン非表示）。
 */
object ExternalSignerHost {
    var provider: ExternalSignerProvider? = null
}

interface ExternalSignerProvider {
    /** ボタン表示ラベル（例: "Amber"）。 */
    val label: String

    /** 外部署名アプリが端末に存在するか（Android: nostrsigner: を処理できるアプリがあるか）。 */
    fun isAvailable(): Boolean

    /**
     * ログイン（get_public_key）を実行し、成功したら SignerProvider を外部署名へ切替えて
     * ログイン状態を永続化する。成功なら公開鍵(hex)、キャンセル/失敗なら null。
     * 呼び出し側は非nullなら repo.reloadForNewIdentity() を呼ぶこと。
     */
    suspend fun login(): String?

    /** 外部署名の永続状態を消す（呼び出し側でローカル鍵へ戻す）。 */
    fun logout()
}
