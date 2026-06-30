package app.nostrdeck.theme

import androidx.compose.ui.graphics.Color

/**
 * designs/tokens.css と一対一で対応するカラートークン。
 * 片方を変えたら両方を合わせること（デザインモックと実装の同期を保つ）。
 */
object DeckColors {
    // surfaces
    val Bg          = Color(0xFF0C0C10)
    val Surface     = Color(0xFF15151C)
    val Surface2    = Color(0xFF1D1D26)
    val Surface3    = Color(0xFF262631)
    val Border      = Color(0xFF2C2C38)
    val BorderStrong = Color(0xFF3A3A48)

    // text
    val Text   = Color(0xFFECEDF1)
    val Text2  = Color(0xFFA7A8B3)
    val Text3  = Color(0xFF6F7080)

    // accent: モノクロ基調。ブランドカラー(紫)は持たない。
    // 「選択中/アクティブ」は彩度ゼロの明色で示し、Damus/Nostr 的な紫を避ける。
    val Accent     = Color(0xFFF2F2F5)  // アクティブな文字/アイコン（ほぼ白）
    val Accent2    = Color(0xFFC9C9D0)  // セカンダリ（チャット名など）
    val AccentWeak = Color(0x14FFFFFF)  // 選択チップ/ナビの淡い下地（白の薄被せ）

    // action colors: 無彩色（アイコンは色で意味づけしない。形と数値で区別）
    val Zap    = Color(0xFFE7E7EA)  // pin アクティブ・ルームヘッダ・オフライン表示
    val Repost = Color(0xFFC9C9D0)
    val Like   = Color(0xFF8A8A93)

    // 通知一覧でリポストを示す控えめなグリーン（暗いモノクロ基調に馴染む彩度に抑える）。
    val Boost  = Color(0xFF4FA77A)

    // NIP-05 検証バッジ用。OK=控えめな緑(Boost)、異常=控えめな赤(Warn)。
    val Verified = Color(0xFF4FA77A)
    val Warn     = Color(0xFFC76B6B)
}
