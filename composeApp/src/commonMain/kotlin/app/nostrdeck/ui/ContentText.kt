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
import app.nostrdeck.model.extractMediaUrls
import app.nostrdeck.model.tokenizeNostrContent
import app.nostrdeck.state.eventLinkOf
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
    // [#254] URL をホスト+パスの短縮ラベルで表示（引用カード等の行数が限られる場所用）。
    shortenUrls: Boolean = false,
    // [#378] にゃにゃにゃウイルス: プレーンテキスト断片だけを nyaize 変換する（表示専用）。
    // URL/メンション/#タグ/:shortcode: はトークンのまま素通しなので参照は壊れない。
    // 判定（オフ/自分のみ/全員 × 著者）は呼び出し側が Nyan.appliesTo で行い真偽で渡す。
    nyaize: Boolean = false,
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
                // [#791] nevent の kind・リレーヒントも渡す（kind:40/42 はパブリックチャットのルームで開く）。
                val link = eventLinkOf(bech) ?: return append(raw)
                styled(if (nav != null) ({ nav.onEventLink(link) }) else null)
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
            is ContentToken.Text -> {
                val frag = text.substring(tok.start, tok.end)
                append(if (nyaize) app.nostrdeck.model.nyaize(frag) else frag)   // [#378]
            }
            is ContentToken.Url -> withLink(LinkAnnotation.Url(tok.url, linkStyles)) {
                append(if (shortenUrls) shortUrlLabel(tok.url) else tok.url)
            }
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

/** [#254] 本文中の動画直リンク（mp4/webm/mov/m4v）。引用カードではテキストから除去してカルーセルに出す。 */
/**
 * [#254] URL の短縮表示ラベル（ホスト+パスを最大28文字）。
 * 引用カードなど「本文が4行しかない」場所で長い URL が占有しないようにする。
 */
fun shortUrlLabel(url: String): String {
    val bare = url.removePrefix("https://").removePrefix("http://").removePrefix("www.")
    return if (bare.length > 28) bare.take(28) + "…" else bare
}

/**
 * content からメディアURL（画像・動画）を抽出し、本文からは除去した (表示本文, 画像URL一覧) を返す。
 * タイムラインもチャットの吹き出しも共通で使う（メディアは本文の外＝下にカード表示する）。
 *
 * [#326] 判定は nostr-core の [app.nostrdeck.model.extractMediaUrls] に一本化した。
 * 以前はここに独自の正規表現（jpg/jpeg/png/gif/webp の5種のみ）を持っており、埋め込み判定側の
 * 拡張子リストとずれて bmp/avif が裸で残り、動画に至っては剥がす処理自体が無かった。
 */
fun extractMedia(content: String): Pair<String?, List<String>> = extractMediaUrls(content)

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
