package app.nostrdeck.data

import app.nostrdeck.model.AuthPolicy
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NoteAccentStyle
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.model.withHexProfileAuthors
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.auth_always
import nostr_deck_client.composeapp.generated.resources.auth_dm_mine
import nostr_deck_client.composeapp.generated.resources.auth_off
import nostr_deck_client.composeapp.generated.resources.auth_title
import nostr_deck_client.composeapp.generated.resources.bold_text_title
import nostr_deck_client.composeapp.generated.resources.note_accent_bg
import nostr_deck_client.composeapp.generated.resources.note_accent_line
import nostr_deck_client.composeapp.generated.resources.note_accent_none
import nostr_deck_client.composeapp.generated.resources.note_accent_title
import nostr_deck_client.composeapp.generated.resources.reaction_default_title
import nostr_deck_client.composeapp.generated.resources.sync_name_reaction_image
import nostr_deck_client.composeapp.generated.resources.sync_value_none
import nostr_deck_client.composeapp.generated.resources.sync_value_off
import nostr_deck_client.composeapp.generated.resources.sync_value_on
import org.jetbrains.compose.resources.StringResource

/**
 * [#374] 設定のリレー同期（NIP-78 / kind:30078, d=[SETTINGS_SYNC_D]）。
 *
 * 「明示的な操作でしか書き換わらない」インポート/エクスポート型:
 *  - 保存は手動のみ（設定画面のボタン）。自動発行・自動取込・常時購読はしない。
 *  - ロードも手動。取得スナップショットとローカル現在値の**差分だけ**を一覧表示し、
 *    チェックした項目だけを個別適用する。
 *
 * content は平文 JSON `{"version":1,"settings":{"<KVキー>":"<値>"}}`。
 * キーはローカル KV のキーをそのまま使う（[SETTINGS_SYNC_WHITELIST] 参照）。
 */

/** 30078（d=nostrism-settings）の content。 */
@Serializable
data class SettingsSyncPayload(
    val version: Int = 1,
    val settings: Map<String, String> = emptyMap(),
)

/** 差分一覧に出す値の表示ラベル（文字列リソース or 生文字列）。 */
sealed interface SyncValueLabel {
    data class Text(val value: String) : SyncValueLabel
    data class Resource(val res: StringResource) : SyncValueLabel
}

/**
 * 同期対象1件分の定義。キー・表示名・値の表示整形・現在値の読み出し・適用をここに集約する。
 * 同期対象を増やすときは [SETTINGS_SYNC_WHITELIST] に1エントリ追加するだけでよい。
 */
class SyncSettingSpec(
    /** KVキー = スナップショット JSON のキー。 */
    val key: String,
    /** 差分一覧に出す設定名。 */
    val nameRes: StringResource,
    /** 現在値の正規化文字列（KV 未設定でも既定値を返す）。 */
    val read: (EventRepository) -> String,
    /** リモート値の適用（各 setter 経由で StateFlow も更新する）。 */
    val apply: (EventRepository, String) -> Unit,
    /** 値 → 人間が読めるラベル。 */
    val display: (String) -> SyncValueLabel = { SyncValueLabel.Text(it) },
)

private fun AuthPolicy.toSyncId(): String = when (this) {
    AuthPolicy.OFF -> "off"; AuthPolicy.ALWAYS -> "always"; AuthPolicy.DM_AND_MINE -> "dm"
}

private fun authPolicyFromSyncId(id: String): AuthPolicy = when (id) {
    "off" -> AuthPolicy.OFF; "always" -> AuthPolicy.ALWAYS; else -> AuthPolicy.DM_AND_MINE
}

private fun onOff(v: String): SyncValueLabel =
    SyncValueLabel.Resource(if (v == "1") Res.string.sync_value_on else Res.string.sync_value_off)

/**
 * [#374] リレー同期対象のホワイトリスト。
 * 端末ごとに変えたい設定（表示サイズ/UIスケール/テーマ/カスタムカラー）、回線・端末依存
 * （画質/動画設定）、端末ローカル値（下書き/既読位置/検索履歴/開発者モード）、他の kind で
 * 同期済みのもの（絵文字リスト=kind:10030）は**対象外**。
 */
