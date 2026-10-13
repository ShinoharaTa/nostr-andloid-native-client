package app.nostrdeck.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.GroupRemove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.nostrdeck.data.EventRepository
import app.nostrdeck.i18n.stringResource
import kotlinx.coroutines.launch
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.channel_join
import nostr_deck_client.composeapp.generated.resources.channel_join_failed
import nostr_deck_client.composeapp.generated.resources.channel_joined_toast
import nostr_deck_client.composeapp.generated.resources.channel_leave
import nostr_deck_client.composeapp.generated.resources.channel_left_toast

/**
 * [#421] ルームの ⋯ に出す「参加する／参加をやめる」。デッキのカラム（[rememberColumnMenuActions]）と、
 * デッキの外のルーム（パブリックチャット画面・詳細オーバーレイ）で同じものを使う。
 *
 * 保存は最新版を取り直してから発行するので数秒かかることがある。その間は項目を押せなくし、終わったらトーストで知らせる。
 * 最新版を取れなかったときは App の共通トースト（own_list_unreachable）が出るので、ここでは保存の失敗だけ知らせる。
 * リポジトリが無い（プレビュー）・[channelId] が無いときは null（項目を出さない）。
 */
@Composable
internal fun rememberChannelJoinItem(channelId: String?): HeaderMenuItem? {
    val repo = LocalRepository.current ?: return null
    if (channelId == null) return null
    val joinedIds by remember(repo) { repo.joinedChannelIdsFlow() }.collectAsState()
    val joined = channelId in joinedIds
    var busy by remember(channelId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val toast = rememberToaster()
    val joinedMsg = stringResource(Res.string.channel_joined_toast)
    val leftMsg = stringResource(Res.string.channel_left_toast)
    val failedMsg = stringResource(Res.string.channel_join_failed)
    return HeaderMenuItem(
        label = stringResource(if (joined) Res.string.channel_leave else Res.string.channel_join),
        icon = if (joined) Icons.Outlined.GroupRemove else Icons.Outlined.GroupAdd,
        enabled = !busy,
    ) {
        busy = true
        scope.launch {
            val result = try {
                repo.setChannelJoined(channelId, join = !joined)
            } finally {
                busy = false
            }
            when (result) {
                EventRepository.ChannelJoinResult.DONE -> toast(if (joined) leftMsg else joinedMsg)
                EventRepository.ChannelJoinResult.FAILED -> toast(failedMsg)
                EventRepository.ChannelJoinResult.UNREACHABLE -> Unit
            }
        }
    }
}
