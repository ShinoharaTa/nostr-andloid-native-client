package app.nostrdeck.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import app.nostrdeck.theme.DeckColors

/**
 * ノート本文を装飾付き [AnnotatedString] に変換する。
 *  - http(s) URL        : タップでブラウザを開くリンク（LinkAnnotation.Url）
 *  - nostr: メンション   : npub/nprofile は `@…`、note/nevent は `↗…` に短縮して強調表示
 *  - #ハッシュタグ        : 強調表示
 * 生の `nostr:npub1<60文字>` や長い URL がそのまま出るのを防ぎ、読みやすくする。
 *
 * TODO: メンションは NIP-19 復号で `@表示名` に解決、タップでプロフィール/スレッドを開く。
 *       ハッシュタグはタップで該当カラムを開く。
 */
fun noteAnnotated(text: String): AnnotatedString = buildAnnotatedString {
    val accent = SpanStyle(color = DeckColors.Accent, fontWeight = FontWeight.Medium)
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text.startsWith("http://", i) || text.startsWith("https://", i) -> {
                val end = urlEnd(text, i)
                val url = text.substring(i, end)
                withLink(LinkAnnotation.Url(url, TextLinkStyles(style = accent))) { append(url) }
                i = end
            }
            text.startsWith("nostr:", i) && i + 6 < n && isBech(text[i + 6]) -> {
                val end = bechEnd(text, i + 6)
                val bech = text.substring(i + 6, end)
                withStyle(accent) { append(mentionLabel(bech)) }
                i = end
            }
            text[i] == '#' && i + 1 < n && isTagChar(text[i + 1]) -> {
                var j = i + 1
                while (j < n && isTagChar(text[j])) j++
                withStyle(accent) { append(text.substring(i, j)) }
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

/** bech32 は ASCII 小英数字（大文字・1/b/i/o を除く）。後続が日本語でも誤って取り込まない。 */
private fun isBech(c: Char): Boolean = c in '0'..'9' || c in 'a'..'z'
private fun bechEnd(s: String, start: Int): Int {
    var e = start
    while (e < s.length && isBech(s[e])) e++
    return e
}

private fun isTagChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

private fun mentionLabel(bech: String): String {
    val prefix = if (bech.startsWith("npub1") || bech.startsWith("nprofile1")) "@" else "↗"
    val short = if (bech.length > 14) bech.take(12) + "…" else bech
    return prefix + short
}
