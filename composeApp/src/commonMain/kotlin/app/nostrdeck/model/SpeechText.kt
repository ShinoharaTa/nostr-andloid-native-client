package app.nostrdeck.model

/**
 * [#948] 投稿を読み上げる文章にする（純関数）。「名前。本文」。
 *  - URL・nostr: の投稿の参照（note / nevent / naddr）は読まない
 *  - nostr: の人の参照（npub / nprofile）は名前が分かれば名前、分からなければ読まない
 *  - カスタム絵文字（:shortcode:）は読まない。ハッシュタグは「#」を外して語だけ読む
 *  - 文字・数字を含まない文（絵文字だけ・記号だけの文）は読まない（ZEN-TTS は読む文字が無い文で失敗し、
 *    その投稿の残りも読めなくなるため）。名前も同じ
 *  - 長い本文は [MAX_BODY] 文字で切る（読み上げが長く続かないように）
 *  - 注意書き（NIP-36 content-warning）の付いた投稿は本文を読まず、注意書きだけ読む（画面でも畳んでいるため）
 *  - 読む本文が残らない投稿は、画像があれば「画像」、絵文字があれば「絵文字」、どちらも無ければ読まない（null）
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
    /** 文（区切りの記号・改行まで）。区切りの無い最後の文も含む。 */
    private val SENTENCE = Regex("""[^。！？!?\n]*[。！？!?\n]+|[^。！？!?\n]+$""")

    /** 本文の nostr:npub / nprofile（[of] の personName に渡す bech32）。名前を先に引いておくため。 */
    fun personRefs(body: String): List<String> =
        NOSTR_PERSON.findAll(body).map { it.value.removePrefix("nostr:") }.distinct().toList()

    /**
     * @param name 投稿者の表示名（空なら名前を読まない）
     * @param body 本文（メディアの URL を除いたもの。[NoteUi.text]）
     * @param hasMedia 画像・動画が付いているか
     * @param contentWarning 注意書き（NIP-36。null なら無し、"" なら理由なし）
     * @param personName nostr:npub / nprofile → 名前（分からなければ null）
     */
    fun of(
        name: String,
        body: String,
        hasMedia: Boolean,
        contentWarning: String? = null,
        personName: (String) -> String?,
    ): String? {
        val who = speakable(name.replace(SHORTCODE, " ").replace(SPACES, " ").trim()).take(MAX_NAME)
        if (contentWarning != null) {
            val reason = speakable(contentWarning.replace(SPACES, " ").trim()).take(MAX_NAME)
            val text = if (reason.isEmpty()) "注意書きのある投稿" else "注意書きのある投稿、$reason"
            return if (who.isEmpty()) text else "$who。$text"
        }
        val cleaned = body
            .replace(NOSTR_PERSON) { m -> personName(m.value.removePrefix("nostr:"))?.let { " $it " } ?: " " }
            .replace(NOSTR_OTHER, " ")
            .replace(URL, " ")
            .replace(SHORTCODE, " ")
            .replace(HASHTAG) { it.groupValues[1] }
            .lines().joinToString("\n") { it.replace(SPACES, " ").trim() }
            .replace(BLANK_LINES, "\n")
            .trim()
        var text = speakable(cleaned)
        if (text.length > MAX_BODY) text = text.take(MAX_BODY).trimEnd() + "、以下略"
        if (text.isEmpty()) text = when {
            hasMedia -> "画像"
            cleaned.any { it.isSurrogate() || it.category == CharCategory.OTHER_SYMBOL } -> "絵文字"
            else -> return null
        }
        return if (who.isEmpty()) text else "$who。$text"
    }

    /** 文字・数字を含まない文を落とす（区切りの記号ごと）。 */
    private fun speakable(s: String): String =
        SENTENCE.findAll(s).map { it.value }.filter { seg -> seg.any { it.isLetterOrDigit() } }.joinToString("").trim()
}
