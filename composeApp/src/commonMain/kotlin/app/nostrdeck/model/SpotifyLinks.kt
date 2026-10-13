package app.nostrdeck.model

/**
 * [#836] Spotify のリンクカード（Web #820 の移植）。open.spotify.com は OGP の取得（ブラウザ風 UA）に中身の無いページ
 * （title「Spotify – Web Player」、og:image 無し）を返すので、曲・アルバム等の URL は公式 oEmbed
 * （`https://open.spotify.com/oembed?url=`、認証不要）の title / thumbnail_url / provider_name からカードを作る。
 * URL の判定は Web（web/src/features/linkcard/spotifyLoader.ts の canonicalSpotifyUrl）と同じ規則。
 */
object SpotifyLinks {
    // スキームとホストは大文字小文字を区別しない（URL の仕様どおり）。ホストの直後は必ずパスの「/」
    // （ポート・ユーザー情報・別ホスト `open.spotify.com.evil.example` は下の PATH で弾く）。
    private val ORIGIN = Regex("""^https?://open\.spotify\.com""", RegexOption.IGNORE_CASE)
    // パスは区別する（Web と同じ）。intl-xx/（intl-pt-BR/ 等も）は落とし、ID は 22 文字の base62。
    private val PATH = Regex(
        """^/(?:intl-[A-Za-z]{2}(?:-[A-Za-z0-9]{2,4})?/)?(track|album|playlist|artist|episode|show)/([A-Za-z0-9]{22})/?$""",
    )

    /** provider_name が無いときのサイト名。 */
    const val SITE_NAME = "Spotify"

    /**
     * oEmbed に渡す正規化した URL（`https://open.spotify.com/<種類>/<ID>`）。intl-xx/・クエリ・fragment を落とす。
     * 対象の形（曲・アルバム・プレイリスト・アーティスト・エピソード・番組）でなければ null（呼び出し側は従来の OGP）。
     */
    fun canonicalUrl(url: String): String? {
        val t = url.trim()
        val origin = ORIGIN.find(t) ?: return null
        val path = t.substring(origin.range.last + 1).substringBefore('?').substringBefore('#')
        val m = PATH.matchEntire(path) ?: return null
        return "https://open.spotify.com/${m.groupValues[1]}/${m.groupValues[2]}"
    }

    /** 公式 oEmbed の URL。[canonical] は [canonicalUrl] の結果（英数字と `:/.-` だけなので、そのまま符号化できる）。 */
    fun oembedUrl(canonical: String): String =
        "https://open.spotify.com/oembed?url=" + canonical.replace(":", "%3A").replace("/", "%2F")

    /** OGP と同じ DB キャッシュに持つときのキー（OGP で取った中身の無い結果と分ける）。Web と同じ形。 */
    fun cacheKey(canonical: String): String = "spotify-oembed:$canonical"

    /**
     * oEmbed の項目からカードの中身を作る（タイトル = title、画像 = thumbnail_url、サイト名 = provider_name）。
     * http(s) 以外の thumbnail_url は使わない。タイトルもジャケットも無ければ null。
     */
    fun fromOembed(canonical: String, title: String?, thumbnailUrl: String?, providerName: String?): OgpData? {
        val t = title?.trim()?.ifEmpty { null }
        val image = thumbnailUrl?.trim()?.takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
        if (t == null && image == null) return null
        return OgpData(canonical, title = t, image = image, siteName = providerName?.trim()?.ifEmpty { null } ?: SITE_NAME)
    }
}
