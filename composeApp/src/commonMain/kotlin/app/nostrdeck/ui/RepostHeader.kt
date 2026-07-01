package app.nostrdeck.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType

/** [M8-repost] NIP-18 リポストのヘッダ「🔁 {name} がリポスト」。モノクロ・控えめ表示。 */
@Composable
fun RepostHeader(name: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.Repeat,
            contentDescription = null,
            tint = DeckColors.Text3,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(DeckSpace.Xs))
        Text(
            "$name がリポスト",
            color = DeckColors.Text3,
            fontSize = DeckType.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
