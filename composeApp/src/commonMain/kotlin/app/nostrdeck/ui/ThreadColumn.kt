package app.nostrdeck.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.ThreadEntry
import app.nostrdeck.theme.DeckColors

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
) {
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = spec.subtitle,
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            iconTint = DeckColors.Repost, iconBg = DeckColors.Repost.copy(alpha = 0.14f),
            onPin = onPin, onClose = onClose,
        )
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(entries, key = { it.note.event.id }) { entry ->
                ThreadRow(entry)
                HorizontalDivider(color = DeckColors.Border)
            }
        }
        ReplyBox()
    }
}

@Composable
private fun ThreadRow(entry: ThreadEntry) {
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
                "返信先 @${entry.replyToName}", color = DeckColors.Text3, fontSize = 11.sp,
                modifier = Modifier.padding(start = 13.dp, top = 8.dp),
            )
        }
        NoteItem(entry.note)
    }
}

@Composable
private fun ReplyBox() {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface).padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "返信を書く…", color = DeckColors.Text3, fontSize = 12.5.sp,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp))
                .background(DeckColors.Surface2).padding(horizontal = 14.dp, vertical = 9.dp),
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.Send, "送信", tint = DeckColors.Accent,
            modifier = Modifier.padding(6.dp))
    }
}
