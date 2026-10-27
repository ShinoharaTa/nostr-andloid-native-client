package app.nostrdeck.model

/**
 * [#948] 投稿を読み上げる文章にする（純関数）。「名前。本文」。
 *  - URL・nostr: の投稿の参照（note / nevent / naddr）は読まない
 *  - nostr: の人の参照（npub / nprofile）は名前が分かれば名前、分からなければ読まない
 *  - カスタム絵文字（:shortcode:）は読まない。ハッシュタグは「#」を外して語だけ読む
 *  - 長い本文は [MAX_BODY] 文字で切る（読み上げが長く続かないように）
 *  - 本文が残らない投稿は、画像があれば「画像」、無ければ読まない（null）
 */
object SpeechText {
    /** 本文の上限（文字）。 */
    const val MAX_BODY = 140
    /** 名前の上限（文字）。 */
    const val MAX_NAME = 20

    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
    private val NOSTR_PERSON = Regex("""nostr:(npub1|nprofile1)[0-9a-z]+""")
    private val NOSTR_OTHER = Regex("""nostr:(note1|nevent1|naddr1)[0-9a-z]+""")
    private val SHORTCODE = Regex(""":[A-Za-z0-9_+\-]+:""")
    private val HASHTAG = Regex("""(^|\s)#(?=\S)""")
    private val SPACES = Regex("""[ \t　]+""")
    private val BLANK_LINES = Regex("""\n\s*\n+""")

    /**
     * @param name 投稿者の表示名（空なら名前を読まない）
     * @param body 本文（メディアの URL を除いたもの。[NoteUi.text]）
     * @param hasMedia 画像・動画が付いているか
     * @param personName nostr:npub / nprofile → 名前（分からなければ null）
     */
    fun of(name: String, body: String, hasMedia: Boolean, personName: (String) -> String?): String? {
        var text = body
            .replace(NOSTR_PERSON) { m -> personName(m.value.removePrefix("nostr:"))?.let { " $it " } ?: " " }
            .replace(NOSTR_OTHER, " ")
            .replace(URL, " ")
            .replace(SHORTCODE, " ")
            .replace(HASHTAG) { it.groupValues[1] }
            .lines().joinToString("\n") { it.replace(SPACES, " ").trim() }
            .replace(BLANK_LINES, "\n")
            .trim()
        if (text.length > MAX_BODY) text = text.take(MAX_BODY).trimEnd() + "、以下略"
        if (text.isEmpty()) text = if (hasMedia) "画像" else return null
        val who = name.replace(SHORTCODE, " ").replace(SPACES, " ").trim().take(MAX_NAME)
        return if (who.isEmpty()) text else "$who。$text"
    }
}
