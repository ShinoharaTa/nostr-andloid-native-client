package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.AddReaction
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ThreadEntry
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.launch

/**
 * [#124] NIP-23 長文記事（kind:30023）のビューワー。
 * nevent/note1 参照で開いたイベントが 30023 のとき、スレッド詳細の代わりに表示する。
 *  - ヘッダ: title / image / summary / published_at タグ + 著者行
 *  - 本文: 簡易 Markdown（[MarkdownBody]）
 *  - リアクション（kind:7・絵文字ピッカー対応）と返信（kind:1, e-tag）＝コメント
 */
@Composable
fun ArticleReader(
    state: DeckState,
    article: NostrEvent,
    comments: List<ThreadEntry>,
) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val profile = repo?.let { r -> remember(article.pubkey) { r.profileFlow(article.pubkey) } }
        ?.collectAsState(null)?.value

    fun tag(name: String): String? =
        article.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)?.takeIf { it.isNotBlank() }

    val title = tag("title") ?: stringResource(Res.string.article_untitled)
    val image = tag("image")
    val summary = tag("summary")
    val publishedAt = tag("published_at")?.toLongOrNull() ?: article.createdAt

    var showPicker by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        // ヘッダバー（戻る + 記事ラベル）
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Sm))
                    .clickable { state.popDetail() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(Res.string.common_back), tint = DeckColors.Text)
            }
            Spacer(Modifier.width(DeckSpace.Sm))
            Column {
                TitleText(stringResource(Res.string.article_title))
                HintText("NIP-23 · kind:30023")
            }
        }
        HorizontalDivider(color = DeckColors.Border)

        LazyColumn(Modifier.fillMaxSize()) {
            item("header") {
                Column(Modifier.padding(horizontal = DeckSpace.Lg)) {
                    Spacer(Modifier.padding(top = DeckSpace.Md))
                    // タイトル
                    Text(title, color = DeckColors.Text, fontSize = 24.sp, fontWeight = DeckWeight.Name, lineHeight = 32.sp)
                    Spacer(Modifier.padding(top = DeckSpace.Sm))
                    // 著者行（タップでプロフィール）
                    AuthorRow(article.pubkey, profile, publishedAt) { state.openProfile(article.pubkey) }
                    // バナー画像
                    if (image != null) {
                        Spacer(Modifier.padding(top = DeckSpace.Md))
                        NoteImages(listOf(image))
                    }
                    // 概要（あれば斜体の淡色）
                    if (summary != null) {
                        Spacer(Modifier.padding(top = DeckSpace.Sm))
                        Text(summary, color = DeckColors.Text2, fontSize = DeckType.Body,
                            fontStyle = FontStyle.Italic, lineHeight = 21.sp)
                    }
                    Spacer(Modifier.padding(top = DeckSpace.Sm))
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
            item("body") {
                MarkdownBody(article.content, Modifier.padding(horizontal = DeckSpace.Lg, vertical = DeckSpace.Md))
            }
            item("actions") {
                Column {
                    HorizontalDivider(color = DeckColors.Border)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = DeckSpace.Lg, vertical = DeckSpace.Xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ActionIcon(Icons.AutoMirrored.Outlined.Reply, stringResource(Res.string.article_comment)) {
                            state.replyTo = article; state.showCompose = true
                        }
                        Spacer(Modifier.width(DeckSpace.Xl))
                        ActionIcon(Icons.Outlined.FavoriteBorder, stringResource(Res.string.section_reaction)) {
                            scope.launch { repo?.publishReaction(article) }
                        }
                        Spacer(Modifier.width(DeckSpace.Xl))
                        ActionIcon(Icons.Outlined.AddReaction, stringResource(Res.string.article_emoji_reaction)) { showPicker = true }
                    }
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
            if (comments.isNotEmpty()) {
                item("comments-header") {
                    Text(
                        stringResource(Res.string.article_comments_fmt, comments.size),
                        color = DeckColors.Text2, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong,
                        modifier = Modifier.padding(horizontal = DeckSpace.Lg, vertical = DeckSpace.Sm),
                    )
                }
                items(comments.size, key = { comments[it].note.event.id }) { i ->
                    val entry = comments[i]
                    Column(Modifier.padding(start = DeckSpace.Sm)) {
                        NoteItem(
                            entry.note,
                            onReply = { state.replyTo = entry.note.event; state.showCompose = true },
                            onQuote = { state.quoting = entry.note.event; state.showCompose = true },
                            onAuthorClick = { state.openProfile(it) },
                        )
                        HorizontalDivider(color = DeckColors.Border)
                    }
                }
            }
            item("bottom-space") { Spacer(Modifier.padding(bottom = DeckSpace.Xl)) }
        }
    }

    if (showPicker) {
        ReactionPickerSheet(
            onPick = { content, url ->
                showPicker = false
                scope.launch { repo?.publishReaction(article, content, url) }
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun AuthorRow(pubkey: String, profile: Profile?, publishedAt: Long, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(DeckRadius.Sm)).clickable(onClick = onClick).padding(vertical = DeckSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(profile?.name ?: pubkey, profile?.pictureUrl, size = 28.dp)
        Spacer(Modifier.width(DeckSpace.Sm))
        Text(
            profile?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(12),
            color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        HintText(relativeTime(publishedAt))
    }
}

@Composable
private fun ActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Sm)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = DeckColors.Text2, modifier = Modifier.size(DeckDimens.IconLg))
    }
}

private fun relativeTime(createdAt: Long): String {
    val diff = app.nostrdeck.crypto.currentUnixTime() - createdAt
    return when {
        diff < 10 -> "now"
        diff < 60 -> "${diff}s"
        diff < 3600 -> "${diff / 60}m"
        diff < 86400 -> "${diff / 3600}h"
        diff < 604800 -> "${diff / 86400}d"
        else -> "${diff / 604800}w"
    }
}
