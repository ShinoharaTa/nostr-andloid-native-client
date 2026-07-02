package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * [M9-profile] プロフィールカラム：上部にプロフィールカード（アバター/名前/nip05/npub/フォロー）、
 * 下に本人の投稿一覧。著者タップで開く一時カラム。
 */
@Composable
fun ProfileColumn(
    spec: ColumnSpec,
    pubkey: String,
    profile: Profile?,
    isFollowing: Boolean,
    notes: List<NoteUi>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menu: ColumnMenuActions? = null,
    onFollowToggle: () -> Unit = {},
    onReply: (NoteUi) -> Unit = {},
    onQuote: (NoteUi) -> Unit = {},
    onAuthorClick: (String) -> Unit = {},
    onNoteClick: (NoteUi) -> Unit = {},
) {
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = profile?.name?.takeIf { it.isNotBlank() } ?: spec.title,
            subtitle = "プロフィール",
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                ProfileHeaderCard(pubkey, profile, isFollowing, onFollowToggle)
                HorizontalDivider(color = DeckColors.Border)
            }
            items(notes, key = { it.event.id }) { note ->
                NoteItem(
                    note, Modifier.clickable { onNoteClick(note) },
                    onReply = { onReply(note) }, onQuote = { onQuote(note) }, onAuthorClick = onAuthorClick,
                )
            }
        }
    }
}

@Composable
private fun ProfileHeaderCard(
    pubkey: String,
    profile: Profile?,
    isFollowing: Boolean,
    onFollowToggle: () -> Unit,
) {
    val npub = remember(pubkey) { runCatching { Nip19.hexToNpub(pubkey) }.getOrNull() }
    Column(Modifier.fillMaxWidth().background(DeckColors.Surface).padding(DeckSpace.Lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile?.name ?: pubkey, profile?.pictureUrl, Modifier.size(60.dp))
            Spacer(Modifier.width(DeckSpace.Md))
            Column(Modifier.weight(1f)) {
                Text(
                    profile?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(10),
                    color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Name,
                )
                profile?.handle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(DeckSpace.Xs))
                    Nip05Handle(pubkey, it, fontSize = DeckType.Caption)
                }
            }
            FollowButton(isFollowing, onFollowToggle)
        }
        npub?.let {
            Spacer(Modifier.size(DeckSpace.Sm))
            Text(
                it.take(20) + "…" + it.takeLast(6),
                color = DeckColors.Text3, fontSize = DeckType.Label,
            )
        }
    }
}

@Composable
private fun FollowButton(isFollowing: Boolean, onClick: () -> Unit) {
    if (isFollowing) {
        DeckGhostButton("フォロー中", onClick = onClick)
    } else {
        DeckButton("フォロー", onClick = onClick)
    }
}
