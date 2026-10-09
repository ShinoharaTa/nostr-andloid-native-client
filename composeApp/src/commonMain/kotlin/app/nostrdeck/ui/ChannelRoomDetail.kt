package app.nostrdeck.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.nostrdeck.data.SampleData
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.Channel
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.KIND_CHANNEL_CREATE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.chat_room_unnamed

/**
 * [#791] 詳細オーバーレイで重ねるパブリックチャット（NIP-28）のルーム。本文の kind:40/42 へのリンクから開く。
 * 「パブリックチャット」タブへは移らず、スレッド・プロフィールと同じく戻るで元の画面へ戻る。
 *  - チャンネル名は手元の一覧（channel テーブル）から引く。一覧に無ければ kind:40 を id で取りに行き、
 *    届くまで（届かなければずっと）名前なしで出す。
 *  - [messageId]（kind:42 へのリンク）があれば、その発言の位置まで送って短く強調する。
 */
@Composable
internal fun ChannelRoomDetail(state: DeckState, channelId: String, messageId: String?) {
    val repo = LocalRepository.current
    val channels = if (repo != null) {
        remember { repo.channelsFlow() }.collectAsState(emptyList()).value
    } else SampleData.channels
    val listed = channels.firstOrNull { it.id == channelId }
    var fetched by remember(channelId) { mutableStateOf<Channel?>(null) }
    LaunchedEffect(channelId, listed == null) {
        if (listed == null && fetched == null && repo != null) {
            fetched = repo.fetchEvent(channelId)?.let { channelFromCreateEvent(it) }
        }
    }
    val channel = listed ?: fetched ?: Channel(channelId, name = "", about = "")
    val unnamed = stringResource(Res.string.chat_room_unnamed)
    val spec = SampleData.roomColumnFor(channel).let { base ->
        // デッキに同じルームを固定していても購読が混ざらないよう、カラムとは別の購読 id にする
        // （閉じたときにカラム側の購読まで CLOSE しないため）。リレーの subId 長の上限 64 文字に収める。
        base.copy(id = "overlay_room_${channelId.take(32)}", title = base.title.ifBlank { unnamed })
    }
    LiveChannelRoom(
        spec = spec,
        channelId = channelId,
        modifier = Modifier.fillMaxSize(),
        onBack = { state.popDetail() },
        highlightMessageId = messageId,
    )
}

private val channelJson = Json { ignoreUnknownKeys = true }

/**
 * [#791] kind:40（チャンネル作成）の content（JSON: name / about / picture）→ [Channel]。
 * kind:40 でない・content が JSON でないときは null。
 */
internal fun channelFromCreateEvent(e: NostrEvent): Channel? {
    if (e.kind != KIND_CHANNEL_CREATE) return null
    val meta = runCatching { channelJson.parseToJsonElement(e.content).jsonObject }.getOrNull() ?: return null
    fun field(key: String) = runCatching { meta[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
    return Channel(
        id = e.id,
        name = field("name").orEmpty(),
        about = field("about").orEmpty(),
        pictureUrl = field("picture")?.ifBlank { null },
    )
}
