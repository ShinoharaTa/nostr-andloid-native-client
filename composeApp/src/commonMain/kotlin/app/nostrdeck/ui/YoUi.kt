package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.model.Yo
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.flow.MutableStateFlow
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.feed_you_yo_fmt
import nostr_deck_client.composeapp.generated.resources.yo_back
import nostr_deck_client.composeapp.generated.resources.yo_backed

/** [#1024] スタンプの文字（nostr-yo のロゴと同じ「Yo!」）と、傾き（度。左上がり）。 */
private const val YO_STAMP_TEXT = "Yo!"
private const val YO_STAMP_TILT = -6f

/**
 * [#1024] Yo の印（手を振るアイコン）。通知とタイムラインの左の列（種別マーク）に置く。ほかの種別のアイコンと同じ大きさ。
 */
@Composable
internal fun YoMark(modifier: Modifier = Modifier) {
    Icon(Icons.Outlined.WavingHand, null, tint = DeckColors.Accent, modifier = modifier.size(17.dp))
}

/**
 * [#1024] 「Yo!」のスタンプ（受け取った Yo そのもの。リアクションの行の絵文字にあたる）。主ボタンと同じ塗り（文字の色の面に
 * 背景の色の文字）を少し傾けて置く。返したあと（[dimmed]）は面を沈めて、まだ返していない Yo と見分けられるようにする。
 * 送り主が `emoji` タグで指定した画像は使わない（SPEC: 受け取る側はその URL を取りに行かない）。
 */
@Composable
internal fun YoStamp(modifier: Modifier = Modifier, dimmed: Boolean = false) {
    Box(
        modifier
            .graphicsLayer { rotationZ = YO_STAMP_TILT }
            .clip(RoundedCornerShape(DeckRadius.Sm))
            .background(if (dimmed) DeckColors.Surface3 else DeckColors.Text)
            .padding(horizontal = DeckSpace.Sm, vertical = DeckSpace.Xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            YO_STAMP_TEXT,
            color = if (dimmed) DeckColors.Text2 else DeckColors.Bg,
            fontSize = DeckType.Title, fontWeight = FontWeight.Black, maxLines = 1,
        )
    }
}

/** [#1024] Yo の件数（同じ人から 2 件以上届いているとき「×3」。1 件なら出さない）。 */
internal fun yoCountLabel(count: Int): String? = if (count > 1) "×$count" else null

/**
 * [#1024] 通知の Yo の行の本体: 左に「Yo!」のスタンプ（と件数）、右に「Yo を返す」。
 * 引用カードは無い（対象は自分のプロフィール）。
 */
@Composable
internal fun YoNoticeBody(n: NotificationUi) {
    val repo = LocalRepository.current
    val sentAt by remember(repo) { repo?.yoSentAtFlow() ?: MutableStateFlow(emptyMap<String, Long>()) }.collectAsState()
    val replied = n.yoReplied || (sentAt[n.actor.pubkey] ?: Long.MIN_VALUE) >= n.createdAt
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        YoStamp(dimmed = replied)
        yoCountLabel(n.yoCount)?.let { count ->
            Spacer(Modifier.width(DeckSpace.Sm))
            Text(count, color = DeckColors.Text2, fontSize = DeckType.Body, fontWeight = DeckWeight.Name, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        YoBackButton(n.actor.pubkey, replied = replied)
    }
}

/**
 * [#1024] 「Yo を返す」。押したときだけ送る。送っている間は押せず、返したあと（[replied]）は「✓ 返しました」にして押せなくする
 * （二重に返さない。相手からまた Yo が届けば、また押せる）。
 * 送る処理は Repository のスコープで続き、結果は App がトーストで知らせる（行はスクロールで消えるので、ここでは待たない）。
 *
 * [replied] は呼ぶ側（[YoNoticeBody]）が決める。通知の組み直しを待つと送った直後に間が空くので、このセッションで送った時刻
 * （[app.nostrdeck.data.EventRepository.yoSentAtFlow]）が受け取った Yo 以降なら、それも返したものとして渡す。
 */
@Composable
internal fun YoBackButton(pubkey: String, replied: Boolean) {
    val repo = LocalRepository.current ?: return
    val sending by remember(repo) { repo.yoSendingFlow() }.collectAsState()
    Box(Modifier.heightIn(min = DeckDimens.TouchTargetSm), contentAlignment = Alignment.Center) {
        when {
            pubkey in sending ->
                CircularProgressIndicator(Modifier.size(DeckDimens.IconMd), strokeWidth = 2.dp, color = DeckColors.Text2)
            replied -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Check, null, tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconSm))
                Spacer(Modifier.width(DeckSpace.Xs))
                HintText(stringResource(Res.string.yo_backed), maxLines = 1)
            }
            else -> DeckGhostButton(stringResource(Res.string.yo_back), onClick = { repo.sendYo(pubkey) })
        }
    }
}

/**
 * [#1024] 自分が送った Yo の行（タイムラインの「自分がしたリアクション」・ふぁぼ欄）: 「(アイコン) 〇〇 さんに Yo」。
 * 対象は相手のプロフィール（kind:0）なので、投稿としては出さない。押すと相手のプロフィールを開く。
 */
@Composable
internal fun MyYoRow(entry: FeedEntry.MyYo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(DeckSpace.Md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(DeckDimens.AvatarSize), contentAlignment = Alignment.Center) { YoMark() }
        Spacer(Modifier.width(DeckSpace.Sm))
        Avatar(seed = entry.to.pubkey, pictureUrl = entry.to.pictureUrl, size = 16.dp)
        Spacer(Modifier.width(DeckSpace.Xs))
        Text(
            stringResource(Res.string.feed_you_yo_fmt, entry.to.name), Modifier.weight(1f),
            color = DeckColors.Text2, fontSize = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        HintText(timeLabel(entry.sentAt))
    }
}
