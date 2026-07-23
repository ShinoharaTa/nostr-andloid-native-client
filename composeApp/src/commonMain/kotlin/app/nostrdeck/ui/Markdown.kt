package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import app.nostrdeck.crypto.Nip19
import kotlinx.coroutines.launch
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * [#124] NIP-23 長文記事向けの簡易 Markdown レンダラー。
 * 対応: 見出し(#〜####) / 段落 / 箇条書き(-,*,数字.) / 引用(>) / 水平線(---) /
 *       フェンスコード(```) / 画像(![](url) は行単位でブロック画像化) /
 *       インライン: **太字** *斜体* `code` [リンク](url) 素URL。
 * 完全な CommonMark 互換ではなく「記事が読める」ことを目標にした実装。
 */
@Composable
fun MarkdownBody(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier) {
        blocks.forEach { b -> RenderBlock(b) }
    }
}

// ---- ブロックモデル ----

private sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data class ListItem(val text: String, val ordered: Boolean, val index: Int) : MdBlock
    data class Image(val url: String, val alt: String) : MdBlock
    /** [#124] 行単位の nostr:note1/nevent1 参照。Nosli 等の「まとめ」記事は本文がこの並びになる。 */
    data class NoteRef(val id: String, val relays: List<String>) : MdBlock
    /** [#124] 行単位の nostr:naddr1 参照（記事→記事のリンク等）。解決してから埋め込む。 */
    data class AddrRef(val addr: Nip19.AddrRef) : MdBlock
    data object Rule : MdBlock
}

private val imageLine = Regex("""^!\[([^\]]*)]\(([^)\s]+)[^)]*\)\s*$""")
private val orderedItem = Regex("""^(\d+)[.)]\s+(.*)$""")
// 行単位の参照。Nosli 等はバッククォートで包むことがあるので `…` も許容する。
private val noteRefLine = Regex("""^`?(?:nostr:)?((?:note|nevent)1[a-z0-9]+)`?\s*$""")
private val addrRefLine = Regex("""^`?(?:nostr:)?(naddr1[a-z0-9]+)`?\s*$""")
// コードスパン/リンク先が nostr 参照そのものかの判定（リンク表示に切り替える）。
private val nostrRefOnly = Regex("""^(?:nostr:)?((?:note|nevent|naddr)1[a-z0-9]+)$""")

private fun parseBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.lines()
    var i = 0
    val para = StringBuilder()
    fun flushPara() {
        if (para.isNotBlank()) out.add(MdBlock.Paragraph(para.toString().trim()))
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) { buf.appendLine(lines[i]); i++ }
                out.add(MdBlock.Code(buf.toString().trimEnd()))
            }
            trimmed.matches(Regex("""^#{1,6}\s+.*$""")) -> {
                flushPara()
                val level = trimmed.takeWhile { it == '#' }.length
                out.add(MdBlock.Heading(level, trimmed.dropWhile { it == '#' }.trim()))
            }
            imageLine.matches(trimmed) -> {
                flushPara()
                val m = imageLine.find(trimmed)!!
                out.add(MdBlock.Image(url = m.groupValues[2], alt = m.groupValues[1]))
            }
            noteRefLine.matches(trimmed) -> {
                // 行全体がノート参照 → 埋め込みカード。デコード不能な bech32 は段落として素通し。
                val bech = noteRefLine.find(trimmed)!!.groupValues[1]
                val ref = Nip19.eventBechToIdAndRelays(bech)
                if (ref != null) { flushPara(); out.add(MdBlock.NoteRef(ref.first, ref.second)) }
                else { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
            }
            addrRefLine.matches(trimmed) -> {
                val bech = addrRefLine.find(trimmed)!!.groupValues[1]
                val addr = Nip19.naddrDecode(bech)
                if (addr != null) { flushPara(); out.add(MdBlock.AddrRef(addr)) }
                else { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
            }
            trimmed.startsWith(">") -> {
                flushPara()
                // 連続する引用行をまとめる
                val buf = StringBuilder()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    buf.appendLine(lines[i].trim().removePrefix(">").trim()); i++
                }
                i--
                out.add(MdBlock.Quote(buf.toString().trim()))
            }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> { flushPara(); out.add(MdBlock.Rule) }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                { flushPara(); out.add(MdBlock.ListItem(trimmed.substring(2).trim(), ordered = false, index = 0)) }
            orderedItem.matches(trimmed) -> {
                flushPara()
                val m = orderedItem.find(trimmed)!!
                out.add(MdBlock.ListItem(m.groupValues[2], ordered = true, index = m.groupValues[1].toIntOrNull() ?: 0))
            }
            trimmed.isEmpty() -> flushPara()
            else -> { if (para.isNotEmpty()) para.append('\n'); para.append(line) }
        }
        i++
    }
    flushPara()
    return out
}

// ---- 描画 ----

@Composable
private fun RenderBlock(b: MdBlock) {
    when (b) {
        is MdBlock.Heading -> {
            val size = when (b.level) { 1 -> 22.sp; 2 -> 19.sp; 3 -> 17.sp; else -> 15.sp }
            InlineText(
                b.text, fontSize = size, fontWeight = DeckWeight.Strong,
                modifier = Modifier.padding(top = DeckSpace.Lg, bottom = DeckSpace.Sm),
            )
        }
        is MdBlock.Paragraph -> InlineText(
            b.text, fontSize = DeckType.Body,
            modifier = Modifier.padding(vertical = DeckSpace.Xs),
        )
        is MdBlock.Quote -> QuoteBlock(b.text)
        is MdBlock.Code -> Box(
            Modifier.fillMaxWidth().padding(vertical = DeckSpace.Xs)
                .clip(RoundedCornerShape(DeckRadius.Sm)).background(DeckColors.Surface2)
                .horizontalScroll(rememberScrollState()),
        ) {
            Text(
                b.text, color = DeckColors.Text2, fontSize = DeckType.Caption,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(DeckSpace.Md),
            )
        }
        is MdBlock.ListItem -> Row(Modifier.padding(start = DeckSpace.Sm, top = 2.dp, bottom = 2.dp)) {
            Text(
                if (b.ordered) "${b.index}." else "•",
                color = DeckColors.Text2, fontSize = DeckType.Body,
            )
            Spacer(Modifier.width(DeckSpace.Sm))
            InlineText(b.text, fontSize = DeckType.Body)
        }
        is MdBlock.Image -> NoteImages(listOf(b.url))
        is MdBlock.NoteRef -> EmbeddedNoteRef(b.id, b.relays)
        is MdBlock.AddrRef -> EmbeddedAddrRef(b.addr)
        MdBlock.Rule -> HorizontalDivider(
            color = DeckColors.Border, modifier = Modifier.padding(vertical = DeckSpace.Md),
        )
    }
}

/**
 * [#124] 行単位の naddr 参照。アドレス（kind+著者+dタグ）を event id に解決してから
 * [EmbeddedNoteRef] で埋め込む。解決失敗（リレーに無い等）は淡色メッセージ。
 */
@Composable
private fun EmbeddedAddrRef(addr: Nip19.AddrRef) {
    val repo = LocalRepository.current ?: return
    var resolved by remember(addr) { mutableStateOf<String?>(null) }
    var failed by remember(addr) { mutableStateOf(false) }
    LaunchedEffect(addr) {
        val id = repo.resolveAddress(addr.kind, addr.pubkey, addr.dTag, addr.relays)
        if (id != null) resolved = id else failed = true
    }
    val id = resolved
    if (id != null) {
        EmbeddedNoteRef(id, emptyList())
    } else {
        Box(
            Modifier.padding(vertical = DeckSpace.Xs).fillMaxWidth()
                .clip(RoundedCornerShape(DeckRadius.Md)).background(DeckColors.Surface2)
                .padding(DeckSpace.Sm),
        ) {
            Text(
                if (failed) stringResource(Res.string.md_naddr_failed) else stringResource(Res.string.md_resolving),
                color = DeckColors.Text3, fontSize = DeckType.Caption,
            )
        }
    }
}

/**
 * [#124] 記事本文の nostr:note1/nevent1 参照を埋め込みノートカードで表示する。
 * DB に無ければリレー（+ nevent のリレーヒント）へ取得を出し、解決までプレースホルダを出す。
 * カードタップは QuotedNoteCard の導線（スレッド / 30023 なら記事）に乗る。
 */
@Composable
private fun EmbeddedNoteRef(id: String, relays: List<String>) {
    val repo = LocalRepository.current
    if (repo == null) return
    LaunchedEffect(id) { repo.requestEvent(id, relays) }
    val event = remember(id) { repo.eventByIdFlow(id) }.collectAsState(null).value
    Box(Modifier.padding(vertical = DeckSpace.Xs)) {
        if (event == null) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Md))
                    .background(DeckColors.Surface2).padding(DeckSpace.Sm),
            ) {
                Text(stringResource(Res.string.md_loading_note), color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
        } else {
            val profile = repo.let { r -> remember(event.pubkey) { r.profileFlow(event.pubkey) } }
                .collectAsState(null).value
            QuotedNoteCard(
                NoteUi(
                    event = event,
                    author = profile ?: Profile(pubkey = event.pubkey, name = event.pubkey.take(12), handle = ""),
                ),
            )
        }
    }
}

/** 引用ブロック（左ボーダー + 淡色テキスト）。RenderBlock の Quote から使う。 */
@Composable
private fun QuoteBlock(text: String) {
    // 左ボーダーをテキスト高に合わせるため IntrinsicSize.Min で行高を確定させる。
    Row(Modifier.padding(vertical = DeckSpace.Xs).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(DeckColors.BorderStrong))
        Spacer(Modifier.width(DeckSpace.Md))
        InlineText(text, fontSize = DeckType.Body, color = DeckColors.Text2)
    }
}

// ---- インライン装飾 ----

/**
 * **bold** / *italic* / `code` / [text](url) / 素URL を AnnotatedString へ。
 * [#231] CMP 1.11 で非推奨の ClickableText を廃し、リンクは [LinkAnnotation.Clickable] を
 * 直接 [withLink] で埋め込む。タップは [onLink]（url 文字列を受け取る）へ委譲する。
 */
private fun renderInline(
    text: String,
    linkColor: androidx.compose.ui.graphics.Color,
    onLink: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    // リンク区間に色 + 下線を付けつつ、タップを onLink(url) に流す共通ヘルパ。
    fun appendLink(display: String, url: String) {
        withLink(LinkAnnotation.Clickable(tag = url, linkInteractionListener = { onLink(url) })) {
            pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            append(display); pop()
        }
    }

    val rest = text
    // 処理順: リンク → 太字 → 斜体 → code。単純化のため逐次置換で走査する。
    val tokenRegex = Regex(
        """\[([^\]]+)]\(([^)\s]+)[^)]*\)""" +      // [text](url)
            """|\*\*([^*]+)\*\*""" +                 // **bold**
            """|\*([^*\s][^*]*)\*""" +               // *italic*
            """|`([^`]+)`""" +                        // `code`
            """|(https?://[^\s)\]}>,、。」]+)""" +    // bare URL
            """|(?:nostr:)?((?:note|nevent|naddr)1[a-z0-9]+)""",  // 段落中のノート/記事参照（短縮表示）
    )
    var idx = 0
    while (idx < rest.length) {
        val m = tokenRegex.find(rest, idx)
        if (m == null) { append(rest.substring(idx)); break }
        append(rest.substring(idx, m.range.first))
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> appendLink(g[1], g[2])  // [text](url)
            g[3].isNotEmpty() -> { pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(g[3]); pop() }
            g[4].isNotEmpty() -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(g[4]); pop() }
            g[5].isNotEmpty() -> {
                // `nostr:naddr…` のようにコードで包まれた参照は、生の bech32 を出さず
                // 短縮リンクにする（タップでアプリ内遷移）。それ以外は従来どおり code 表示。
                val refMatch = nostrRefOnly.find(g[5])
                if (refMatch != null) {
                    val bech = refMatch.groupValues[1]
                    appendLink(bech.take(12) + "…", "nostr:$bech")
                } else {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x22FFFFFF)))
                    append(g[5]); pop()
                }
            }
            g[6].isNotEmpty() -> appendLink(g[6], g[6])  // bare URL
            // ノート参照は全長 bech32 を出さず短縮表示。タップでスレッド/記事を開く（nostr: スキーム）。
            g[7].isNotEmpty() -> appendLink(g[7].take(12) + "…", "nostr:${g[7]}")
        }
        idx = m.range.last + 1
    }
}

