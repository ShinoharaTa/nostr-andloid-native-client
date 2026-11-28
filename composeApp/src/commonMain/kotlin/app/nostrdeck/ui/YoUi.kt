package app.nostrdeck.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.model.Yo
import app.nostrdeck.model.YoStyle
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDensity
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.feed_you_yo_fmt
import nostr_deck_client.composeapp.generated.resources.yo_back
import nostr_deck_client.composeapp.generated.resources.yo_backed
import nostr_deck_client.composeapp.generated.resources.yo_received
import nostr_deck_client.composeapp.generated.resources.yo_sending

/**
 * [#1024] Yo の通知の見た目の設定。App が設定 Flow から [apply] する snapshot state（TimeDisplaySetting と同じ作り）。
 * Yo の行と通知の見出しが描画中に読むので、設定を変えるとそこだけが描き直される。
 */
object YoStyleSetting {
    private val state = mutableStateOf(YoStyle.COLOR)
    val style: YoStyle get() = state.value

    /** 値が変わる時のみ代入（DeckWeight.apply と同じ作法）。 */
    fun apply(s: YoStyle) {
        if (state.value != s) state.value = s
    }
}

/**
 * 「Yo!」の文字（nostr-yo のロゴと同じ表記）。受け取った Yo の絵（送り主が付けた絵文字の画像）を読み込めるまで・
 * 読み込めなかったときに、代わりに出す。
 */
private const val YO_WORD = "Yo!"

/** 返したあとの Yo の絵の濃さ（まだ返していない Yo より沈める）。 */
private const val YO_DONE_IMAGE_ALPHA = 0.45f

/** 文字の「Yo!」の傾き（度。左上がり）と、紫のタイルの傾き。 */
private const val YO_WORD_TILT = -6f
private const val YO_TILE_TILT = -8f

/** 届いた瞬間の動き: 「Yo!」が弾む大きさ（+15%）、タイルが揺れる角度（さらに 8 度）、行きと戻りの時間。 */
private const val YO_BOUNCE = 0.15f
private const val YO_WOBBLE = -8f
private const val YO_PULSE_UP_MS = 160
private const val YO_PULSE_DOWN_MS = 260

/** 届いたばかりとみなす秒数。この間に初めて描く行だけ動かす（スクロールで出てきた古い Yo は動かさない）。 */
private const val YO_FRESH_SEC = 15L

/** 反転した面の上の補足の文字（時刻）の濃さ。Text3 のままだと面との差が小さく読みにくい。 */
private const val YO_INVERSE_SUB_ALPHA = 0.7f

/**
 * [#1024] Yo の印（手を振るアイコン）。タイムラインの「自分が送った Yo」の行の左の列に置く。ほかの種別のアイコンと同じ大きさ。
 */
@Composable
internal fun YoMark(modifier: Modifier = Modifier) {
    Icon(Icons.Outlined.WavingHand, null, tint = DeckColors.Accent, modifier = modifier.size(17.dp))
}

/** [#1024] Yo の件数（同じ人から 2 件以上届いているとき「×3」。1 件なら出さない）。 */
internal fun yoCountLabel(count: Int): String? = if (count > 1) "×$count" else null

/** 「Yo!」の文字。太さは Black（Yo だけの太さ。役割のウェイトには無い）。 */
@Composable
private fun YoWord(size: TextUnit, color: Color, modifier: Modifier = Modifier) {
    Text(
        YO_WORD, modifier,
        color = color, fontSize = size, fontWeight = FontWeight.Black, letterSpacing = (-0.02).em,
        maxLines = 1, softWrap = false,
    )
}

/** このセッションで送った Yo の時刻（相手 → created_at）。Repository が無い（プレビュー）なら空。 */
@Composable
private fun yoSentAt(): Map<String, Long> {
    val repo = LocalRepository.current
    val sentAt by remember(repo) { repo?.yoSentAtFlow() ?: MutableStateFlow(emptyMap()) }.collectAsState()
    return sentAt
}

/**
 * 届いた瞬間の動き（0 → 1 → 0 を一度だけ）。届いたばかりの Yo の行を初めて描くときだけ動かす。
 * 値は描画（graphicsLayer）の中で読むこと（行を組み直さずに済む）。
 */