val SETTINGS_SYNC_WHITELIST: List<SyncSettingSpec> = listOf(
    SyncSettingSpec(
        EventRepository.AUTH_POLICY, Res.string.auth_title,
        read = { it.authPolicyFlow().value.toSyncId() },
        apply = { r, v -> r.setAuthPolicy(authPolicyFromSyncId(v)) },
        display = {
            SyncValueLabel.Resource(
                when (it) {
                    "off" -> Res.string.auth_off
                    "always" -> Res.string.auth_always
                    else -> Res.string.auth_dm_mine
                },
            )
        },
    ),
    SyncSettingSpec(
        EventRepository.BOLD_TEXT_KEY, Res.string.bold_text_title,
        read = { if (it.boldTextFlow().value) "1" else "0" },
        apply = { r, v -> r.setBoldText(v == "1") },
        display = ::onOff,
    ),
    SyncSettingSpec(
        EventRepository.DEFAULT_REACTION_CONTENT, Res.string.reaction_default_title,
        read = { it.defaultReactionFlow().value.first },
        // 画像はもう一方のエントリで適用するため、ここでは content だけ差し替える。
        apply = { r, v -> r.setDefaultReaction(v, r.defaultReactionFlow().value.second) },
        // "+" は表示上は ❤️（ingest 側の正規化と同じ）。
        display = { SyncValueLabel.Text(if (it == "+" || it.isEmpty()) "❤️" else it) },
    ),
    SyncSettingSpec(
        EventRepository.DEFAULT_REACTION_IMAGE, Res.string.sync_name_reaction_image,
        read = { it.defaultReactionFlow().value.second ?: "" },
        apply = { r, v -> r.setDefaultReaction(r.defaultReactionFlow().value.first, v.ifBlank { null }) },
        display = { if (it.isBlank()) SyncValueLabel.Resource(Res.string.sync_value_none) else SyncValueLabel.Text(it) },
    ),
    SyncSettingSpec(
        EventRepository.NOTE_ACCENT_STYLE_KEY, Res.string.note_accent_title,
        read = { it.noteAccentStyleFlow().value.id },
        apply = { r, v -> r.setNoteAccentStyle(NoteAccentStyle.fromId(v)) },
        display = {
            SyncValueLabel.Resource(
                when (NoteAccentStyle.fromId(it)) {
                    NoteAccentStyle.LINE -> Res.string.note_accent_line
                    NoteAccentStyle.BACKGROUND -> Res.string.note_accent_bg
                    NoteAccentStyle.NONE -> Res.string.note_accent_none
                },
            )
        },
    ),
)

// ---- 差分計算（純関数・単体テスト対象）----

/** 設定1件の差分（ローカル現在値 → リモート値）。 */
data class SettingDiff(val key: String, val localValue: String, val remoteValue: String)

/**
 * 設定の差分を計算する。ホワイトリスト順で、リモートに存在して値が違うキーだけを返す。
 * リモートに無いキー（古いスナップショット等）と、ホワイトリスト外のキーは無視する。
 */
fun diffSyncSettings(local: Map<String, String>, remote: Map<String, String>): List<SettingDiff> =
    SETTINGS_SYNC_WHITELIST.mapNotNull { spec ->
        val r = remote[spec.key] ?: return@mapNotNull null
        val l = local[spec.key] ?: return@mapNotNull null
        if (l == r) null else SettingDiff(spec.key, l, r)
    }

/** カラム構成の差分（カラム単位）。 */
sealed interface ColumnDiff {
    /** リモートにだけあるカラム（適用=追加）。 */
    data class Added(val spec: ColumnSpec) : ColumnDiff
    /** ローカルにだけあるカラム（適用=削除）。 */
    data class Removed(val spec: ColumnSpec) : ColumnDiff
    /** 同じ id で内容（タイトル/フィルタ等）が違うカラム（適用=リモート版に置換）。 */
    data class Changed(val local: ColumnSpec, val remote: ColumnSpec) : ColumnDiff
    /** 共通カラムの並び順だけが違う（適用=リモートの並びに揃える）。 */
    data class Reordered(val remoteIdOrder: List<String>) : ColumnDiff
}

