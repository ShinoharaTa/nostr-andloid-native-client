package app.nostrdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors

/** [M8-collapse] 長文ノートを折りたたみ、もっと見る/閉じるで開閉する。 */
@Composable
fun CollapsibleText(text: String, modifier: Modifier = Modifier, collapsedMaxLines: Int = 8) {
    var expanded by remember { mutableStateOf(false) }
    // 折りたたみ時に溢れたか。初回(折りたたみ)レイアウトで判定する
    var isOverflowing by remember { mutableStateOf(false) }

    Column(modifier) {
        Text(
            text,
            color = DeckColors.Text, fontSize = 13.5.sp, lineHeight = 20.sp,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                // 溢れ判定は折りたたみ中だけ更新（展開時は常に溢れない）
                if (!expanded) isOverflowing = result.hasVisualOverflow
            },
        )
        if (isOverflowing || expanded) {
            Text(
                if (expanded) "閉じる" else "もっと見る",
                color = DeckColors.Accent, fontSize = 12.sp,
                modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp),
            )
        }
    }
}
