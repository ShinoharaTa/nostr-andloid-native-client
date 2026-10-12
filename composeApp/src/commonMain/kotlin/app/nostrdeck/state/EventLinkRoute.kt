package app.nostrdeck.state

import app.nostrdeck.crypto.Bech32
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.data.Nip10
import app.nostrdeck.model.NostrEvent

/**
 * [#791] 本文リンク（note / nevent）が指すイベント。nevent なら kind とリレーヒントも入っていることがある。
 * kind は開き先（スレッド / パブリックチャットのルーム）の判定に、リレーヒントは取得に使う。
 */
data class EventLink(
    val id: String,
    val relays: List<String> = emptyList(),
    val kind: Int? = null,
)

/** [#791] NIP-28 の kind。40 = チャンネル作成（イベント id がそのままチャンネル id）。 */
const val KIND_CHANNEL_CREATE = 40

/** [#791] NIP-28 の kind。42 = チャンネルへの発言（root の e タグがチャンネル id）。 */
const val KIND_CHANNEL_MESSAGE = 42

/**
 * [#791] note / nevent の bech32 → [EventLink]。解析できなければ null。
 * id とリレーヒントは [Nip19.eventBechToIdAndRelays] に任せ、ここでは nevent の kind だけを足す。
 */
fun eventLinkOf(bech: String): EventLink? {
    val (id, relays) = Nip19.eventBechToIdAndRelays(bech) ?: return null
    return EventLink(id, relays, neventKindOf(bech))
}

/**
 * [#791] nevent の TLV から kind（type=3・4byte ビッグエンディアン）を取り出す（NIP-19）。
 * note・kind の入っていない nevent・解析できないものは null。
 */
internal fun neventKindOf(bech: String): Int? = runCatching {
    val (hrp, five) = Bech32.decode(bech)
    if (hrp != "nevent") return@runCatching null
    val bytes = Bech32.convertBits(five, 5, 8, false)   // 各要素は 0..255
    var i = 0
    while (i + 2 <= bytes.size) {
        val type = bytes[i]
        val len = bytes[i + 1]
        val start = i + 2
        if (start + len > bytes.size) return@runCatching null
        if (type == 3 && len == 4) {
            return@runCatching (bytes[start] shl 24) or (bytes[start + 1] shl 16) or
                (bytes[start + 2] shl 8) or bytes[start + 3]
        }
        i = start + len
    }
    null
}.getOrNull()

/**
 * [#791] kind:42 の発言が属するチャンネル id。NIP-28 は root マーカーの e タグ（= kind:40 の id）。
 * マーカーの無い古い形は NIP-10 の位置規則で先頭の e（[Nip10.rootOf] と同じ）。無ければ null。
 */
fun channelIdOfMessage(tags: List<List<String>>): String? = Nip10.rootOf(tags)?.takeIf { it.isNotBlank() }

/**
 * [#791] イベントの kind とタグから開き先を決める。
 *  - kind:40 → そのチャンネルのルーム（イベント id = チャンネル id）
 *  - kind:42 → root の e タグのチャンネルのルーム。発言の位置まで送って強調する。チャンネルが分からなければスレッド
 *  - それ以外 → スレッド（従来どおり）
 */
fun detailRouteForEvent(id: String, kind: Int, tags: List<List<String>>): DetailRoute = when (kind) {
    KIND_CHANNEL_CREATE -> DetailRoute.ChannelRoomView(id)
    KIND_CHANNEL_MESSAGE ->
        channelIdOfMessage(tags)?.let { DetailRoute.ChannelRoomView(it, messageId = id) } ?: DetailRoute.ThreadView(id)
    else -> DetailRoute.ThreadView(id)
}

/**
 * [#791] nevent の kind だけで開き先が決まるなら返す（イベントの取得を待たない）。
 * kind:42 はチャンネル id（タグ）が要るので、kind の無いリンクと同じく null（イベントを見て決める）。
 */
fun detailRouteForKindHint(link: EventLink): DetailRoute? = when (val kind = link.kind) {
    null, KIND_CHANNEL_MESSAGE -> null
    else -> detailRouteForEvent(link.id, kind, emptyList())
}

/**
 * [#791] 本文リンクの開き先を決める。nevent の kind → 手元のイベント → 手元のチャンネル一覧 → 取得して待つ、の順。
 * どれでも分からなければスレッド（従来どおり）。取得の待ち時間の上限は [fetch] 側が持つ。
 *
 * kind:40 が手元のイベント（event テーブル）にあるのは、リレーから取り込んだものだけ（[#839] から保存する）。
 * HTTP 由来のチャンネル一覧（channel テーブル）の分は無いので、そこに id があればチャンネルとみなす（[isKnownChannel]）。
 */
suspend fun resolveEventLinkRoute(
    link: EventLink,
    local: suspend (id: String) -> NostrEvent?,
    isKnownChannel: suspend (id: String) -> Boolean,
    fetch: suspend (id: String, relays: List<String>) -> NostrEvent?,
): DetailRoute {
    detailRouteForKindHint(link)?.let { return it }
    local(link.id)?.let { return detailRouteForEvent(link.id, it.kind, it.tags) }
    if (link.kind == null && isKnownChannel(link.id)) return DetailRoute.ChannelRoomView(link.id)
    fetch(link.id, link.relays)?.let { return detailRouteForEvent(link.id, it.kind, it.tags) }
    return DetailRoute.ThreadView(link.id)
}
