package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.ThreadEntry
import app.nostrdeck.model.ZapUi
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType

/**
 * THREAD レンダラー：NIP-10 返信ツリー。
 * root → 祖先 → 対象ノート(ハイライト) → 返信、を深さインデントで表示。下部に返信ボックス。
 */
@Composable
fun ThreadColumn(
    spec: ColumnSpec,
    entries: List<ThreadEntry>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menu: ColumnMenuActions? = null,
    onBack: (() -> Unit)? = null,
    zaps: List<ZapUi> = emptyList(),
    onReply: (NoteUi) -> Unit = {},
    onQuote: (NoteUi) -> Unit = {},
    onAuthorClick: ((String) -> Unit)? = null,
) {
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            iconTint = DeckColors.Repost, iconBg = DeckColors.Repost.copy(alpha = 0.14f),
            onPin = onPin, onClose = onClose, menu = menu, onBack = onBack,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(entries, key = { it.note.event.id }) { entry ->
                ThreadRow(entry, onReply = { onReply(entry.note) }, onQuote = { onQuote(entry.note) }, onAuthorClick = onAuthorClick)
            }
            // Zap を「リプライ風」に列挙（誰がいくら Zap したか＋コメント）。
            if (zaps.isNotEmpty()) {
                item(key = "zap_header") {
                    val total = zaps.sumOf { it.sats }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Bolt, null, tint = DeckColors.Zap, modifier = Modifier.width(16.dp))
                        Spacer(Modifier.width(DeckSpace.Xs))
                        Text("Zap · 合計 $total sats", color = DeckColors.Text3, fontSize = DeckType.Label, fontWeight = FontWeight.SemiBold)
                    }
                    HorizontalDivider(color = DeckColors.Border)
                }
                items(zaps, key = { "zap_" + it.id }) { z ->
                    ZapRow(z, onAuthorClick = onAuthorClick)
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
        }
        // 下部の返信ボックスは起点（フォーカス）ノート、無ければ先頭への返信。
        val replyTarget = entries.firstOrNull { it.isFocused } ?: entries.firstOrNull()
        ReplyBox(enabled = replyTarget != null, onClick = { replyTarget?.let { onReply(it.note) } })
    }
}

@Composable
private fun ThreadRow(entry: ThreadEntry, onReply: () -> Unit, onQuote: () -> Unit = {}, onAuthorClick: ((String) -> Unit)? = null) {
    val bg = when {
        entry.isFocused -> DeckColors.AccentWeak
        entry.isRoot -> DeckColors.Accent.copy(alpha = 0.05f)
        else -> DeckColors.Surface
    }
    Column(
        Modifier.fillMaxWidth().background(bg)
            .padding(start = (entry.depth * 18).dp)
    ) {
        if (entry.replyToName != null) {
            Text(
                "返信先 @${entry.replyToName}", color = DeckColors.Text3, fontSize = DeckType.Label,
                modifier = Modifier.padding(start = DeckSpace.Md, top = DeckSpace.Sm),
            )
        }
        NoteItem(entry.note, onReply = onReply, onQuote = onQuote, onAuthorClick = onAuthorClick)
    }
}

/** Zap 1件をリプライ風に表示（⚡アバター + 名前 + 金額 + 任意コメント）。 */
@Composable
private fun ZapRow(zap: ZapUi, onAuthorClick: ((String) -> Unit)?) {
    val tap = if (onAuthorClick != null) Modifier.clickable { onAuthorClick(zap.zapper.pubkey) } else Modifier
    Row(Modifier.fillMaxWidth().padding(DeckSpace.Md)) {
        Box {
            Avatar(zap.zapper.name, zap.zapper.pictureUrl, Modifier.then(tap))
            // 右下に ⚡ バッジ。
            Icon(
                Icons.Outlined.Bolt, "Zap", tint = DeckColors.Zap,
                modifier = Modifier.align(Alignment.BottomEnd).width(14.dp),
            )
        }
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    zap.zapper.name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).then(tap),
                )
                Spacer(Modifier.width(DeckSpace.Xs))
                Text("${zap.sats} sats", color = DeckColors.Zap, fontSize = DeckType.Sub, fontWeight = FontWeight.Bold)
            }
            if (zap.comment.isNotBlank()) {
                Spacer(Modifier.width(DeckSpace.Xs))
                Text(zap.comment, color = DeckColors.Text2, fontSize = DeckType.Body)
            }
        }
    }
}

@Composable
private fun ReplyBox(enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "返信を書く…", color = DeckColors.Text3, fontSize = DeckType.Caption,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(DeckRadius.Full))
                .background(DeckColors.Surface2).padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        Icon(Icons.AutoMirrored.Outlined.Send, "送信", tint = DeckColors.Accent,
            modifier = Modifier.padding(DeckSpace.Xs))
    }
}
