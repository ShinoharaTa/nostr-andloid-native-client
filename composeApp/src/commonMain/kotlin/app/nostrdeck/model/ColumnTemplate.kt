package app.nostrdeck.model

import app.nostrdeck.crypto.Nip19
import app.nostrdeck.crypto.currentUnixTime
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

/** 追加カラムの設定種別。 */
enum class ColumnConfig { NONE, TEXT, NOTIF_FILTER, RELAY_SET }

/**
 * 追加できるカラムの種別を「絞った」一覧（白紙のフィルタ組みではなく選ぶ）。
 * スレッドは文脈型（ノートをタップして開く）なのでここには含めない。
 */
// [#160] ラベル/ヒントは文字列リソース（UI 層で stringResource() 解決）。
enum class ColumnTemplate(
    val label: StringResource,
    val config: ColumnConfig = ColumnConfig.NONE,
    val hint: StringResource? = null,
) {
    FOLLOWING(Res.string.tpl_following),
    GLOBAL(Res.string.tpl_global, ColumnConfig.RELAY_SET, Res.string.tpl_global_hint),
    NOTIFICATIONS(Res.string.tpl_notifications, ColumnConfig.NOTIF_FILTER),
    DM(Res.string.nav_dm),
    PROFILE(Res.string.tpl_profile, ColumnConfig.TEXT, Res.string.tpl_profile_hint),
    SEARCH(Res.string.tpl_search, ColumnConfig.TEXT, Res.string.tpl_search_hint),
    HASHTAG(Res.string.tpl_hashtag, ColumnConfig.TEXT, Res.string.tpl_hashtag_hint),
    FAVS(Res.string.tpl_favs, hint = Res.string.tpl_favs_hint),
}

/** 通知で選べるイベント種別（永続化されるフィルタ）。 */
enum class NotifKind(val label: StringResource, val kind: Int) {
    MENTION(Res.string.notif_mention, 1),
    REACTION(Res.string.notif_reaction, 7),
    ZAP(Res.string.notif_zap, 9735),
    REPOST(Res.string.notif_repost, 6),
}

/**
 * テンプレ + 入力 → ColumnSpec を生成。[relays]=GLOBAL の配信先リレー集合。
 * [#160] ここで入れる日本語タイトルは DB に永続化される**正準キー**。表示時に
 * [app.nostrdeck.ui.columnDisplayTitle] がロケールへマップするため、変更しないこと。
 */
fun ColumnTemplate.build(
    input: String = "",
    notifKinds: List<Int> = NotifKind.entries.map { it.kind },
    relays: List<String> = emptyList(),
): ColumnSpec {
    val id = "col_${name.lowercase()}_${currentUnixTime()}"
    val text = input.trim()
    return when (this) {
        ColumnTemplate.FOLLOWING -> spec(id, "フォロー中", "following", ColumnKind.FOLLOWING,
            ReqFilter(kinds = listOf(1)))

        ColumnTemplate.GLOBAL -> spec(id, "グローバル",
            if (relays.isEmpty()) "all relays" else "${relays.size} relays", ColumnKind.GLOBAL,
            ReqFilter(kinds = listOf(1), relays = relays))

        ColumnTemplate.NOTIFICATIONS -> spec(id, "通知",
            "mentions/zaps…", ColumnKind.NOTIFICATIONS,
            ReqFilter(kinds = notifKinds.ifEmpty { listOf(1, 7, 9735, 6) }))

        // [#415] 表示は復号後の kind:14（gift wrap 1059 は event テーブルに保存しない）。
        // カラム自身は REQ を張らず、起動時の dm_inbox 購読に相乗りする。
        ColumnTemplate.DM -> spec(id, "DM", "NIP-17", ColumnKind.DM,
            ReqFilter(kinds = listOf(14)))

        // [#578] authors は hex のみ（npub のまま入れると REQ に何も流れない）。
        // 読めない入力は UI 側（acceptsInput）で追加/保存させない。
        ColumnTemplate.PROFILE -> spec(id, npubShort(text.removePrefix("nostr:")), "profile", ColumnKind.PROFILE,
            ReqFilter(kinds = listOf(1), authors = listOf(profileInputToHex(text) ?: text)))

        ColumnTemplate.SEARCH -> {
            // [#135] スペース区切りのトークンを単語/タグへ振り分け、OR で並べる1フィードに。
            val tokens = text.split(Regex("""\s+""")).filter { it.isNotBlank() }
            buildSearchColumn(
                words = tokens.filterNot { it.startsWith("#") },
                hashtags = tokens.filter { it.startsWith("#") },
            )
        }

        ColumnTemplate.HASHTAG -> spec(id, "#${text.removePrefix("#")}", "hashtag", ColumnKind.HASHTAG,
            ReqFilter(kinds = listOf(1), hashtags = listOf(text.removePrefix("#"))))

        ColumnTemplate.FAVS -> spec(id, "ふぁぼ欄", "自分のリアクション", ColumnKind.FAVS,
            ReqFilter(kinds = listOf(7)))
    }
}

private fun spec(id: String, title: String, subtitle: String, kind: ColumnKind, filter: ReqFilter) =
    ColumnSpec(id, title, subtitle, kind, ColumnRenderer.FEED, filter, pinned = true)