@Composable
private fun rememberYoArrival(n: NotificationUi): Animatable<Float, *> {
    val pulse = remember(n.id) { Animatable(0f) }
    LaunchedEffect(n.id) {
        if (abs(currentUnixTime() - n.createdAt) > YO_FRESH_SEC) return@LaunchedEffect
        pulse.animateTo(1f, tween(YO_PULSE_UP_MS))
        pulse.animateTo(0f, tween(YO_PULSE_DOWN_MS))
    }
    return pulse
}

/**
 * [#1024] 通知の Yo の 1 行。ほかの通知の行（NoticeRow）とは別の骨格で、行そのものが「Yo!」だと分かるようにする。
 * 見た目は設定（[YoStyleSetting]）で選ぶ。
 *  - 紫（既定）: 紫の「Yo を返す」と、行の左端に紫の線
 *  - 白黒（強調）: まだ返していない Yo は、行まるごと白黒を反転した塊にして、絵を大きく置く
 * 左の印は、送り主が Yo に付けた絵文字の画像（[NotificationUi.yoImageUrl]。ほかのカスタム絵文字と同じく画像プロキシ経由で
 * 読み込む）。読み込めるまで・読み込めなかったときは、描いた「Yo!」を出す。
 * 返したあとはどちらも普通の行に戻し、絵を沈めて「✓ 返しました」にする（相手からまた届けば、また目立つ行になる）。
 * 引用カードは無い（対象は自分のプロフィール）。行を押すと相手のプロフィール。
 */
@Composable
internal fun YoNoticeRow(n: NotificationUi, selected: Boolean, onClick: () -> Unit, onActorClick: () -> Unit) {
    val replied = Yo.isReplied(n, yoSentAt())
    val style = YoStyleSetting.style
    when {
        replied -> YoDoneRow(n, style, selected, onClick, onActorClick)
        style == YoStyle.COLOR -> YoColorRow(n, selected, onClick, onActorClick)
        else -> YoBlockRow(n, selected, onClick, onActorClick)
    }
}

/** モノクロ・まだ返していない Yo: 行まるごと反転（文字の色の面に、背景の色の文字）。 */
@Composable
private fun YoBlockRow(n: NotificationUi, selected: Boolean, onClick: () -> Unit, onActorClick: () -> Unit) {
    val fill = DeckColors.Text
    val content = DeckColors.Bg
    val pulse = rememberYoArrival(n)
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                drawRect(fill)
                // [#222] キーボード選択中の印（反転した面の上なので、線も反転の色）
                if (selected) drawRect(color = content, size = Size(3.dp.toPx(), size.height))
            }
            .clickable(onClick = onClick)
            .padding(horizontal = DeckDensity.NotePadX, vertical = DeckDensity.NotePadY),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.widthIn(min = DeckDensity.AvatarSize + DeckSpace.Md), contentAlignment = Alignment.CenterStart) {
            YoImage(
                n.yoImageUrl, size = if (DeckDensity.isDense) DeckDimens.TouchTargetXs else DeckDimens.TouchTarget,
                modifier = Modifier.graphicsLayer {
                    rotationZ = YO_WORD_TILT
                    val scale = 1f + YO_BOUNCE * pulse.value
                    scaleX = scale
                    scaleY = scale
                },
            ) { YoWord(size = if (DeckDensity.isDense) DeckType.YoMd else DeckType.YoLg, color = content) }
        }
        Spacer(Modifier.width(DeckSpace.Md))
        Column(Modifier.weight(1f)) {
            YoHead(n, onActorClick, nameColor = content, timeColor = content.copy(alpha = YO_INVERSE_SUB_ALPHA))
            Spacer(Modifier.size(DeckSpace.Xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                YoCount(n.yoCount, content, Modifier.weight(1f))
                Spacer(Modifier.width(DeckSpace.Sm))
                YoBackButton(
                    n.actor.pubkey, fill = content, content = fill, busyFill = content, busyContent = DeckColors.Text2,
                    weight = DeckWeight.Strong,
                )
            }
        }
    }
}

