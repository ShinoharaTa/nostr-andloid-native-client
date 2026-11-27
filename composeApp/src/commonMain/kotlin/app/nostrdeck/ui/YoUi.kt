package app.nostrdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.model.Yo
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.feed_you_yo_fmt
import nostr_deck_client.composeapp.generated.resources.yo_back
import nostr_deck_client.composeapp.generated.resources.yo_backed

/**
 * [#1024] Yo の印（文字の「Yo」）。通知とタイムラインの左の列（種別マーク）に置く。
 * 送り主が `emoji` タグで指定した画像は使わない（SPEC: 受け取る側はその URL を取りに行かない）。
 */
@Composable
internal fun YoMark(modifier: Modifier = Modifier) {
    Text(
        Yo.LABEL, modifier,
        color = DeckColors.Accent, fontSize = DeckType.Label, fontWeight = DeckWeight.Strong, maxLines = 1,
    )
}

/** [#1024] Yo に付ける件数（同じ人から 2 件以上届いているとき「Yo ×3」）。 */
internal fun yoCountLabel(count: Int): String = if (count > 1) "${Yo.LABEL} ×$count" else Yo.LABEL

/**
 * [#1024] 通知の Yo の行の本体: 「Yo（×件数）」と、右に「Yo を返す」。
 * 引用カードは無い（対象は自分のプロフィール）。
 */
@Composable
internal fun YoNoticeBody(n: NotificationUi) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            yoCountLabel(n.yoCount), Modifier.weight(1f),
            color = DeckColors.Text, fontSize = DeckType.Body, fontWeight = DeckWeight.Strong, maxLines = 1,
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        YoBackButton(n.actor.pubkey, replied = n.yoReplied, receivedAt = n.createdAt)
    }
}

/**
 * [#1024] 「Yo を返す」。押したときだけ送る。送っている間は押せず、返したあと（[replied]）は「返しました」にして押せなくする
 * （二重に返さない。相手からまた Yo が届けば、また押せる）。
 * 送る処理は Repository のスコープで続き、結果は App がトーストで知らせる（行はスクロールで消えるので、ここでは待たない）。
 *
 * [replied] は通知の組み直しを待って付くので、送った直後は間が空く。その間にまた押せないよう、このセッションで送った時刻
 * （[app.nostrdeck.data.EventRepository.yoSentAtFlow]）が受け取った Yo（[receivedAt]）以降なら、それも「返しました」にする。
 */
@Composable
internal fun YoBackButton(pubkey: String, replied: Boolean, receivedAt: Long) {
    val repo = LocalRepository.current ?: return
    val sending by remember(repo) { repo.yoSendingFlow() }.collectAsState()
    val sentAt by remember(repo) { repo.yoSentAtFlow() }.collectAsState()
    val done = replied || (sentAt[pubkey] ?: Long.MIN_VALUE) >= receivedAt
    Box(Modifier.heightIn(min = DeckDimens.TouchTargetSm), contentAlignment = Alignment.Center) {
        when {
            pubkey in sending ->
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = DeckColors.Text2)
            done -> HintText(stringResource(Res.string.yo_backed), maxLines = 1)
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
