package app.nostrdeck.model

import app.nostrdeck.crypto.currentUnixTime

/** 追加カラムの設定種別。 */
enum class ColumnConfig { NONE, TEXT, NOTIF_FILTER }

/**
 * 追加できるカラムの種別を「絞った」一覧（白紙のフィルタ組みではなく選ぶ）。
 * スレッドは文脈型（ノートをタップして開く）なのでここには含めない。
 */
enum class ColumnTemplate(
    val label: String,
    val config: ColumnConfig = ColumnConfig.NONE,
    val hint: String? = null,
) {
    FOLLOWING("フォロー中"),
    GLOBAL("グローバル", ColumnConfig.TEXT, "リレー URL（空=全リレー）"),
    NOTIFICATIONS("通知", ColumnConfig.NOTIF_FILTER),
    DM("DM"),
    PROFILE("指定 npub の投稿", ColumnConfig.TEXT, "npub または hex"),
    SEARCH("ワード検索", ColumnConfig.TEXT, "検索ワード"),
    HASHTAG("ハッシュタグ", ColumnConfig.TEXT, "タグ（# は不要）"),
}

/** 通知で選べるイベント種別（永続化されるフィルタ）。 */
enum class NotifKind(val label: String, val kind: Int) {
    MENTION("メンション", 1),
    REACTION("リアクション", 7),
    ZAP("Zap", 9735),
    REPOST("リポスト", 6),
}

/** テンプレ + 入力 → ColumnSpec を生成。 */
fun ColumnTemplate.build(input: String = "", notifKinds: List<Int> = NotifKind.entries.map { it.kind }): ColumnSpec {
    val id = "col_${name.lowercase()}_${currentUnixTime()}"
    val text = input.trim()
    return when (this) {
        ColumnTemplate.FOLLOWING -> spec(id, "フォロー中", "following", ColumnKind.FOLLOWING,
            ReqFilter(kinds = listOf(1)))

        ColumnTemplate.GLOBAL -> spec(id, "グローバル",
            if (text.isBlank()) "all relays" else text, ColumnKind.GLOBAL,
            ReqFilter(kinds = listOf(1), relays = if (text.isBlank()) emptyList() else listOf(text)))

        ColumnTemplate.NOTIFICATIONS -> spec(id, "通知",
            "mentions/zaps…", ColumnKind.NOTIFICATIONS,
            ReqFilter(kinds = notifKinds.ifEmpty { listOf(1, 7, 9735, 6) }))

        ColumnTemplate.DM -> spec(id, "DM", "NIP-17", ColumnKind.DM,
            ReqFilter(kinds = listOf(1059)))

        ColumnTemplate.PROFILE -> spec(id, npubShort(text), "profile", ColumnKind.PROFILE,
            ReqFilter(kinds = listOf(1), authors = listOf(text)))

        ColumnTemplate.SEARCH -> spec(id, "検索: $text", "NIP-50", ColumnKind.GLOBAL,
            ReqFilter(kinds = listOf(1), search = text))

        ColumnTemplate.HASHTAG -> spec(id, "#${text.removePrefix("#")}", "hashtag", ColumnKind.HASHTAG,
            ReqFilter(kinds = listOf(1), hashtags = listOf(text.removePrefix("#"))))
    }
}

private fun spec(id: String, title: String, subtitle: String, kind: ColumnKind, filter: ReqFilter) =
    ColumnSpec(id, title, subtitle, kind, ColumnRenderer.FEED, filter, pinned = true)

private fun npubShort(s: String): String =
    if (s.length > 14) s.take(10) + "…" else s.ifBlank { "profile" }