/**
 * [#578] PROFILE の入力 → 64 桁 hex 公開鍵。npub / nprofile（その pubkey）/ hex を受け付け、
 * `nostr:` 付きでも良い。読めなければ null。
 */
fun profileInputToHex(input: String): String? {
    val t = input.trim().removePrefix("nostr:")
    if (t.length == 64 && t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return t.lowercase()
    return Nip19.mentionBechToHex(t)?.takeIf { it.length == 64 }
}

/** [#578] 入力欄の値で追加/保存できるか。PROFILE は pubkey として読めるものだけ。 */
fun ColumnTemplate.acceptsInput(text: String): Boolean = when {
    config != ColumnConfig.TEXT -> true
    this == ColumnTemplate.PROFILE -> profileInputToHex(text) != null
    else -> text.isNotBlank()
}

/**
 * [#578] 旧版で保存された PROFILE カラムの authors に npub 等が入っていれば hex に直す
 * （読み込み時に通し、壊れたまま保存済みのカラムも流れるようにする）。直せない値はそのまま。
 */
fun ColumnSpec.withHexProfileAuthors(): ColumnSpec {
    if (kind != ColumnKind.PROFILE) return this
    val fixed = filter.authors.map { profileInputToHex(it) ?: it }
    return if (fixed == filter.authors) this else copy(filter = filter.copy(authors = fixed))
}

/**
 * [#135] キーワード・タグフィード。指定した単語・#タグの投稿を1カラムに OR で集約する
 * （例: rally #wrc #rally）。タイトルは条件の要約。
 */
fun buildSearchColumn(words: List<String>, hashtags: List<String>): ColumnSpec {
    val summary = (words + hashtags.map { "#${it.removePrefix("#")}" }).joinToString(" ")
    return spec(
        "col_search_${currentUnixTime()}",
        summary.ifBlank { "キーワード・タグ" },
        "キーワード・タグ",
        ColumnKind.GLOBAL,
        ReqFilter(
            kinds = listOf(1),
            words = words,
            hashtags = hashtags.map { it.removePrefix("#").lowercase() },
        ),
    )
}

/**
 * [#385] NIP-51 フォローセット(kind:30000)のメンバーのタイムラインを1カラムにする。
 * 既存のカラム機構（authors 指定の FEED）にそのまま載せるだけで、新しい基盤は要らない。
 * REQ が肥大しないよう著者数は [LIST_COLUMN_AUTHOR_CAP] で頭打ちにする。
 */
fun buildListColumn(title: String, members: List<String>): ColumnSpec = ColumnSpec(
    id = "col_list_${currentUnixTime()}",
    title = title.ifBlank { "リスト" },
    subtitle = "list",
    kind = ColumnKind.LIST,
    renderer = ColumnRenderer.FEED,
    // 投稿＋リポスト。プロフィールのタイムラインと同じ組み合わせ。
    filter = ReqFilter(kinds = listOf(1, 6, 16), authors = members.distinct().take(LIST_COLUMN_AUTHOR_CAP)),
    pinned = false,   // 一時カラムとして開く（ヘッダの📌で固定できる）
)

/** リストカラムに載せる著者数の上限（REQ とローカル絞り込みの現実的な上限）。 */
const val LIST_COLUMN_AUTHOR_CAP = 500

/**
 * 既存カラムの「フィルター再設定」に使うテンプレ（設定を持たないカラムは null）。
 * FOLLOWING/DM/スレッド/チャンネル系は設定項目が無いので編集対象外。
 */
fun ColumnSpec.editTemplate(): ColumnTemplate? = when {
    kind == ColumnKind.HASHTAG -> ColumnTemplate.HASHTAG
    kind == ColumnKind.PROFILE -> ColumnTemplate.PROFILE
    kind == ColumnKind.NOTIFICATIONS -> ColumnTemplate.NOTIFICATIONS
    kind == ColumnKind.GLOBAL && (filter.words.isNotEmpty() || filter.search != null) -> ColumnTemplate.SEARCH
    kind == ColumnKind.GLOBAL -> ColumnTemplate.GLOBAL
    else -> null
}

/** 現在のテキスト設定値（TEXT 設定テンプレのプリフィル用）。 */
fun ColumnSpec.editText(): String = when (kind) {
    ColumnKind.HASHTAG -> filter.hashtags.firstOrNull().orEmpty()
    // [#578] authors は hex で持つので、入力欄には npub に戻して出す。
    ColumnKind.PROFILE -> filter.authors.firstOrNull()
        ?.let { runCatching { Nip19.hexToNpub(it) }.getOrNull() ?: it }.orEmpty()
    // [#135] キーワード・タグフィードはトークン列へ可逆に戻す（旧形式は search をそのまま）。
    ColumnKind.GLOBAL ->
        if (filter.words.isNotEmpty() || filter.hashtags.isNotEmpty())
            (filter.words + filter.hashtags.map { "#$it" }).joinToString(" ")
        else filter.search ?: ""
    else -> ""
}

/** GLOBAL カラムの現在の配信先リレー（RELAY_SET のプリフィル用）。 */
fun ColumnSpec.editRelays(): List<String> = if (kind == ColumnKind.GLOBAL) filter.relays else emptyList()

private fun npubShort(s: String): String =
    if (s.length > 14) s.take(10) + "…" else s.ifBlank { "profile" }
