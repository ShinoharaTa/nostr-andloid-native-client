package app.nostrdeck.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import app.nostrdeck.model.UserStatuses
import androidx.compose.runtime.Composable
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnSpec
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import app.nostrdeck.i18n.stringResource

/**
 * [#160] カラムのタイトル/サブタイトルは DB に永続化されるため、保存済みの日本語を
 * **正準キー**として扱い、表示時にロケールへマップする（既存ユーザーのカラムも英語表示になる）。
 * 一致しない文字列（ユーザー入力のタグ・検索要約・プロフィール名等）はそのまま表示する。
 */
@Composable
fun columnDisplayTitle(title: String): String = when (title) {
    "フォロー中" -> stringResource(Res.string.tpl_following)
    "グローバル" -> stringResource(Res.string.tpl_global)
    "通知" -> stringResource(Res.string.tpl_notifications)
    "ふぁぼ欄" -> stringResource(Res.string.tpl_favs)
    "キーワード・タグ" -> stringResource(Res.string.tpl_search)
    "パブリックチャット" -> stringResource(Res.string.nav_public_chat)
    "スレッド" -> stringResource(Res.string.thread_title)
    "DM" -> stringResource(Res.string.nav_dm)
    "ステータス" -> stringResource(Res.string.tpl_status)   // [#772]
    else -> title
}

@Composable
fun columnDisplaySubtitle(subtitle: String): String = when (subtitle) {
    "自分のリアクション" -> stringResource(Res.string.sub_my_reactions)
    "キーワード・タグ" -> stringResource(Res.string.tpl_search)
    "プロフィール" -> stringResource(Res.string.profile_section)
    else -> subtitle
}

/**
 * [#325] カラムのサブタイトルを **kind から表示時に導出する**。
 *
 * 以前は作成時に文字列として DB へ焼いており、コードの変更に追従しない化石が残っていた
 * （実データに `following · 2 relays` `mentions · zaps` 等、**今のコードが生成し得ない文字列**が
 * 実在した）。種別ラベルはテンプレから毎回引けばよく、保存する必要がない。
 *
 * ルーム/一覧/スレッドだけは保存値が実内容（チャンネル説明・一時表示の注記）なので従来どおり
 * 保存値を通す。DB の subtitle 列は互換のため残る（ここでは読まないだけ）。
 */
@Composable
fun columnSubtitleFor(spec: ColumnSpec): String = when (spec.kind) {
    ColumnKind.FOLLOWING -> "following"
    // GLOBAL は検索カラムの器も兼ねる（buildSearchColumn）。words/hashtags を持てば検索。
    ColumnKind.GLOBAL ->
        if (spec.filter.words.isNotEmpty() || spec.filter.hashtags.isNotEmpty()) {
            stringResource(Res.string.tpl_search)
        } else {
            "global"
        }
    ColumnKind.HASHTAG -> "hashtag"
    ColumnKind.NOTIFICATIONS -> stringResource(Res.string.notif_subtitle)
    ColumnKind.DM -> "NIP-17"
    ColumnKind.PROFILE -> stringResource(Res.string.profile_section)
    ColumnKind.FAVS -> stringResource(Res.string.sub_my_reactions)
    // [#385] NIP-51 リストから開いたタイムライン（タイトルはリスト名）。
    ColumnKind.LIST -> stringResource(Res.string.tab_lists)
    ColumnKind.THREAD, ColumnKind.CHANNEL_LIST, ColumnKind.CHANNEL_ROOM ->
        columnDisplaySubtitle(spec.subtitle)
    // [#772] 「表示」で絞っていればその種類、すべてなら NIP-38（Web と同じ）。
    ColumnKind.STATUS -> when (LocalRepository.current?.columnStatusTypesFlow()?.collectAsState()?.value?.get(spec.id)) {
        UserStatuses.Type.MUSIC -> stringResource(Res.string.status_type_music)
        UserStatuses.Type.GENERAL -> stringResource(Res.string.status_type_general)
        null -> "NIP-38"
    }
}
