package app.nostrdeck.ui

import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.encodeURLParameter
import kotlin.concurrent.Volatile

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
     * wsrv.nl が拒否したホスト（実行時に学習）。以後は元 URL を直接使い、
     * 毎回の 400 往復を避けて Coil のキャッシュを効かせる（= 投稿ごとの再ロードを防ぐ）。
     * コピーオンライト: 読み取りは無ロックで安全、稀な書き込みのみ入れ替える。
     */
    @Volatile
    private var blockedHosts: Set<String> = emptySet()

    /**
     * [#359] 従量制回線（モバイル/データセーバー）のとき true。EventRepository が回線種別の監視で
     * 切り替える。true の間は幅を 2/3・品質を最大60 に落として転送量を抑える。
     * URL が変わるため回線切替直後はキャッシュミスが起きるが、以後はその回線での再取得が軽くなる。
     */
    @Volatile
    var dataSaver: Boolean = false

    /**
     * [width] px 幅・webp・品質 [quality] に圧縮した URL を返す。
     * [height] を渡すと幅ではなく高さで縮める（カスタム絵文字。横長の絵文字を幅で縮めると高さが数 px に潰れるため）。
     * [animated]=true なら `n=-1` で全フレームを保持する（GIF/アニメ WebP を動かす）。
     * wsrv.nl は既定で先頭1フレームのみ返すため、アニメを残すには n=-1 が必須。
     * 既にプロキシ拒否と分かっているホストは、プロキシを介さず元 URL を返す。
     */
    fun proxied(url: String, width: Int = 600, quality: Int = 75, animated: Boolean = false, height: Int? = null): String {
        // kind:0 の picture/banner URL には前後空白が混ざることがある（例: yabu.me のアイコン）。
        // そのままだと wsrv が空白込み URL を取得できず画像が出ないため、必ず trim する。
        val clean = url.trim()
        if (clean.isBlank()) return clean
        val host = hostOf(clean)
        if (host in blockedHosts) return clean
        // 手元のサーバー（開発中のメディアサーバー・ストア用スクリーンショットのデモ画像）は公開プロキシから取れないので通さない。
        if (host == "localhost" || host == "127.0.0.1" || host == "[::1]") return clean
        val enc = clean.encodeURLParameter()
        val q = if (dataSaver) minOf(quality, 60) else quality
        // 縮める基準（幅 w= か高さ h=）。縦横比は保たれる。
        val size = if (height != null) "h=${if (dataSaver) height * 2 / 3 else height}"
        else "w=${if (dataSaver) width * 2 / 3 else width}"
        // we = 拡大しない（元が小さければそのまま）
        val frames = if (animated) "&n=-1" else ""
        return "$HOST?url=$enc&$size&output=webp&q=$q&we$frames"
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

    /**
     * プロキシが拒否した元 URL のホストを記録する。以後 [proxied] はそのホストに対して
     * 元 URL を返すため、無駄な 400 往復が消え、取得済み画像がキャッシュから即表示される。
     */
    fun markProxyBlocked(originUrl: String) {
        val h = hostOf(originUrl) ?: return
        if (h in blockedHosts) return
        blockedHosts = blockedHosts + h  // copy-on-write
    }

    /** URL からホスト部（小文字）を取り出す。`scheme://host[:port]/...` 前提。失敗時 null。 */
    private fun hostOf(url: String): String? {
        val after = url.substringAfter("://", "")
        if (after.isEmpty()) return null
        return after.substringBefore('/').substringBefore('?').substringBefore(':')
            .lowercase().ifBlank { null }
    }
}
