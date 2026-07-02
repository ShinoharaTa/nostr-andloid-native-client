package app.nostrdeck.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Deck のレイアウト幾何 + コンポーネント寸法。tokens.css と 1対1（原則6.3）。 */
object DeckDimens {
    val ColumnWidth = 340.dp   // 固定カラム幅。はみ出しは横スクロール
    val HingeGutter = 22.dp    // フォルダブルのヒンジ回避ガター
    val AvatarSize  = 38.dp

    // タッチ領域: 原則8は48dp。Deck 密度優先で実用最小は 40dp(T3採用・実領域で確保)。
    // Xs=32dp はインラインの補助操作（返信キャンセル/添付削除/行内ピン等）の下限。
    val TouchTarget   = 48.dp
    val TouchTargetSm = 40.dp
    val TouchTargetXs = 32.dp

    // アイコン実寸（タッチ領域の中に置くグリフ）。
    val IconSm = 16.dp
    val IconMd = 18.dp
    val IconLg = 20.dp

    // 左レール（Deck 展開時の常設ナビ）。項目は全て RailSlot(=RailItem) の同一寸法で統一。
    val RailWidth = 72.dp   // レール幅
    val RailItem  = 48.dp   // 各項目のタップ領域（=推奨48dp）
    val RailIcon  = 24.dp   // レールのグリフ実寸
    val RailMark  = 40.dp   // ロゴ/アバター等の識別マーク（グリフより少し大きい）
}

/**
 * タイプスケール（Option C: ハイブリッド）。近接値を7段に集約し、半端値(12.5/13.5/11.5)を廃止。
 * sp 指定なのでフォント拡大設定に追従。tokens.css の --type-* と 1対1。
 */
object DeckType {
    val Display = 20.sp   // ダイアログ見出し等
    val Title   = 15.sp   // 画面/カラムのタイトル
    val Body    = 14.sp   // 本文（ノート/チャット）
    val Sub     = 13.sp   // 著者名/小見出し
    val Caption = 12.sp   // 補足テキスト
    val Label   = 11.sp   // 時刻/ハンドル/ラベル
    val Micro   = 10.sp   // バッジ/最小メタ
    val Emoji   = 18.sp   // リアクション絵文字グリフ（本文スケール外）

    // 「タイトル+説明」2段テキストの行高。合計40dp・スペーサーなしで
    // ヘッダ/リスト行（DM/チャンネル/テンプレ/アカウント行）共通の段差を作る。
    val LineTitle = 22.sp
    val LineDesc  = 18.sp
}

/**
 * 文字ロール別のウェイト（施策2: ウェイトの明暗コントラストで階層を作る）。
 * 名前/見出しは太く・メタ/本文は Normal に統一し、色(Text/Text3)と合わせて主役↔脇役を分ける。
 */
object DeckWeight {
    val Name   = FontWeight.Bold      // 人物名/エンティティ名（主役・最も強い）
    val Strong = FontWeight.SemiBold  // 画面/セクション見出し・アクティブタブ（UIの中見出し）
    val Link   = FontWeight.Medium    // リンク/アクティブ要素
    val Body   = FontWeight.Normal    // 本文・メタ（時刻/ハンドル/npub/ID）
}

/** 角丸トークン。tokens.css --r-*（8/12/18/full）と 1対1。実装はこれへ完全スナップ。 */
object DeckRadius {
    val Sm   = 8.dp
    val Md   = 12.dp
    val Lg   = 18.dp
    val Full = 999.dp
}

/** 余白トークン。tokens.css --sp-*（4/8/12/16/24）と 1対1。padding/Spacer はこれへスナップ。 */
object DeckSpace {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
}

private val DarkScheme = darkColorScheme(
    primary      = DeckColors.Accent,
    onPrimary    = DeckColors.Bg,        // 明色 primary 上は暗色文字
    // FAB は primaryContainer を使う。未指定だと M3 既定の紫が漏れる（Damus 風）ので明示的にモノクロ化。
    primaryContainer   = DeckColors.Accent,
    onPrimaryContainer = DeckColors.Bg,
    secondaryContainer   = DeckColors.Surface2,
    onSecondaryContainer = DeckColors.Text,
    tertiaryContainer   = DeckColors.Surface2,
    onTertiaryContainer = DeckColors.Text,
    // 未読バッジは error を使う。既定のピンクを避け、白地+暗色数字のモノクロに。
    error          = DeckColors.Accent,
    onError        = DeckColors.Bg,
    errorContainer = DeckColors.Accent,
    onErrorContainer = DeckColors.Bg,
    background   = DeckColors.Bg,
    surface      = DeckColors.Surface,
    surfaceVariant = DeckColors.Surface2,
    onBackground = DeckColors.Text,
    onSurface    = DeckColors.Text,
    onSurfaceVariant = DeckColors.Text2,
    outline      = DeckColors.Border,
)

@Composable
fun DeckTheme(content: @Composable () -> Unit) {
    // 現状はダーク固定（Nostr クライアントの定番）。将来ライト対応する場合はここで分岐。
    @Suppress("UNUSED_VARIABLE") val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
