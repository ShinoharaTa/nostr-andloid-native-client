package app.nostrdeck.model

/**
 * [#181] ノート本文中の要素を位置付きで抽出する共通トークナイザ（純ロジック・UI 非依存）。
 *
 * これまで本文の解析は3系統で別実装だった:
 *  - [app.nostrdeck.ui.noteAnnotated] … インライン装飾（AnnotatedString 化）
 *  - [detectEmbeds] … 埋め込みカード候補の URL 抽出
 *  - Markdown（NIP-23）… note/nevent/naddr の独自 Regex
 * URL の終端判定や nostr 参照の拾い方が微妙に食い違い、片方だけ直すと不整合が出やすかった。
 * ここに一本化し、装飾・カード化・記事解析が同じ抽出結果を使う。
 */
sealed interface ContentToken {
    /** [text] 内でのこのトークンの範囲（[start] 含む・[end] 含まず）。 */
    val start: Int
    val end: Int

    /** 装飾対象でない素のテキスト範囲。 */
    data class Text(override val start: Int, override val end: Int) : ContentToken

    /** http(s) URL。[url] は末尾の句読点/閉じ括弧を除いた実体。 */
    data class Url(val url: String, override val start: Int, override val end: Int) : ContentToken

    /**
     * nostr 参照。[bech] は npub1/nprofile1/note1/nevent1/naddr1 …（接頭辞 `nostr:` は含まない）。
     * [hadPrefix] は `nostr:` 接頭辞付きだったか。
     */
    data class NostrRef(
        val bech: String,
        val hadPrefix: Boolean,
        override val start: Int,
        override val end: Int,
    ) : ContentToken

    /** #ハッシュタグ。[tag] は先頭 `#` を除いた本体。 */
    data class Hashtag(val tag: String, override val start: Int, override val end: Int) : ContentToken

    /** `:shortcode:` 候補。emoji タグとの照合は呼び出し側で行う（[code] は前後の `:` を除いた本体）。 */
    data class EmojiShortcode(val code: String, override val start: Int, override val end: Int) : ContentToken
}

// nostr 参照の接頭辞（長い順に並べる＝最長一致）。
private val ENTITY_PREFIXES = listOf("nprofile1", "nevent1", "naddr1", "npub1", "note1")

// bech32 は ASCII 小英数字（大文字・1/b/i/o を含む数字レンジは許容、後続が日本語でも取り込まない）。
internal fun isBechChar(c: Char): Boolean = c in '0'..'9' || c in 'a'..'z'
private fun isTagChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/** 空白までを URL とみなし、末尾の句読点/閉じ括弧は URL から除外する（[app.nostrdeck.ui.noteAnnotated] と同一規則）。 */
internal fun urlEndOf(s: String, start: Int): Int {
    var e = start
    while (e < s.length && !s[e].isWhitespace()) e++
    while (e > start && s[e - 1] in ").,!?；。、）】」』") e--
    return e
}

/** [start] から続く bech32 文字列の終端 index。 */
internal fun bechEndOf(s: String, start: Int): Int {
    var e = start
    while (e < s.length && isBechChar(s[e])) e++
    return e
}

/** [i] がいずれかの nostr 参照接頭辞の先頭か。 */
private fun entityPrefixAt(s: String, i: Int): Boolean = ENTITY_PREFIXES.any { s.startsWith(it, i) }

/** 素の（`nostr:` 接頭辞なし）nostr 参照の先頭か。語中ヒットを避けるため直前が英数字なら対象外。 */
private fun bareEntityAt(s: String, i: Int): Boolean {
    if (i > 0 && (s[i - 1] in '0'..'9' || s[i - 1] in 'a'..'z' || s[i - 1] in 'A'..'Z')) return false
    return entityPrefixAt(s, i)
}

/** [start] は ':'。`:shortcode:` の終端 ':' の index を返す（無効なら -1）。 */
private fun shortcodeEndOf(s: String, start: Int): Int {
    var j = start + 1
    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '-')) j++
    return if (j < s.length && s[j] == ':' && j > start + 1) j else -1
}

/**
 * [text] を位置付きトークン列に分解する。連続する非トークン文字は 1 つの [ContentToken.Text] にまとめる。
 * 認識順（先頭一致）: URL → `nostr:`付き参照 → 素の参照 → #タグ → :shortcode:。
 */
fun tokenizeNostrContent(text: String): List<ContentToken> {
    val out = ArrayList<ContentToken>()
    val n = text.length
    var i = 0
    var textStart = 0

    fun flushText(upto: Int) {
        if (upto > textStart) out.add(ContentToken.Text(textStart, upto))
    }

    while (i < n) {
        val token: ContentToken? = when {
            text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                val end = urlEndOf(text, i)
                ContentToken.Url(text.substring(i, end), i, end)
            }
            text.startsWith("nostr:", i) && i + 6 < n && isBechChar(text[i + 6]) && entityPrefixAt(text, i + 6) -> {
                val end = bechEndOf(text, i + 6)
                ContentToken.NostrRef(text.substring(i + 6, end), hadPrefix = true, start = i, end = end)
            }
            bareEntityAt(text, i) -> {
                val end = bechEndOf(text, i)
                ContentToken.NostrRef(text.substring(i, end), hadPrefix = false, start = i, end = end)
            }
            text[i] == '#' && i + 1 < n && isTagChar(text[i + 1]) -> {
                var j = i + 1
                while (j < n && isTagChar(text[j])) j++
                ContentToken.Hashtag(text.substring(i + 1, j), i, j)
            }
            text[i] == ':' -> {
                val close = shortcodeEndOf(text, i)
                if (close > 0) ContentToken.EmojiShortcode(text.substring(i + 1, close), i, close + 1) else null
            }
            else -> null
        }
        if (token != null) {
            flushText(token.start)
            out.add(token)
            i = token.end
            textStart = i
        } else {
            i++
        }
    }
    flushText(n)
    return out
}
