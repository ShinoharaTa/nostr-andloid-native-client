package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import coil3.compose.AsyncImage
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.article_title
import nostr_deck_client.composeapp.generated.resources.article_untitled
import nostr_deck_client.composeapp.generated.resources.md_naddr_failed
import nostr_deck_client.composeapp.generated.resources.md_resolving
import org.jetbrains.compose.resources.stringResource

/** 本文中の naddr1（先頭に nostr: が付く場合あり）を抽出する。重複除去し最大数を制限。 */
private val naddrRegex = Regex("""(?:nostr:)?(naddr1[a-z0-9]+)""")

private fun extractNaddrs(content: String): List<Nip19.AddrRef> =
    naddrRegex.findAll(content)
        .mapNotNull { Nip19.naddrDecode(it.groupValues[1]) }
        // 現状は kind:30023(長文記事)のみカード展開。他 kind は個別対応まで本文リンクのみ。
        .filter { it.kind == 30023 }
        .distinctBy { "${it.kind}:${it.pubkey}:${it.dTag}" }
        .take(3)
        .toList()

/**
 * [#217] ノート本文が参照する naddr(kind:30023 長文記事) を OGP 風カードで展開する。
 * URL の [LinkEmbeds] と同様、本文の下に別ブロックとして並べる。30023 以外は個別対応のため
 * ここでは描画しない（本文中のリンク表示は従来どおり残る）。
 */
@Composable
fun NoteNaddrEmbeds(content: String, modifier: Modifier = Modifier) {
    val addrs = remember(content) { extractNaddrs(content) }
    if (addrs.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        addrs.forEach { addr ->
            Spacer(Modifier.size(DeckSpace.Sm))
            ArticleEmbedCard(addr)
        }
    }
}

/**
 * naddr を解決し、kind:30023 なら OGP 風カード（画像 + タイトル + 概要/1行目）を表示する。
 * 解決前はプレースホルダ、失敗・非対応 kind は淡色メッセージ（本文リンクで開ける前提）。
 */
@Composable
private fun ArticleEmbedCard(addr: Nip19.AddrRef) {
    val repo = LocalRepository.current ?: return
    val nav = LocalNoteNav.current
    var resolvedId by remember(addr) { mutableStateOf<String?>(null) }
    var failed by remember(addr) { mutableStateOf(false) }
    LaunchedEffect(addr) {
        val id = repo.resolveAddress(addr.kind, addr.pubkey, addr.dTag, addr.relays)
        if (id != null) resolvedId = id else failed = true
    }
    val id = resolvedId
    val event = if (id != null) {
        remember(id) { repo.eventByIdFlow(id) }.collectAsState(null).value
    } else {
        null
    }

    val box = Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(DeckRadius.Md))
        .clickable(enabled = nav != null) { nav?.onAddr?.invoke(addr) }
        .background(DeckColors.Surface2, RoundedCornerShape(DeckRadius.Md))

    if (event != null && event.kind == 30023) {
        ArticleCardBody(event, box)
    } else {
        Box(box.padding(DeckSpace.Sm)) {
            Text(
                if (failed) stringResource(Res.string.md_naddr_failed) else stringResource(Res.string.md_resolving),
                color = DeckColors.Text3, fontSize = DeckType.Caption,
            )
        }
    }
}

@Composable
private fun ArticleCardBody(event: NostrEvent, boxModifier: Modifier) {
    fun tag(name: String): String? =
        event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)?.takeIf { it.isNotBlank() }

    val title = tag("title") ?: stringResource(Res.string.article_untitled)
    val image = tag("image")
    // 概要は summary タグ優先。無ければ本文の最初の非空行（Markdown 記号は簡易的にそのまま）。
    val excerpt = tag("summary")
        ?: event.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    Row(boxModifier) {
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(DeckRadius.Md))
                    .background(DeckColors.Surface),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.weight(1f).padding(DeckSpace.Sm)) {
            // 記事バッジ（NIP-23 · kind:30023 相当。OGPの「サイト名」位置）。
            Text(
                stringResource(Res.string.article_title),
                color = DeckColors.Text3,
                fontSize = DeckType.Caption,
                fontWeight = DeckWeight.Name,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                title,
                color = DeckColors.Text,
                fontSize = DeckType.Body,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 20.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (excerpt != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    excerpt,
                    color = DeckColors.Text2,
                    fontSize = DeckType.Caption,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
