package app.nostrdeck.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * [#182] 役割ベースの共通テキスト。同じ意味のテキストで fontSize/color/weight がばらつくのを防ぐ。
 *
 * 各コンポーネントの既定は現行の最頻パターンに一致させており（下記コメントの出現数は 2026-07 時点）、
 * 置き換えても**見た目は変わらない**。色・行数など必要な差分だけ引数で上書きする。
 * 見た目そのものを調整するのは別軸（#178 のサイズ調整）で行う。
 *
 * 役割が weight レベルで一意に定まる頻出パターンのみを用意する（Sub+Text のように用途が
 * 著者名/小見出し/本文小に分かれるものは、意味が確定しないため個別指定のまま残す）。
 */

/** 画面/カラム/ダイアログの見出し（Title + Text + Strong）。 */
@Composable
fun TitleText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DeckColors.Text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) = Text(
    text, modifier, color = color, fontSize = DeckType.Title,
    fontWeight = DeckWeight.Strong, maxLines = maxLines, overflow = overflow,
)

/** 設定などのセクション見出し（Caption + Text2）。出現 34。 */
@Composable
fun SectionCaption(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DeckColors.Text2,
) = Text(text, modifier, color = color, fontSize = DeckType.Caption)

/** 補足/説明の小テキスト（Label + Text3）。出現 70（最頻）。時刻/ハンドル/ラベルもこれ。 */
@Composable
fun HintText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = DeckColors.Text3,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) = Text(
    text, modifier, color = color, fontSize = DeckType.Label,
    maxLines = maxLines, overflow = overflow,
)
