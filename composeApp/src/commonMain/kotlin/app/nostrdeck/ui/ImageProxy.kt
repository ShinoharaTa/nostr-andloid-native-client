package app.nostrdeck.ui

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

    /** [width] px 幅・webp・品質 [quality] に圧縮した URL を返す。 */
    fun proxied(url: String, width: Int = 600, quality: Int = 75): String {
        if (url.isBlank()) return url
        val enc = url.encodeURLParameter()
        // we = 拡大しない（元が小さければそのまま）
        return "$HOST?url=$enc&w=$width&output=webp&q=$quality&we"
    }
}
