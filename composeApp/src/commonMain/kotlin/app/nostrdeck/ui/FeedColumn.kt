package app.nostrdeck.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragIndicator
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
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors

/**
 * Deck の1カラム。ヘッダ + 独立スクロールの LazyColumn。
 *
 * 重要(whiteboard): スクロール位置は呼び出し側が [listState] を hoist して渡す。
 * 折り↔展開のコンフィグ変更で Activity が再生成されても状態を失わないよう、
 * 本来は ViewModel 側にカラムIDキーで保持する。
 */
@Composable
fun FeedColumn(
    spec: ColumnSpec,
    notes: List<NoteUi>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    offline: Boolean = false,
) {
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(spec)
        HorizontalDivider(color = DeckColors.Border)
        Box(Modifier.fillMaxSize()) {
            LazyColumn(state = listState) {
                if (offline) item { OfflineBanner() }
                items(notes, key = { it.event.id }) { note ->
                    NoteItem(note)
                    HorizontalDivider(color = DeckColors.Border)
                }
            }
        }
    }
}

@Composable
private fun ColumnHeader(spec: ColumnSpec) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(DeckColors.AccentWeak),
            contentAlignment = Alignment.Center,
        ) { Text(spec.title.take(1), color = DeckColors.Accent, fontSize = 13.sp) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(spec.title, color = DeckColors.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(spec.subtitle, color = DeckColors.Text3, fontSize = 11.sp)
        }
        Icon(Icons.Outlined.DragIndicator, contentDescription = "並べ替え",
            tint = DeckColors.Text3, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun OfflineBanner() {
    Box(Modifier.fillMaxWidth().padding(13.dp, 8.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(DeckColors.Zap.copy(alpha = 0.1f)).padding(11.dp, 7.dp)
        ) {
            Text("⚠ オフライン — キャッシュ表示中・3件の投稿を送信待ち",
                color = DeckColors.Zap, fontSize = 11.5.sp)
        }
    }
    Spacer(Modifier.height(0.dp))
}
