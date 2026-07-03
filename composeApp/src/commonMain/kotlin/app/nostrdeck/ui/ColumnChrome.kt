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
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragIndicator
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import app.nostrdeck.model.FeedNoticeCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * デッキカラムの ⋯ メニューに載せる操作。ヘッダーの末尾は ⋯ 1つに集約する
 * （📌/grip/✕ は出さない）。
 */
data class ColumnMenuActions(
    val canMoveLeft: Boolean,
    val canMoveRight: Boolean,
    val onMoveLeft: () -> Unit,
    val onMoveRight: () -> Unit,
    /** フィルター再設定（設定を持たないカラムは null → 項目非表示）。 */
    val onEdit: (() -> Unit)?,
    val onDelete: () -> Unit,
    /** コンテンツフィルター: ミュートを表示中か（目アイコン）。null なら項目非表示。 */
    val mutedRevealed: Boolean? = null,
    val onToggleMuted: (() -> Unit)? = null,
    /** フォロー中カラム: 非表示中の通知系カテゴリ集合。null なら項目非表示（フォロー中以外）。 */
    val hiddenCategories: Set<FeedNoticeCategory>? = null,
    val onToggleCategory: ((FeedNoticeCategory) -> Unit)? = null,
)

/**
 * 全カラム共通のヘッダ（designs/index.html の .col-head）。
 * [menu] 非null（=デッキカラム）なら末尾は ⋯ メニューのみ。
 * null の場合は従来どおり onPin/onClose を個別表示（2ペイン詳細の ✕ 等）。
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
    /** デッキカラムの ⋯ メニュー（移動 ◀▶ / フィルター編集 / 削除）。 */
    menu: ColumnMenuActions? = null,
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
        // タイトル+説明のテキストブロックは行高で合計40dp（LineTitle+LineDesc）に固定。
        Column(Modifier.weight(1f)) {
            Text(title, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                lineHeight = DeckType.LineTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DeckColors.Text3, fontSize = DeckType.Label,
                lineHeight = DeckType.LineDesc, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menu != null) {
            // デッキカラム: ⋯ に集約（移動 ◀▶ / フィルター編集 / 削除）。
            ColumnMenuButton(menu)
        } else {
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
}

/** ⋯ ボタンとそのドロップダウン（移動 ｜◀｜▶｜ / フィルターを編集 / カラムを削除）。 */
@Composable
private fun ColumnMenuButton(menu: ColumnMenuActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        HeaderIconButton(Icons.Outlined.MoreHoriz, "カラムメニュー", DeckColors.Text3, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // 移動: 1行に ◀▶ を並べる（端では該当方向を無効化）。
            Row(
                Modifier.padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("移動", color = DeckColors.Text, fontSize = DeckType.Sub)
                Spacer(Modifier.width(DeckSpace.Lg))
                MoveArrow(Icons.Outlined.ChevronLeft, "左へ移動", enabled = menu.canMoveLeft) {
                    menu.onMoveLeft()
                }
                Spacer(Modifier.width(DeckSpace.Sm))
                MoveArrow(Icons.Outlined.ChevronRight, "右へ移動", enabled = menu.canMoveRight) {
                    menu.onMoveRight()
                }
            }
            menu.onEdit?.let { edit ->
                DropdownMenuItem(
                    text = { Text("フィルターを編集") },
                    leadingIcon = { Icon(Icons.Outlined.Tune, null, modifier = Modifier.size(DeckDimens.IconMd)) },
                    onClick = { open = false; edit() },
                )
            }
            // コンテンツフィルター: ミュート中の投稿を一括で表示/非表示（目アイコン）。
            if (menu.mutedRevealed != null && menu.onToggleMuted != null) {
                val revealed = menu.mutedRevealed
                DropdownMenuItem(
                    text = { Text(if (revealed) "ミュートを隠す" else "ミュートを表示") },
                    leadingIcon = {
                        Icon(
                            if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            null, modifier = Modifier.size(DeckDimens.IconMd),
                        )
                    },
                    onClick = { open = false; menu.onToggleMuted.invoke() },
                )
            }
            // フォロー中カラム: 通知系（自分への反応/自分のリアクション）を種別ごとに表示/非表示。
            // タップしてもメニューは閉じない（連続でトグルできるように）。
            if (menu.hiddenCategories != null && menu.onToggleCategory != null) {
                HorizontalDivider(color = DeckColors.Border)
                Text(
                    "タイムラインに混ぜる表示",
                    color = DeckColors.Text3, fontSize = DeckType.Label,
                    modifier = Modifier.padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
                )
                FeedCategoryItem("自分へのリアクション", FeedNoticeCategory.REACTIONS, menu.hiddenCategories, menu.onToggleCategory)
                FeedCategoryItem("自分への返信・メンション", FeedNoticeCategory.REPLIES, menu.hiddenCategories, menu.onToggleCategory)
                FeedCategoryItem("自分へのリポスト", FeedNoticeCategory.REPOSTS, menu.hiddenCategories, menu.onToggleCategory)
                FeedCategoryItem("自分がしたリアクション", FeedNoticeCategory.MY_REACTIONS, menu.hiddenCategories, menu.onToggleCategory)
                HorizontalDivider(color = DeckColors.Border)
            }
            DropdownMenuItem(
                text = { Text("カラムを削除", color = DeckColors.Warn) },
                leadingIcon = { Icon(Icons.Outlined.Close, null, tint = DeckColors.Warn, modifier = Modifier.size(DeckDimens.IconMd)) },
                onClick = { open = false; menu.onDelete() },
            )
        }
    }
}

/** [M18] 通知系カテゴリの表示トグル項目（チェック=表示中）。タップしてもメニューは閉じない。 */
@Composable
private fun FeedCategoryItem(
    label: String,
    category: FeedNoticeCategory,
    hidden: Set<FeedNoticeCategory>,
    onToggle: (FeedNoticeCategory) -> Unit,
) {
    val shown = category !in hidden
    DropdownMenuItem(
        text = { Text(label, color = if (shown) DeckColors.Text else DeckColors.Text3) },
        leadingIcon = {
            Icon(
                if (shown) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, null,
                tint = if (shown) DeckColors.Text else DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconMd),
            )
        },
        onClick = { onToggle(category) },
    )
}

/** メニュー内の ◀/▶（32dp 実タップ領域・Surface2 の面・無効時は沈める）。 */
@Composable
private fun MoveArrow(icon: ImageVector, cd: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(DeckDimens.TouchTargetXs).clip(RoundedCornerShape(DeckRadius.Sm))
            .background(if (enabled) DeckColors.Surface2 else DeckColors.Surface)
            .let { if (enabled) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, cd, tint = if (enabled) DeckColors.Text else DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconLg)) }
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
