package app.nostrdeck.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.NoteUi
import app.nostrdeck.theme.DeckColors

/** [M8-repost] 引用リポスト（NIP-18 q タグ）の埋め込みカード。著者名 + 切り詰めた本文を枠内に。モノクロ。 */
@Composable
fun QuotedNoteCard(note: NoteUi, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .border(1.dp, DeckColors.Border, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Text(
            note.author.name,
            color = DeckColors.Text2,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(3.dp))
        // 本文と同様に nostr:nevent/npub・URL・#タグを短縮装飾する。素の Text だと
        // 引用元本文に含まれる生の nostr:nevent1… が全長のまま表示されてしまう。
        val names = LocalProfileNames.current
        val body = note.text ?: note.event.content
        val annotated = remember(body, names) { noteAnnotated(body, { names[it] }) }
        Text(
            annotated,
            color = DeckColors.Text2,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