@Composable
private fun InlineText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    color: androidx.compose.ui.graphics.Color = DeckColors.Text,
) {
    val uriHandler = LocalUriHandler.current
    val nav = LocalNoteNav.current
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    // クリック処理はリンク注釈に埋め込むため、最新のハンドラ群を参照できるよう保持する。
    // （annotated は text 変化時のみ再生成されるので、注釈内から常に最新値を読めるようにする）
    val onLink by rememberUpdatedState<(String) -> Unit> { url ->
        if (url.startsWith("nostr:")) {
            // nostr 参照はアプリ内で開く（スレッド / kind:30023 なら記事ビューワー）。
            // [参考リンク](nostr:naddr1…) のような markdown リンク形式もここに来る。
            val bech = url.removePrefix("nostr:")
            if (bech.startsWith("naddr1")) {
                Nip19.naddrDecode(bech)?.let { a ->
                    scope.launch {
                        repo?.resolveAddress(a.kind, a.pubkey, a.dTag, a.relays)
                            ?.let { id -> nav?.onEvent?.invoke(id) }
                    }
                }
            } else {
                Nip19.eventBechToIdAndRelays(bech)?.let { (id, relays) ->
                    repo?.requestEvent(id, relays)  // 未取得でも先に取得を出しておく
                    nav?.onEvent?.invoke(id)
                }
            }
        } else {
            runCatching { uriHandler.openUri(url) }
        }
    }
    val annotated = remember(text) { renderInline(text, DeckColors.Accent) { onLink(it) } }
    Text(annotated, color = color, fontSize = fontSize, fontWeight = fontWeight,
        lineHeight = fontSize * 1.55, modifier = modifier)
}
