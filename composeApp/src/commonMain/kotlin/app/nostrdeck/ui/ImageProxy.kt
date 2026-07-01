package app.nostrdeck.ui

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter

/**
 * 画像 URL をリサイズ/圧縮プロキシに通す。
 * - 幅制限 + webp + 品質指定で**転送量とキャッシュ容量を圧縮**
 * - プロキシ結果を Coil がディスクキャッシュ（= ローカルにあれば再取得しない）
 *
 * 既定は公開プロキシ wsrv.nl。プライバシー/自前運用なら imgproxy 等に [HOST] を差し替える。
 */
object ImageProxy {
    private const val HOST = "https://wsrv.nl/"

    /**
     * [width] px 幅・webp・品質 [quality] に圧縮した URL を返す。
     * [animated]=true なら `n=-1` で全フレームを保持する（GIF/アニメ WebP を動かす）。
     * wsrv.nl は既定で先頭1フレームのみ返すため、アニメを残すには n=-1 が必須。
     */
    fun proxied(url: String, width: Int = 600, quality: Int = 75, animated: Boolean = false): String {
        if (url.isBlank()) return url
        val enc = url.encodeURLParameter()
        // we = 拡大しない（元が小さければそのまま）
        val frames = if (animated) "&n=-1" else ""
        return "$HOST?url=$enc&w=$width&output=webp&q=$quality&we$frames"
    }

    /**
     * [proxied] が生成した URL から元画像 URL を復元する（プロキシ URL でなければ null）。
     * プロキシ（wsrv.nl）が特定ドメイン/TLD をポリシーで拒否した場合の**元 URL 直取得**
     * フォールバックに使う（例: .cc ドメインは wsrv.nl が 400 で拒否する）。
     */
    fun originOf(data: Any?): String? {
        val s = data as? String ?: return null
        if (!s.startsWith("$HOST?url=")) return null
        val enc = s.substringAfter("?url=").substringBefore("&")
        return enc.decodeURLQueryComponent().ifBlank { null }
    }
}
