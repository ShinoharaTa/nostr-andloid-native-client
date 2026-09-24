package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.Channel
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * CHANNEL_LIST レンダラー：NIP-28 チャンネル一覧（スレッド一覧）。
 * 行タップでルームを開き、📌でレールにピン留め。
 */
@Composable
fun ChannelListColumn(
    spec: ColumnSpec,
    channels: List<Channel>,
    pinnedChannelIds: Set<String>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menu: ColumnMenuActions? = null,
    onChannelClick: (Channel) -> Unit = {},
    onPinChannel: (Channel) -> Unit = {},
    // [#291] スレッド作成（非null で先頭に「＋作成」行）と、自分が作成したスレッドの編集。
    onCreateChannel: ((name: String, about: String, picture: String?) -> Unit)? = null,
    ownedChannelIds: Set<String> = emptySet(),
    onEditChannel: ((channelId: String, name: String, about: String, picture: String?) -> Unit)? = null,
    /** [#422] 非null なら見出しの代わりに描く（メッセージ画面の「DM | チャット」切り替え）。 */
    header: (@Composable () -> Unit)? = null,
) {
    var showCreate by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Channel?>(null) }
    Column(modifier.background(DeckColors.Surface)) {
        if (header != null) header() else ColumnHeader(
            title = spec.title, subtitle = columnSubtitleFor(spec),
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            if (onCreateChannel != null) {
                item(key = "create_channel") {
                    CreateChannelRow { showCreate = true }
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
            items(channels, key = { it.id }) { ch ->
                ChannelRow(
                    ch, ch.id in pinnedChannelIds,
                    onClick = { onChannelClick(ch) }, onPin = { onPinChannel(ch) },
                    onEdit = if (onEditChannel != null && ch.id in ownedChannelIds) ({ editing = ch }) else null,
                )
            }
        }
    }
    if (showCreate && onCreateChannel != null) {
        ChannelEditSheet(
            onSubmit = { name, about, picture -> showCreate = false; onCreateChannel(name, about, picture) },
            onDismiss = { showCreate = false },
        )
    }
    editing?.let { ch ->
        ChannelEditSheet(
            initialName = ch.name, initialAbout = ch.about, initialPicture = ch.pictureUrl,
            isEdit = true,
            onSubmit = { name, about, picture -> editing = null; onEditChannel?.invoke(ch.id, name, about, picture) },
            onDismiss = { editing = null },
        )
    }
}

/** [#291] 一覧先頭の「＋ 新しいスレッドを作成」行。 */
@Composable
private fun CreateChannelRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, null, tint = DeckColors.Accent, modifier = Modifier.size(DeckDimens.IconMd))
        Spacer(Modifier.width(DeckSpace.Md))
        Text(
            stringResource(Res.string.channel_create_row),
            color = DeckColors.Accent, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
        )
    }
}

@Composable
private fun ChannelRow(
    ch: Channel, pinned: Boolean, onClick: () -> Unit, onPin: () -> Unit,
    onEdit: (() -> Unit)? = null,   // [#291] 自分が作成したスレッドのみ非null
) {
    Row(
        // [#123] 行高は固定（AvatarSize + 上下Md = 62dp）。説明の有無に関わらず全項目同じ高さ。
        // 以前は上下Sm(=54dp)でアイコン周りの余白が薄く、行とピンの誤タップが起きやすかった。
        Modifier.fillMaxWidth().height(DeckDimens.AvatarSize + DeckSpace.Md * 2)
            .clickable(onClick = onClick).padding(horizontal = DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(DeckDimens.AvatarSize).clip(RoundedCornerShape(DeckRadius.Md)).background(DeckColors.Surface3)) {
            AvatarSquare(ch.name, ch.pictureUrl)
        }
        Spacer(Modifier.width(DeckSpace.Md))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ch.name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                    lineHeight = DeckType.LineTitle,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                // メンバー数はエンドポイントに無いので、判っている場合のみ表示。
                if (ch.members > 0) {
                    Spacer(Modifier.width(DeckSpace.Xs))
                    HintText("👤 ${ch.members}")
                }
            }
            // 直近メッセージがあればそれを、無ければ概要(about)を副題に。両方空なら省略。
            val secondary = when {
                ch.lastMessage.isNotBlank() -> "${ch.lastMessageBy}: ${ch.lastMessage}"
                ch.about.isNotBlank() -> ch.about
                else -> null
            }
            if (secondary != null) {
                // タイトル+説明の段差は行高（LineTitle/LineDesc）で統一（ColumnHeader と同一）。
                Text(secondary, color = DeckColors.Text2, fontSize = DeckType.Caption,
                    lineHeight = DeckType.LineDesc,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (ch.unread > 0) {
            Spacer(Modifier.width(DeckSpace.Sm))
            Box(
                Modifier.clip(CircleShape).background(DeckColors.Accent).padding(horizontal = DeckSpace.Xs, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) { Text("${ch.unread}", color = DeckColors.Bg, fontSize = DeckType.Micro, fontWeight = DeckWeight.Name) }
        }
        // [#291] 自分が作成したスレッドは ✏️ で編集（kind:41）。
        if (onEdit != null) {
            Box(
                Modifier.padding(start = DeckSpace.Sm).size(DeckDimens.TouchTargetSm)
                    .clip(RoundedCornerShape(DeckRadius.Sm)).clickable(onClick = onEdit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Edit, stringResource(Res.string.channel_edit_title),
                    tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconSm))
            }
        }
        // [#123] 行内ピン。行タップとの誤タップを防ぐため、タップ領域を 40dp(TouchTargetSm) に
        // 広げ、本文との間隔も Sm に拡大して分離を明確にする。
        Box(
            Modifier.padding(start = DeckSpace.Sm).size(DeckDimens.TouchTargetSm)
                .clip(RoundedCornerShape(DeckRadius.Sm)).clickable(onClick = onPin),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.PushPin, stringResource(Res.string.channel_pin),
                tint = if (pinned) DeckColors.Zap else DeckColors.Text3,
                modifier = Modifier.size(DeckDimens.IconSm))
        }
    }
}
