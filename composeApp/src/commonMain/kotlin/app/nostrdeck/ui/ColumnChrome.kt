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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

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
    /** 非null なら先頭アイコン位置に「←」戻る矢印を出す（単体画面の確実な戻り導線）。 */
    onBack: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().background(DeckColors.Surface)
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 先頭は常に 40dp の固定スロット（←戻る / タイトルアイコンのどちらでも）。
        // 戻ると同じ「素の 20dp グリフ」で描き、チップ下地を廃止して視覚差をなくす。
        if (onBack != null) {
            HeaderBackButton(onClick = onBack)
        } else {
            Box(Modifier.size(DeckDimens.TouchTargetSm), contentAlignment = Alignment.Center) {
                Icon(leadingIcon, null, tint = iconTint, modifier = Modifier.size(DeckDimens.IconLg))
            }
        }
        Spacer(Modifier.width(DeckSpace.Sm))
        // タイトル+説明のテキストブロックは行高で合計40dp（22+18）に固定し、隙間を詰める。
        Column(Modifier.weight(1f)) {
            Text(title, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                lineHeight = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DeckColors.Text3, fontSize = DeckType.Label,
                lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // pin/close は callback が渡されたときだけ表示（pane では非表示にできる）。
        if (onPin != null) {
            HeaderIconButton(Icons.Outlined.PushPin, if (pinned) "固定を解除" else "固定",
                tint = if (pinned) DeckColors.Zap else DeckColors.Text3, onClick = onPin)
        }
        if (pinned && onPin != null) {
            HeaderIconButton(Icons.Outlined.DragIndicator, "並べ替え", DeckColors.Text3, onClick = null)
        } else if (onClose != null) {
            HeaderIconButton(Icons.Outlined.Close, "閉じる", DeckColors.Text3, onClick = onClose)
        }
    }
}

/**
 * ヘッダー共通の「←戻る」。**全画面この1実装に統一**（画面ごとの独自実装は禁止）。
 * 2ペイン/単体画面のどちらのヘッダーでもそのまま置ける（40dp 実タップ領域 + IconLg）。
 */
@Composable
fun HeaderBackButton(onClick: () -> Unit, tint: Color = DeckColors.Text) {
    Box(
        Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Sm)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "戻る", tint = tint, modifier = Modifier.size(DeckDimens.IconLg)) }
}

/** ヘッダー共通のアイコンアクション（閉じる/ピン/並べ替え等）。40dp 実タップ領域 + IconSm。 */
@Composable
fun HeaderIconButton(icon: ImageVector, cd: String, tint: Color, onClick: (() -> Unit)?) {
    Box(
        Modifier.size(DeckDimens.TouchTargetSm).clip(RoundedCornerShape(DeckRadius.Sm))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, cd, tint = tint, modifier = Modifier.size(DeckDimens.IconSm)) }
}

/** オフライン状態バナー（控えめ・操作はブロックしない）。 */
@Composable
fun OfflineBanner(pendingCount: Int) {
    Box(Modifier.fillMaxWidth().padding(DeckSpace.Md, DeckSpace.Sm)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Sm))
                .background(DeckColors.Zap.copy(alpha = 0.1f)).padding(DeckSpace.Md, DeckSpace.Sm)
        ) {
            Text("⚠ オフライン — キャッシュ表示中・$pendingCount 件の投稿を送信待ち",
                color = DeckColors.Zap, fontSize = DeckType.Label)
        }
    }
}
