package app.nostrdeck.ui

import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckWeight

/**
 * ノート本文を装飾付き [AnnotatedString] に変換する。
 *  - http(s) URL         : タップでブラウザを開くリンク（LinkAnnotation.Url）
 *  - nostr: メンション    : npub/nprofile は `@…`、note/nevent は `↗…` に短縮して強調表示
 *  - #ハッシュタグ         : 強調表示
 * 生の `nostr:npub1<60文字>` や長い URL がそのまま出るのを防ぎ、読みやすくする。
 *
 * [nav] を渡すと @メンション→プロフィール / #タグ→ハッシュタグカラム / note・nevent→スレッド を
 * タップで開けるクリック可能リンクにする（null なら強調表示のみ＝プレビュー用途）。
 *
 * [linkColor] はメンション/リンク/ハッシュタグの色。既定は明色 [DeckColors.Accent]（暗色地の吹き出し用）。
 * 自分の吹き出し（明色地）では暗色を渡し、白地に白文字で埋もれないようにする。区別はウェイトで付く。
 */
fun noteAnnotated(
    text: String,
    resolveName: ((String) -> String?)? = null,
    emojis: Map<String, String> = emptyMap(),
    nav: NoteNav? = null,
    linkColor: Color = DeckColors.Accent,
): AnnotatedString = buildAnnotatedString {
    val accent = SpanStyle(color = linkColor, fontWeight = DeckWeight.Link)
    val linkStyles = TextLinkStyles(style = accent)

    // クリック可能リンク（クリックハンドラ付き）。nav 無し/解決失敗時は強調表示にフォールバック。
    fun clickable(onClick: () -> Unit, label: String) {
        withLink(LinkAnnotation.Clickable("nav", linkStyles, LinkInteractionListener { onClick() })) { append(label) }
    }
    // bech32 エンティティ（npub/nprofile/note/nevent/naddr）を1つ追記する。
    fun appendEntity(bech: String) {
        val label = mentionLabel(bech, resolveName)
        val isProfile = bech.startsWith("npub1") || bech.startsWith("nprofile1")
        val isEvent = bech.startsWith("note1") || bech.startsWith("nevent1")
        if (nav != null && isProfile) {
            val hex = Nip19.mentionBechToHex(bech)
            if (hex != null) { clickable({ nav.onMention(hex) }, label); return }
        }
        if (nav != null && isEvent) {
            val id = Nip19.eventBechToHex(bech)
            if (id != null) { clickable({ nav.onEvent(id) }, label); return }
        }
        withStyle(accent) { append(label) }
    }

    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                val end = urlEnd(text, i)
                val url = text.substring(i, end)
                withLink(LinkAnnotation.Url(url, linkStyles)) { append(url) }
                i = end
            }
            text.startsWith("nostr:", i) && i + 6 < n && isBech(text[i + 6]) -> {
                val end = bechEnd(text, i + 6)
                appendEntity(text.substring(i + 6, end))
                i = end
            }
            // 素の bech32 エンティティ（nostr: 接頭辞なし）。語中ヒットを避けるため直前が英数字なら対象外。
            bareEntityAt(text, i) -> {
                val end = bechEnd(text, i)
                appendEntity(text.substring(i, end))
                i = end
            }
            // NIP-30: :shortcode: が emoji タグにあればインライン画像で描く。
            text[i] == ':' && emojis.isNotEmpty() && shortcodeEnd(text, i).let { it > 0 && text.substring(i + 1, it) in emojis } -> {
                val close = shortcodeEnd(text, i)
                val code = text.substring(i + 1, close)
                appendInlineContent("emoji:$code", ":$code:")
                i = close + 1
            }
            text[i] == '#' && i + 1 < n && isTagChar(text[i + 1]) -> {
                var j = i + 1
                while (j < n && isTagChar(text[j])) j++
                val tag = text.substring(i + 1, j)
                if (nav != null) clickable({ nav.onHashtag(tag) }, "#$tag")
                else withStyle(accent) { append("#$tag") }
                i = j
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}

/** 空白までを URL とみなし、末尾の句読点/閉じ括弧は除外する。 */
private fun urlEnd(s: String, start: Int): Int {
    var e = start
    while (e < s.length && !s[e].isWhitespace()) e++
    while (e > start && s[e - 1] in ").,!?；。、）】」』") e--
    return e
}

/** 素の bech32 エンティティ（npub/nprofile/note/nevent/naddr）の先頭か。語中は除外。 */
private val BARE_ENTITY_PREFIXES = listOf("nprofile1", "nevent1", "naddr1", "npub1", "note1")
private fun bareEntityAt(s: String, i: Int): Boolean {
    if (i > 0 && (s[i - 1] in '0'..'9' || s[i - 1] in 'a'..'z' || s[i - 1] in 'A'..'Z')) return false
    return BARE_ENTITY_PREFIXES.any { s.startsWith(it, i) }
}

/** bech32 は ASCII 小英数字（大文字・1/b/i/o を除く）。後続が日本語でも誤って取り込まない。 */
private fun isBech(c: Char): Boolean = c in '0'..'9' || c in 'a'..'z'
private fun bechEnd(s: String, start: Int): Int {
    var e = start
    while (e < s.length && isBech(s[e])) e++
    return e
}

private fun isTagChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/** start は ':'。`:shortcode:` の終端 ':' の index を返す（無効なら -1）。 */
private fun shortcodeEnd(s: String, start: Int): Int {
    var j = start + 1
    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_' || s[j] == '-')) j++
    return if (j < s.length && s[j] == ':' && j > start + 1) j else -1
}

private fun mentionLabel(bech: String, resolveName: ((String) -> String?)?): String {
    // npub は hex に復号して表示名を引く。解決できれば @name、無ければ短縮 npub。
    if (bech.startsWith("npub1")) {
        val name = runCatching { Nip19.npubToHex(bech) }.getOrNull()?.let { resolveName?.invoke(it) }
        if (!name.isNullOrBlank()) return "@$name"
    }
    val prefix = if (bech.startsWith("npub1") || bech.startsWith("nprofile1")) "@" else "↗"
    val short = if (bech.length > 14) bech.take(12) + "…" else bech
    return prefix + short
}
