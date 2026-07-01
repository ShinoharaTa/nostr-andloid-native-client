package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import app.nostrdeck.theme.DeckColors

/**
 * アダプティブな list-detail 2ペイン（PublicChat / DM / 設定で共用）。
 *  - Expanded: 左=一覧(固定幅) / 右=詳細 を横並び
 *  - Compact : [showDetail] が true なら詳細、false なら一覧（1ペイン）。
 *              [onBack] を渡すと詳細表示時に上部へ「← 戻る」バーを出す
 *              （システムバックに加え、ヘッダーからも一覧へ戻れる）。
 *
 * Home の Deck（横スクロール複数カラム）はこれとは別レイアウト。
 */
@Composable
fun TwoPane(
    isCompact: Boolean,
    showDetail: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
    listWidth: Int = 320,
    onBack: (() -> Unit)? = null,
    detailTitle: String? = null,
) {
    if (isCompact) {
        Box(Modifier.fillMaxSize()) {
            when {
                !showDetail -> list()
                onBack != null -> Column(Modifier.fillMaxSize()) {
                    DetailBackBar(onBack, detailTitle)
                    Box(Modifier.weight(1f).fillMaxWidth()) { detail() }
                }
                else -> detail()
            }
        }
    } else {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(listWidth.dp).fillMaxHeight()) { list() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(DeckColors.Border))
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        }
    }
}

/** Compact の詳細ペイン上部に出す「← 戻る」バー（一覧へ戻る）。 */
@Composable
private fun DetailBackBar(onBack: () -> Unit, title: String?) {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "戻る", tint = DeckColors.Text,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(10.dp).size(20.dp),
        )
        if (!title.isNullOrBlank()) {
            Spacer(Modifier.width(2.dp))
            Text(
                title, color = DeckColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = DeckColors.Border)
}

/** 詳細ペースが未選択のときのプレースホルダ（Expanded のみ表示される）。 */
@Composable
fun DetailPlaceholder(text: String) {
    Box(Modifier.fillMaxSize().background(DeckColors.Bg), contentAlignment = Alignment.Center) {
        Text(text, color = DeckColors.Text3, fontSize = 13.sp)
    }
}
