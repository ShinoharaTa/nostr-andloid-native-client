package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors

/**
 * 全カラム共通のヘッダ（designs/index.html の .col-head）。
 * pinned で末尾アクションを切替：固定=grip(並べ替え) / 一時=📌固定+✕閉じる。
 */
@Composable
fun ColumnHeader(
    title: String,
    subtitle: String,
    leadingIcon: ImageVector,
    pinned: Boolean,
    iconTint: Color = DeckColors.Accent,
    iconBg: Color = DeckColors.AccentWeak,
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(iconBg),
            contentAlignment = Alignment.Center,
        ) { Icon(leadingIcon, null, tint = iconTint, modifier = Modifier.size(15.dp)) }
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DeckColors.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DeckColors.Text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // pin/close は callback が渡されたときだけ表示（pane では非表示にできる）。
        if (onPin != null) {
            HeaderIcon(Icons.Outlined.PushPin, if (pinned) "固定を解除" else "固定",
                tint = if (pinned) DeckColors.Zap else DeckColors.Text3, onClick = onPin)
        }
        if (pinned && onPin != null) {
            HeaderIcon(Icons.Outlined.DragIndicator, "並べ替え", DeckColors.Text3, onClick = null)
        } else if (onClose != null) {
            HeaderIcon(Icons.Outlined.Close, "閉じる", DeckColors.Text3, onClick = onClose)
        }
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, cd: String, tint: Color, onClick: (() -> Unit)?) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, cd, tint = tint, modifier = Modifier.size(16.dp)) }
}

/** オフライン状態バナー（控えめ・操作はブロックしない）。 */
@Composable
fun OfflineBanner(pendingCount: Int) {
    Box(Modifier.fillMaxWidth().padding(13.dp, 8.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(DeckColors.Zap.copy(alpha = 0.1f)).padding(11.dp, 7.dp)
        ) {
            Text("⚠ オフライン — キャッシュ表示中・$pendingCount 件の投稿を送信待ち",
                color = DeckColors.Zap, fontSize = 11.5.sp)
        }
    }
}