/** 紫・まだ返していない Yo: 紫のタイルとボタン、左端に紫の線。 */
@Composable
private fun YoColorRow(n: NotificationUi, selected: Boolean, onClick: () -> Unit, onActorClick: () -> Unit) {
    val bar = DeckColors.Yo
    val pulse = rememberYoArrival(n)
    YoPlainRow(selected, onClick, edge = bar, mark = { YoTile(n.yoImageUrl, dimmed = false, pulse = pulse) }) {
        YoHead(n, onActorClick, nameColor = DeckColors.Text, timeColor = DeckColors.Text3)
        Spacer(Modifier.size(DeckDensity.NoteGap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            YoCount(n.yoCount, DeckColors.Text, Modifier.weight(1f))
            Spacer(Modifier.width(DeckSpace.Sm))
            YoBackButton(
                n.actor.pubkey, fill = DeckColors.Yo, content = DeckColors.OnYo,
                busyFill = DeckColors.Surface2, busyContent = DeckColors.Text2, weight = FontWeight.Black,
            )
        }
    }
}

/** 返したあとの Yo（どちらの見た目でも普通の行）: 沈めた「Yo!」、「Yo が届きました」、「✓ 返しました」。 */
@Composable
private fun YoDoneRow(n: NotificationUi, style: YoStyle, selected: Boolean, onClick: () -> Unit, onActorClick: () -> Unit) {
    YoPlainRow(
        selected, onClick, edge = null,
        mark = {
            if (style == YoStyle.COLOR) {
                YoTile(n.yoImageUrl, dimmed = true, pulse = null)
            } else {
                YoImage(
                    n.yoImageUrl, size = DeckDensity.AvatarSize, alpha = YO_DONE_IMAGE_ALPHA,
                    modifier = Modifier.graphicsLayer { rotationZ = YO_WORD_TILT },
                ) { YoWord(size = if (DeckDensity.isDense) DeckType.Body else DeckType.YoMd, color = DeckColors.Text3) }
            }
        },
    ) {
        YoHead(n, onActorClick, nameColor = DeckColors.Text, timeColor = DeckColors.Text3)
        Spacer(Modifier.size(DeckDensity.NoteGap))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val received = stringResource(Res.string.yo_received)
            Text(
                yoCountLabel(n.yoCount)?.let { "$received $it" } ?: received, Modifier.weight(1f),
                color = DeckColors.Text3, fontSize = DeckType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DeckSpace.Sm))
            Icon(Icons.Outlined.Check, null, tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconSm))
            Spacer(Modifier.width(DeckSpace.Xs))
            HintText(stringResource(Res.string.yo_backed), maxLines = 1)
        }
    }
}

/**
 * ほかの通知の行（NoticeRow）と同じ骨格の行: 左に種別マークの列（幅 = アバター）、右に見出しと本体。
 * [edge] があれば左端に線を引く（紫の、まだ返していない Yo）。キーボード選択中は選択の印を優先する。
 */
@Composable
private fun YoPlainRow(
    selected: Boolean,
    onClick: () -> Unit,
    edge: Color?,
    mark: @Composable () -> Unit,
    main: @Composable () -> Unit,
) {
    val selFill = DeckColors.Surface2
    val selBar = DeckColors.Accent
    Row(
        Modifier.fillMaxWidth()
            .drawBehind {
                if (selected) {
                    drawRect(color = selFill)
                    drawRect(color = selBar, size = Size(3.dp.toPx(), size.height))
                } else if (edge != null) {
                    drawRect(color = edge, size = Size(DeckSpace.Xs.toPx(), size.height))
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = DeckDensity.NotePadX, vertical = DeckDensity.NotePadY),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(DeckDensity.AvatarSize), contentAlignment = Alignment.TopCenter) { mark() }
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.weight(1f)) { main() }
    }
}

/** 見出し行: 相手のアバター・名前・時刻（NoticeRow の見出しと同じ並び。色だけ面に合わせる）。 */
@Composable
private fun YoHead(n: NotificationUi, onActorClick: () -> Unit, nameColor: Color, timeColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(n.actor.name, n.actor.pictureUrl, Modifier.clickable(onClick = onActorClick), size = 20.dp)
        Spacer(Modifier.width(DeckSpace.Xs))
        Text(
            n.actor.name, color = nameColor, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onActorClick),
        )
        Spacer(Modifier.width(DeckSpace.Sm))
        HintText(timeLabel(n.createdAt), color = timeColor, maxLines = 1)
    }
}

/** 件数「×3」（1 件なら空。幅だけ取る）。 */
@Composable
private fun YoCount(count: Int, color: Color, modifier: Modifier) {
    Text(
        yoCountLabel(count).orEmpty(), modifier,
        color = color, fontSize = DeckType.Title, fontWeight = FontWeight.Black, maxLines = 1,
    )
}

/**
 * 受け取った Yo の絵（送り主が付けた絵文字の画像。[url]）。[size] の正方形に収める（縦横比はそのまま）。
 * 読み込めるまで・読み込めなかったとき・[url] が無いときは、[fallback]（描いた「Yo!」）を出す。
 * 画像は、ほかのカスタム絵文字と同じ経路（[AnimatedEmoji]。画像プロキシ経由）で読み込む。
 */
