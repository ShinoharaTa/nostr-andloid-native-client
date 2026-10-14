package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.theme.DeckColors
import kotlin.math.roundToInt
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * [M8-collapse] 長文ノートを折りたたみ、開閉する。
 * トグルは「ただのテキスト」だと押せると分からないため、シェブロン付きの淡い角丸ピル
 * （Surface2 背景 + ▾/▴ アイコン）にして“操作”だと一目で分かる見た目にする。
 */
@Composable
fun CollapsibleText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 8,
    emojis: Map<String, String> = emptyMap(),
    color: Color = DeckColors.Text,
    linkColor: Color = DeckColors.Accent,
    // [#378] 本文の著者 pubkey（hex）。にゃいず（オフ/自分のみ/全員）の判定に使う。表示専用。
    authorPubkey: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    // 折りたたみ時に溢れたか。初回(折りたたみ)レイアウトで判定する。
    var isOverflowing by remember { mutableStateOf(false) }

    // URL/nostr:/#タグ をリンク化・短縮した装飾本文。@npub… は表示名に解決、:emoji: は画像化。
    // nav があれば @→プロフィール/#→カラム/note→スレッド をタップで開ける。
    val names = LocalProfileNames.current
    val nav = LocalNoteNav.current
    // [#378] にゃいず判定は remember の外で読む（snapshot state の変化で再計算させるため key にも入れる）。
    val nyaize = Nyan.appliesTo(authorPubkey)
    val annotated = remember(text, names, emojis, nav, linkColor, nyaize) {
        noteAnnotated(text, { names[it] }, emojis, nav, linkColor = linkColor, nyaize = nyaize)
    }
    // NIP-30 カスタム絵文字のインライン描画（shortcode→画像）。
    // [#861] 高さは固定、幅は画像の縦横比どおり（EmojiAspect。読み込めた時点で記録され、ここが組み直される）。
    // 大きさは画面の画素数で整数に丸める。端数（例: 2 倍の画面で 41.5px）だと、隣り合う絵文字の間に
    // 半端な隙間が残り、分割した絵文字で作った絵に細い線が入る。
    val density = LocalDensity.current
    val ratios = emojis.values.map { EmojiAspect.of(it) }
    val inline = remember(emojis, ratios, density) {
        val heightPx = with(density) { INLINE_EMOJI_HEIGHT.toPx() }.roundToInt().coerceAtLeast(1)
        emojis.entries.associate { (code, url) ->
            val widthPx = (heightPx * EmojiAspect.of(url)).roundToInt().coerceAtLeast(1)
            "emoji:$code" to InlineTextContent(
                Placeholder(
                    width = with(density) { widthPx.toSp() },
                    height = with(density) { heightPx.toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                ),
            ) {
                // [#277] iOS/Desktop でも GIF/アニメ WebP を動かすため共通コンポーネントへ。
                // [#308] 大きさは Placeholder が決める。以前は 20dp 固定だったため、
                // 文字サイズ設定を上げると枠だけ広がって絵文字が中で浮いていた。
                AnimatedEmoji(url, contentDescription = ":$code:", modifier = Modifier.fillMaxSize())
            }
        }
    }
    Column(modifier) {
        Text(
            annotated,
            // [#327] 本文ウェイトはトークンから。太字ON時に Medium へ上がる。
            color = color, fontSize = DeckType.Body, fontWeight = DeckWeight.Body, lineHeight = BODY_LINE_HEIGHT,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            inlineContent = inline,
            onTextLayout = { result ->
                if (!expanded) isOverflowing = result.hasVisualOverflow
            },
        )
        if (isOverflowing || expanded) {
            Spacer(Modifier.size(DeckSpace.Xs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(DeckRadius.Full))
                    .background(DeckColors.Surface2)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
            ) {
                Text(
                    if (expanded) stringResource(Res.string.common_close) else stringResource(Res.string.show_more),
                    color = DeckColors.Accent, fontSize = DeckType.Caption, fontWeight = DeckWeight.Link,
                )
                Spacer(Modifier.width(DeckSpace.Xs))
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null, tint = DeckColors.Accent, modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 本文の行の高さ。 */
private val BODY_LINE_HEIGHT = 20.sp

/**
 * [#861] 本文に混ざるカスタム絵文字の高さ。行の高さよりわずかに大きくして、絵文字だけの行を縦に並べたとき
 * （分割した絵文字で 1 枚の絵を作る投稿など）に行の間へ隙間が出ないようにする。
 * 以前は 1.4em（19.6sp）で行（20sp）より小さく、画素への丸めで行の間に 1px ほどの線が出ていた。
 * Android は行の高さを固定したまま絵文字が 1px 未満はみ出して重なり、iOS / Desktop は絵文字の行だけわずかに高くなる。
 */
private val INLINE_EMOJI_HEIGHT = 20.75.sp
