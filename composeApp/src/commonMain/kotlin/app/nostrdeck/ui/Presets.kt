package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType

/**
 * リレー／メディアサーバーの「候補（おすすめ）」プリセット。
 * 静的にキュレーションした一覧で、ワンタップで設定へ追加できる（追加≠即時常時接続）。
 * 掲載リストはあくまで目安で、後から自由に増減してよい。
 */
enum class PresetCategory(val label: String) {
    General("汎用"),
    Japan("日本"),
    Paid("ペイド"),
    Image("画像特化"),
}

/** プリセット1件。[note] は「有料」等の軽い補足。 */
data class Preset(
    val url: String,
    val category: PresetCategory,
    val note: String? = null,
) {
    /** チップ表示用にスキームを落とした短い名前（例: relay.damus.io）。 */
    val displayName: String
        get() = url.substringAfter("://").trimEnd('/')
}

/** 追加済み判定・重複回避に使う URL 正規化（repo 側の addRelay/addMediaServer と同じ規則）。 */
internal fun normalizePresetUrl(url: String): String = url.trim().trimEnd('/')

/**
 * 静的なリレー候補（フォールバック用）。主役はフォロー中の NIP-65 集計レコメンド
 * （[app.nostrdeck.data.EventRepository.fetchRelayRecommendations]）で、これは
 * フォローが無い新規アカウント向けの最小リスト。2026-07 に NIP-11 応答で生存確認済み。
 * 死活は変わるので、増やすよりレコメンド側に任せる方針。
 */
val RELAY_PRESETS: List<Preset> = listOf(
    // 汎用（無料・大手）
    Preset("wss://relay.damus.io", PresetCategory.General),
    Preset("wss://nos.lol", PresetCategory.General),
    Preset("wss://relay.primal.net", PresetCategory.General),
    Preset("wss://relay.nostr.band", PresetCategory.General, "検索対応"),
    Preset("wss://relay.snort.social", PresetCategory.General),
    Preset("wss://nostr.mom", PresetCategory.General),
    Preset("wss://offchain.pub", PresetCategory.General),
    Preset("wss://purplerelay.com", PresetCategory.General),
    // 日本
    Preset("wss://relay-jp.nostr.wirednet.jp", PresetCategory.Japan),
    Preset("wss://yabu.me", PresetCategory.Japan),
    Preset("wss://r.kojira.io", PresetCategory.Japan),
    // ペイド（有料・認証必須のことが多い）
    Preset("wss://nostr.wine", PresetCategory.Paid, "有料"),
    Preset("wss://eden.nostr.land", PresetCategory.Paid, "有料"),
    Preset("wss://nostrelites.org", PresetCategory.Paid, "有料"),
)

/** NIP-96 メディアサーバー候補（画像アップロード先）。2026-07 に well-known 応答で生存確認済み。 */
val MEDIA_PRESETS: List<Preset> = listOf(
    Preset("https://nostr.build", PresetCategory.Image),
    Preset("https://nostrcheck.me", PresetCategory.General),
    Preset("https://nostpic.com", PresetCategory.Image),
    Preset("https://nostrmedia.com", PresetCategory.Image),
    Preset("https://files.sovbit.host", PresetCategory.General),
    // cdn.satellite.earth は 2026-07 時点で NIP-96 応答なし(404)のため除外。
)

/**
 * 「候補（おすすめ）」セクション。カテゴリ別にチップを並べ、タップで [onAdd] を呼ぶ。
 * すでに登録済み（[registered] に含まれる）の候補は非表示にする。
 * 残り候補が無ければ何も描画しない。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PresetPicker(
    presets: List<Preset>,
    registered: Set<String>,
    onAdd: (String) -> Unit,
) {
    val remaining = presets.filter { normalizePresetUrl(it.url) !in registered }
    if (remaining.isEmpty()) return

    Text("候補（おすすめ）", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    Text(
        "タップで一覧に追加します（登録済みは表示されません）。追加してもすぐには常時接続しません。",
        color = DeckColors.Text3, fontSize = DeckType.Label,
    )
    Spacer(Modifier.size(DeckSpace.Sm))

    // カテゴリ順（enum 宣言順）に、該当候補があるものだけ見出し付きで表示。
    PresetCategory.entries.forEach { cat ->
        val items = remaining.filter { it.category == cat }
        if (items.isEmpty()) return@forEach
        Text(cat.label, color = DeckColors.Text3, fontSize = DeckType.Micro)
        Spacer(Modifier.size(DeckSpace.Xs))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
            verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
        ) {
            items.forEach { p -> PresetChip(p, onAdd) }
        }
        Spacer(Modifier.size(DeckSpace.Sm))
    }
}

/**
 * [#relay-recs] フォロー中の NIP-65 集計によるレコメンドをチップ表示（「＋url ・n人」）。
 * 登録済みは除外。空なら何も描かない（呼び出し側でフォールバックを出す）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecommendedRelayChips(
    recs: List<Pair<String, Int>>,
    registered: Set<String>,
    onAdd: (String) -> Unit,
) {
    val remaining = recs.filter { normalizePresetUrl(it.first) !in registered }
    if (remaining.isEmpty()) return
    Text("フォロー中でよく使われているリレー", color = DeckColors.Text2, fontSize = DeckType.Caption)
    Spacer(Modifier.size(DeckSpace.Xs))
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
        verticalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
    ) {
        remaining.forEach { (url, n) ->
            PresetChip(Preset(url, PresetCategory.General, "${n}人"), onAdd)
        }
    }
}

/** 候補チップ（先頭「＋」＋ホスト名＋補足）。タップで追加。 */
@Composable
private fun PresetChip(preset: Preset, onAdd: (String) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(DeckRadius.Full))
            .background(DeckColors.Surface2)
            .clickable { onAdd(preset.url) }
            .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("＋", color = DeckColors.Accent, fontSize = DeckType.Sub)
        Spacer(Modifier.size(DeckSpace.Xs))
        Text(preset.displayName, color = DeckColors.Text, fontSize = DeckType.Label)
        preset.note?.let {
            Spacer(Modifier.size(DeckSpace.Xs))
            Text(it, color = DeckColors.Text3, fontSize = DeckType.Micro)
        }
    }
}
