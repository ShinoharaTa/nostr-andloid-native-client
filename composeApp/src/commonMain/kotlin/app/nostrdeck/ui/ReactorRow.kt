package app.nostrdeck.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReactorLayout
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType

/** [#902] アバターの大きさ・「+N」に空ける幅・並べる上限。 */
private val ReactorAvatarSize = 20.dp
private val ReactorMoreWidth = 32.dp
private const val ReactorAvatarCap = 12

/**
 * [#254] 反応1種の行: 先頭アイコン + 件数ラベル + した人のアバター列（多すぎる分は +N）。
 * 投稿詳細(FocusNoteStats)で使う。
 *
 * [#902] 横幅に収める。構成は [先頭（伸び縮み）] [件数（縮まない）] [アバター（残りの幅）]。
 *  - 先頭の枠は最小 22dp・最大は行幅の 50%。中身は [ReactorEmoji]（横長のカスタム絵文字・文字数のあるリアクション）
 *  - 件数は 1 行で縮めない
 *  - アバターは 20dp・間隔 4dp で、残りの幅に入る分だけ（上限 12）。入りきらなければ「+N」の 32dp を空けて減らす
 *    （[ReactorLayout.avatarSlots]）。以前は 12 個を固定で並べていたので、S カラムでは +N ごと切れて見えなかった
 */
@Composable
internal fun ReactorRow(
    leading: @Composable () -> Unit,
    label: String,
    people: List<Profile>,
    onAuthorClick: ((String) -> Unit)? = null,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = DeckSpace.Xs)) {
        val rowWidth = maxWidth
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.widthIn(min = 22.dp, max = rowWidth * 0.5f), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(DeckSpace.Xs))
            Text(label, color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(DeckSpace.Sm))
            BoxWithConstraints(Modifier.weight(1f)) {
                // 実際に描く大きさ（px に丸めた値）で数える。丸め前の値だと、ちょうど入る幅で 1 個ずれることがある。
                val density = LocalDensity.current
                val slots = with(density) {
                    ReactorLayout.avatarSlots(
                        widthPx = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY,
                        count = people.size,
                        avatarPx = ReactorAvatarSize.roundToPx().toFloat(),
                        gapPx = DeckSpace.Xs.roundToPx().toFloat(),
                        morePx = ReactorMoreWidth.roundToPx().toFloat(),
                        cap = ReactorAvatarCap,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    people.take(slots.shown).forEach { p ->
                        Avatar(
                            seed = p.pubkey, pictureUrl = p.pictureUrl, size = ReactorAvatarSize,
                            modifier = if (onAuthorClick != null) Modifier.clickable { onAuthorClick(p.pubkey) } else Modifier,
                        )
                    }
                    if (slots.more > 0) {
                        Text(
                            ReactorLayout.formatMore(slots.more), color = DeckColors.Text3, fontSize = DeckType.Label,
                            maxLines = 1, softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * [#902] ReactorRow の先頭に出すリアクションの絵文字。親（ReactorRow の先頭の枠）の最大幅に収める。
 *  - カスタム絵文字は高さ Sub 固定・幅は縦横比どおり（[emojiSize]）。枠の最大幅を超えると幅が枠に合わせて縮み、
 *    画像は ContentScale.Fit で縦横比を保ったまま縮む（幅いっぱいなので左端から描かれる）
 *  - 絵文字だけ / 1〜2 文字は 1 行で省略。文字数のあるもの（[isWordyReaction]）は 2 行まで折り返して省略
 *    （以前は 22dp の枠に詰めていたので、1〜2 文字ずつ縦に折り返していた）
 */
@Composable
internal fun ReactorEmoji(display: String, imageUrl: String?) {
    if (imageUrl != null) {
        // [#308] 文字側(Sub)と同じトークンから引く。[#861] 高さ固定・幅は縦横比どおり。
        AnimatedEmoji(imageUrl, contentDescription = display, modifier = Modifier.emojiSize(imageUrl, DeckType.Sub.asEmojiSize()))
    } else {
        Text(
            display, color = DeckColors.Text, fontSize = DeckType.Sub,
            maxLines = if (isWordyReaction(display)) 2 else 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