/** カラム内容の同一性（並び順・pinned・unread は見ない）。 */
private fun sameColumnContent(a: ColumnSpec, b: ColumnSpec): Boolean =
    a.title == b.title && a.subtitle == b.subtitle && a.kind == b.kind &&
        a.renderer == b.renderer && a.filter == b.filter

/**
 * カラム構成の差分を計算する。id で突き合わせ、追加/削除/変更をカラム単位で返す。
 * 双方に存在するカラムの並び順が違うときだけ [ColumnDiff.Reordered] を1件加える。
 * [#778] [remote] は [decodeDeckColumnsContent] の columns（知らない種別の行は含まない）。
 * 知らない種別の行は手元にも無いので、ここでは差分（削除・並び順）に出さない。保存では元の位置へ書き戻す。
 */
fun diffDeckColumns(local: List<ColumnSpec>, remote: List<ColumnSpec>): List<ColumnDiff> {
    val localSorted = local.sortedBy { it.order }
    val remoteSorted = remote.sortedBy { it.order }
    val localById = localSorted.associateBy { it.id }
    val remoteById = remoteSorted.associateBy { it.id }
    val diffs = mutableListOf<ColumnDiff>()
    remoteSorted.filter { it.id !in localById }.forEach { diffs += ColumnDiff.Added(it) }
    localSorted.filter { it.id !in remoteById }.forEach { diffs += ColumnDiff.Removed(it) }
    localSorted.forEach { l ->
        val r = remoteById[l.id] ?: return@forEach
        if (!sameColumnContent(l, r)) diffs += ColumnDiff.Changed(l, r)
    }
    val commonLocalOrder = localSorted.map { it.id }.filter { it in remoteById }
    val commonRemoteOrder = remoteSorted.map { it.id }.filter { it in localById }
    if (commonLocalOrder != commonRemoteOrder) diffs += ColumnDiff.Reordered(remoteSorted.map { it.id })
    return diffs
}

/**
 * チェックされた差分だけをローカル構成へ適用した結果を返す。
 *  - Added: リモートの並び位置を尊重して追加（Reordered 未選択でも末尾ではなく近い位置へ）。
 *  - Removed: 取り除く。 Changed: 位置は維持して内容だけリモート版へ。
 *  - Reordered: 共通カラムをリモートの並びへ。リモートに無いカラムは末尾（ローカル順）。
 * order は最後に 0..n-1 へ振り直す。
 */
fun applyColumnDiffs(local: List<ColumnSpec>, selected: List<ColumnDiff>): List<ColumnSpec> {
    var result = local.sortedBy { it.order }.toMutableList()
    selected.filterIsInstance<ColumnDiff.Removed>().forEach { d ->
        result.removeAll { it.id == d.spec.id }
    }
    selected.filterIsInstance<ColumnDiff.Changed>().forEach { d ->
        result = result.map { if (it.id == d.local.id) d.remote.copy(pinned = true) else it }.toMutableList()
    }
    selected.filterIsInstance<ColumnDiff.Added>().sortedBy { it.spec.order }.forEach { d ->
        if (result.none { it.id == d.spec.id }) {
            result.add(d.spec.order.coerceIn(0, result.size), d.spec.copy(pinned = true))
        }
    }
    selected.filterIsInstance<ColumnDiff.Reordered>().firstOrNull()?.let { d ->
        val pos = d.remoteIdOrder.withIndex().associate { (i, id) -> id to i }
        val inRemote = result.filter { it.id in pos }.sortedBy { pos[it.id] }
        val rest = result.filter { it.id !in pos }
        result = (inRemote + rest).toMutableList()
    }
    return result.mapIndexed { i, s -> s.copy(order = i) }
}

// ---- [#778] カラム構成の content（d=DECK_COLUMNS_D）の読み書き（純関数・単体テスト対象）----

/** 30078 の content に載せるカラム1件分。他クライアントからも読める素直な JSON。
 *  #122 時代に発行済みのイベントと互換のある形式なので変更しないこと。 */
