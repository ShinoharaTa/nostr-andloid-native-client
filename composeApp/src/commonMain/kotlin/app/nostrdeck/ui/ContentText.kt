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
import app.nostrdeck.model.ContentToken
import app.nostrdeck.model.tokenizeNostrContent
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
    // [#fix] デコードに成功したものだけをリンク/強調にする。不完全・不正な bech32
    // （チェックサム不一致や未対応prefix）は素テキスト [raw] に戻す（誤リンク防止）。
    fun appendEntity(bech: String, raw: String) {
        // 装飾（nav 有り=タップ可 / 無し=強調のみ）。デコード済みの正当なエンティティにのみ適用。
        fun styled(onClick: (() -> Unit)?) {
            val label = mentionLabel(bech, resolveName)
            if (onClick != null) clickable(onClick, label) else withStyle(accent) { append(label) }
        }
        when {
            bech.startsWith("npub1") || bech.startsWith("nprofile1") -> {
                val hex = Nip19.mentionBechToHex(bech) ?: return append(raw)
                styled(if (nav != null) ({ nav.onMention(hex) }) else null)
            }
            bech.startsWith("note1") || bech.startsWith("nevent1") -> {
                val id = Nip19.eventBechToHex(bech) ?: return append(raw)
                styled(if (nav != null) ({ nav.onEvent(id) }) else null)
            }
            bech.startsWith("naddr1") -> {
                val addr = Nip19.naddrDecode(bech) ?: return append(raw)
                styled(if (nav != null) ({ nav.onAddr(addr) }) else null)
            }
            else -> append(raw)
        }
    }

    // [#181] トークン抽出は共通トークナイザに一本化（detectEmbeds/Markdown と同じ規則）。
    // ここは「トークン列 → 装飾付き AnnotatedString」への変換に専念する。
    for (tok in tokenizeNostrContent(text)) {
        when (tok) {
            is ContentToken.Text -> append(text.substring(tok.start, tok.end))
            is ContentToken.Url -> withLink(LinkAnnotation.Url(tok.url, linkStyles)) { append(tok.url) }
            is ContentToken.NostrRef -> appendEntity(tok.bech, text.substring(tok.start, tok.end))
            is ContentToken.Hashtag ->
                if (nav != null) clickable({ nav.onHashtag(tok.tag) }, "#${tok.tag}")
                else withStyle(accent) { append("#${tok.tag}") }
            is ContentToken.EmojiShortcode ->
                // NIP-30: emoji タグにある shortcode だけインライン画像。無いものは素のテキストに戻す。
                if (emojis.isNotEmpty() && tok.code in emojis) appendInlineContent("emoji:${tok.code}", ":${tok.code}:")
                else append(text.substring(tok.start, tok.end))
        }
    }
}

/** 本文中の画像URL（jpg/png/gif/webp）。表示時は本文から除去し [NoteImages] で下に出す。 */
val imageUrlRegex =
    Regex("""https?://\S+?\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?""", RegexOption.IGNORE_CASE)

/**
 * content から画像URLを抽出し、本文からは除去した (表示本文, 画像URL一覧) を返す。
 * タイムラインもチャットの吹き出しも共通で使う（画像は本文の外＝下にカード表示する）。
 */
fun extractMedia(content: String): Pair<String?, List<String>> {
    val urls = imageUrlRegex.findAll(content).map { it.value }.toList()
    if (urls.isEmpty()) return null to emptyList()
    var text = content
    urls.forEach { text = text.replace(it, "") }
    // URL 除去で生じた余分な空白/空行を整理。
    text = text.replace(Regex("""[ \t]{2,}"""), " ")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    return text.ifBlank { null } to urls.distinct()
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