@Composable
private fun YoImage(
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    alpha: Float = 1f,
    fallback: @Composable () -> Unit,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (url == null || !EmojiAspect.isLoaded(url)) fallback()
        if (url != null) AnimatedEmoji(url, contentDescription = YO_WORD, modifier = Modifier.size(size).alpha(alpha))
    }
}

/**
 * 種別マークの列に置く Yo の印（幅 = アバター）。送り主の絵文字の画像（[imageUrl]）を出し、読み込めるまでは紫のタイル
 * 「Yo!」を出す。返したあと（[dimmed]）は沈める。[pulse] があれば、届いた瞬間に一度だけ揺らす。
 */
@Composable
private fun YoTile(imageUrl: String?, dimmed: Boolean, pulse: Animatable<Float, *>?) {
    YoImage(
        imageUrl, size = DeckDensity.AvatarSize, alpha = if (dimmed) YO_DONE_IMAGE_ALPHA else 1f,
        modifier = Modifier.graphicsLayer { rotationZ = YO_TILE_TILT + YO_WOBBLE * (pulse?.value ?: 0f) },
    ) {
        Box(
            Modifier.size(DeckDensity.AvatarSize)
                .clip(RoundedCornerShape(DeckRadius.Sm))
                .background(if (dimmed) DeckColors.Surface3 else DeckColors.Yo),
            contentAlignment = Alignment.Center,
        ) {
            YoWord(
                size = if (DeckDensity.isDense) DeckType.Label else DeckType.Title,
                color = if (dimmed) DeckColors.Text2 else DeckColors.OnYo,
            )
        }
    }
}

/**
 * [#1024] 「Yo を返す」。押したときだけ送る。送っている間は「送信中」にして押せなくする（二重に返さない）。
 * 返したあとは行ごと [YoDoneRow] に替わる（相手からまた Yo が届けば、また押せる）。
 * 送る処理は Repository のスコープで続き、結果は App がトーストで知らせる（行はスクロールで消えるので、ここでは待たない）。
 */
@Composable
private fun YoBackButton(
    pubkey: String,
    fill: Color,
    content: Color,
    busyFill: Color,
    busyContent: Color,
    weight: FontWeight,
) {
    val repo = LocalRepository.current ?: return
    val sending by remember(repo) { repo.yoSendingFlow() }.collectAsState()
    val busy = pubkey in sending
    Row(
        Modifier.heightIn(min = DeckDimens.TouchTargetSm)
            .clip(RoundedCornerShape(DeckRadius.Full))
            .background(if (busy) busyFill else fill)
            .clickable(enabled = !busy) { repo.sendYo(pubkey) }
            .padding(horizontal = DeckSpace.Lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(DeckDimens.IconSm), strokeWidth = 2.dp, color = busyContent)
            Spacer(Modifier.width(DeckSpace.Xs))
        }
        Text(
            stringResource(if (busy) Res.string.yo_sending else Res.string.yo_back),
            color = if (busy) busyContent else content, fontSize = DeckType.Body, fontWeight = weight, maxLines = 1,
        )
    }
}

/**
 * [#1024] 通知の見出しに出す「Yo! 件数」（まだ返していない Yo の相手の数）。Yo の表示が「紫」のときだけ出す。
 * 0 件なら出さない。
 */
@Composable
internal fun YoHeaderPill(items: List<NotificationUi>) {
    if (YoStyleSetting.style != YoStyle.COLOR) return
    val count = Yo.unrepliedCount(items, yoSentAt())
    if (count == 0) return
    Box(
        Modifier.heightIn(min = DeckDimens.TouchTargetXs - DeckSpace.Xs)
            .clip(RoundedCornerShape(DeckRadius.Full))
            .background(DeckColors.Yo)
            .padding(horizontal = DeckSpace.Sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "$YO_WORD $count", color = DeckColors.OnYo, fontSize = DeckType.Caption, fontWeight = FontWeight.Black,
            maxLines = 1,
        )
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
        // 左の印は、そのとき付けた絵文字の画像（読み込めるまでは手を振るアイコン）
        Box(Modifier.width(DeckDimens.AvatarSize), contentAlignment = Alignment.Center) {
            YoImage(entry.imageUrl, size = DeckDimens.IconLg) { YoMark() }
        }
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
