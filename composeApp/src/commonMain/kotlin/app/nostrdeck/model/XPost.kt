package app.nostrdeck.model

/**
 * [#733] X（Twitter）の投稿カードの中身。x.com の OGP（title / description / image）から組み立てる。
 *
 * x.com は投稿ページの OGP に、題名＝著者名と @ハンドル、説明＝本文（改行つき）、画像＝写真の 1 枚目
 * （写真が無ければプロフィール画像）を返す。取得は従来の OGP と同じで、通信先も増えない。
 * 写真は 1 枚目だけ・動画はサムネのみ・投稿日時は無し（oEmbed を足せば日時だけ取れる）。
 */
data class XPost(
    val url: String,
    /** 表示名。題名から読めなければ null（ハンドルだけ出す） */
    val name: String?,
    /** @ を除いたハンドル */
    val handle: String,
    /** 本文（改行は OGP のまま） */
    val text: String,
    /** 写真（1 枚目）。無ければ null */
    val image: String?,
    /** プロフィール画像。写真がある投稿では OGP に載らないので null のことが多い */
    val avatar: String?,
)

object XPosts {
    private val POST_URL = Regex(
        """^https?://(?:www\.|mobile\.)?(?:x|twitter)\.com/([A-Za-z0-9_]{1,15})/status(?:es)?/\d+""",
        RegexOption.IGNORE_CASE,
    )
    // 題名の形は X の表示言語（Accept-Language）で変わる。
    //   en: "jack (@jack) on X" / 旧 "jack (@jack) on Twitter" / "jack (@jack) / X"
    //   ja: "Xユーザーのjack（@jack）さん"
    private val TITLE_EN = Regex("""^(.*?)\s*\(@([A-Za-z0-9_]{1,15})\)\s*(?:on X|on Twitter|/ X|/ Twitter)\s*$""", RegexOption.DOT_MATCHES_ALL)
    private val TITLE_JA = Regex("""^Xユーザーの(.*?)（@([A-Za-z0-9_]{1,15})）さん$""", RegexOption.DOT_MATCHES_ALL)
    // 削除済み・非公開・存在しない投稿で x.com が本文の代わりに返す文。これなら投稿は取れていない。
    private val NOT_FOUND = listOf(
        "could not be found", "may have been deleted", "doesn't exist", "見つかりません", "削除された", "存在しません",
    )

    /**
     * [#733] 公式 oEmbed（publish.x.com）の html から投稿日時の文字列を取る。blockquote の末尾のリンク
     * （`… <a href="https://x.com/u/status/1?ref_src=…">2026年10月4日</a></blockquote>`）の中身で、
     * 言語は oEmbed の lang に従う（ja なら「2026年10月4日」、en なら「October 4, 2026」）。無ければ null。
     */
    fun dateFromOembedHtml(html: String): String? =
        Regex(""">([^<>]+)</a>\s*</blockquote>""").find(html)?.groupValues?.get(1)?.trim()
            ?.replace("&amp;", "&")?.replace("&nbsp;", " ")?.ifEmpty { null }

    /** [#733] 投稿者のプロフィールページの URL（アイコンをプロフィールの OGP から取るため）。 */
    fun profileUrl(handle: String): String = "https://x.com/$handle"

    /** OGP の画像がプロフィール画像（`/profile_images/`）か。 */
    fun isProfileImage(url: String?): Boolean = url != null && url.contains("/profile_images/")

    /** X / Twitter の投稿（status）URL か。プロフィールやホーム等は対象外。 */
    fun isPostUrl(url: String): Boolean = POST_URL.containsMatchIn(url.trim())

    /** URL のパスからハンドルを取る（題名が読めないときの保険）。 */
    fun handleFromUrl(url: String): String? = POST_URL.find(url.trim())?.groupValues?.get(1)

    /** 題名から (表示名, ハンドル)。形が合わなければ null。 */
    fun parseTitle(title: String): Pair<String, String>? {
        val t = title.trim()
        val m = TITLE_JA.find(t) ?: TITLE_EN.find(t) ?: return null
        return m.groupValues[1].trim() to m.groupValues[2]
    }

    /**
     * OGP から投稿カードを組み立てる。本文が取れていない（OGP 失敗・削除済み・空）なら null を返し、
     * 呼び出し側は従来のリンクカードに落とす。
     */
    fun from(url: String, ogp: OgpData?): XPost? {
        if (ogp == null || !isPostUrl(url)) return null
        val text = ogp.description?.trim().orEmpty()
        if (text.isEmpty() || NOT_FOUND.any { text.contains(it, ignoreCase = true) }) return null
        val parsed = ogp.title?.let { parseTitle(it) }
        val handle = parsed?.second ?: handleFromUrl(url) ?: return null
        val img = ogp.image?.takeIf { it.isNotBlank() }
        val isAvatar = isProfileImage(img)
        return XPost(
            url = url,
            name = parsed?.first?.takeIf { it.isNotEmpty() },
            handle = handle,
            text = text,
            image = img?.takeUnless { isAvatar },
            avatar = img?.takeIf { isAvatar },
        )
    }
}
