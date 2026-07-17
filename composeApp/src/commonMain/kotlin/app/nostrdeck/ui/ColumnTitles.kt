package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

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
    else -> title
}

@Composable
fun columnDisplaySubtitle(subtitle: String): String = when (subtitle) {
    "自分のリアクション" -> stringResource(Res.string.sub_my_reactions)
    "キーワード・タグ" -> stringResource(Res.string.tpl_search)
    "プロフィール" -> stringResource(Res.string.profile_section)
    else -> subtitle
}
