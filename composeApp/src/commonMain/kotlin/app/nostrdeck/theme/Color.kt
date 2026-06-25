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

    // accent (Nostr purple)
    val Accent     = Color(0xFFA855F7)
    val Accent2    = Color(0xFF8B5CF6)
    val AccentWeak = Color(0x24A855F7)

    // action colors
    val Zap    = Color(0xFFF7B955)
    val Repost = Color(0xFF4ADE80)
    val Like   = Color(0xFFF76E8E)
}
