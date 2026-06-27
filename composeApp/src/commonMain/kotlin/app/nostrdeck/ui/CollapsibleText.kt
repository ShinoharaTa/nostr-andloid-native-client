package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors

/**
 * [M8-collapse] 長文ノートを折りたたみ、開閉する。
 * トグルは「ただのテキスト」だと押せると分からないため、シェブロン付きの淡い角丸ピル
 * （Surface2 背景 + ▾/▴ アイコン）にして“操作”だと一目で分かる見た目にする。
 */
@Composable
fun CollapsibleText(text: String, modifier: Modifier = Modifier, collapsedMaxLines: Int = 8) {
    var expanded by remember { mutableStateOf(false) }
    // 折りたたみ時に溢れたか。初回(折りたたみ)レイアウトで判定する。
    var isOverflowing by remember { mutableStateOf(false) }

    // URL/nostr:/#タグ をリンク化・短縮した装飾本文。
    val annotated = remember(text) { noteAnnotated(text) }
    Column(modifier) {
        Text(
            annotated,
            color = DeckColors.Text, fontSize = 13.5.sp, lineHeight = 20.sp,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (!expanded) isOverflowing = result.hasVisualOverflow
            },
        )
        if (isOverflowing || expanded) {
            Spacer(Modifier.size(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(DeckColors.Surface2)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            ) {
                Text(
                    if (expanded) "閉じる" else "もっと見る",
                    color = DeckColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.width(3.dp))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = DeckColors.Accent, modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