@Serializable
private data class DeckColumnDto(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val kind: String,
    val renderer: String,
    val filter: ReqFilter = ReqFilter(),
    val order: Int = 0,
)

// EventRepository の json と同じ設定（encodeDefaults = false。既定と等しい subtitle / filter / order は書かない）。
private val deckColumnsJson = Json { ignoreUnknownKeys = true }

/**
 * このビルドが知らない種別（kind / renderer）の行。表示はせず、保存のときに書き戻す。
 * [position] は order の昇順（同じ order は元の順）に並べたときの位置。[raw] は受け取った行の JSON そのまま。
 */
data class UnknownDeckColumn(val position: Int, val raw: JsonElement)

/** カラム構成の content を読んだ結果。[columns] は表示できる行（元の配列の順）、[unknown] は知らない種別の行。 */
data class DeckColumnsContent(val columns: List<ColumnSpec>, val unknown: List<UnknownDeckColumn>)

/**
 * d=DECK_COLUMNS_D の content（#122 互換の DeckColumnDto 配列）を読む。JSON 全体が壊れていれば null。
 * [#388-review] 行の変換は loadPinnedColumns と同じく 1 件ずつ包む（1 件の失敗で全件を失わない）。
 * [#778] 知らない種別の行は捨てずに、生の JSON のまま [DeckColumnsContent.unknown] へ控える。
 */
fun decodeDeckColumnsContent(content: String): DeckColumnsContent? {
    val dtos = runCatching { deckColumnsJson.decodeFromString(ListSerializer(DeckColumnDto.serializer()), content) }
        .getOrNull() ?: return null
    val raws = runCatching { deckColumnsJson.parseToJsonElement(content).jsonArray }.getOrNull() ?: return null
    // 位置は order の昇順で数える（sortedBy は安定。Web の sortByOrder と同じ並び）。
    val positionOf = dtos.indices.sortedBy { dtos[it].order }.withIndex().associate { (pos, i) -> i to pos }
    val columns = mutableListOf<ColumnSpec>()
    val unknown = mutableListOf<UnknownDeckColumn>()
    dtos.forEachIndexed { i, d ->
        val kind = ColumnKind.entries.firstOrNull { it.name == d.kind }
        val renderer = ColumnRenderer.entries.firstOrNull { it.name == d.renderer }
        if (kind == null || renderer == null) {
            unknown += UnknownDeckColumn(positionOf.getValue(i), raws[i])
            return@forEachIndexed
        }
        runCatching {
            ColumnSpec(
                id = d.id, title = d.title, subtitle = d.subtitle,
                kind = kind, renderer = renderer,
                filter = d.filter, pinned = true, order = d.order,
            ).withHexProfileAuthors()   // [#578]
        }.getOrNull()?.let { columns += it }
    }
    return DeckColumnsContent(columns, unknown)
}

/**
 * 手元のピン留めカラム [local]（並び順どおり）を d=DECK_COLUMNS_D の content にする。
 * 手元の行の order は書き出す配列の位置で振り直す。
 * [#778] [unknown]（最後に読み込んだ版の知らない種別の行）は、読み込んだときの位置へ中身を変えずに差し戻す
 * （手元の行が減って位置が配列の長さを超えるときは末尾へ）。
 */
fun encodeDeckColumnsContent(local: List<ColumnSpec>, unknown: List<UnknownDeckColumn>): String {
    // null = 手元の行が入る場所
    val slots = MutableList<JsonElement?>(local.size) { null }
    unknown.sortedBy { it.position }.forEach { u -> slots.add(u.position.coerceIn(0, slots.size), u.raw) }
    val known = local.iterator()
    val rows = slots.mapIndexed { i, raw ->
        raw ?: known.next().let { s ->
            deckColumnsJson.encodeToJsonElement(
                DeckColumnDto.serializer(),
                DeckColumnDto(s.id, s.title, s.subtitle, s.kind.name, s.renderer.name, s.filter, i),
            )
        }
    }
    return JsonArray(rows).toString()
}
