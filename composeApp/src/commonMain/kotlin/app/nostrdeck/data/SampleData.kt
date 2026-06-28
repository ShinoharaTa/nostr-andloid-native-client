package app.nostrdeck.data

import app.nostrdeck.model.Channel
import app.nostrdeck.model.ChannelMessage
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.DmConversation
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.model.ThreadEntry

/**
 * UI を動かすための仮データ。
 * TODO(whiteboard): ここを SQLDelight + リレー購読の Repository に差し替える。
 *   - 読みはローカル DB のみ（SSOT / stale-while-revalidate）
 *   - kind:0 はバッチ + アウトボックス(NIP-65) で解決
 */
object SampleData {

    // ピン留め済み（永続）カラム。レールにも並ぶ。
    val columns = listOf(
        ColumnSpec("c_following", "フォロー中", "following · 2 relays",
            ColumnKind.FOLLOWING, ColumnRenderer.FEED, ReqFilter(kinds = listOf(1)), order = 0),
        ColumnSpec("c_hashtag", "#nostr", "hashtag · global",
            ColumnKind.HASHTAG, ColumnRenderer.FEED,
            ReqFilter(kinds = listOf(1), hashtags = listOf("nostr")), order = 1),
        ColumnSpec("c_channel", "Nostr Japan", "📌 NIP-28 room",
            ColumnKind.CHANNEL_ROOM, ColumnRenderer.ROOM,
            ReqFilter(kinds = listOf(42), channelId = "ch_njp"), order = 2, unread = 12),
        ColumnSpec("c_notif", "通知", "mentions · zaps",
            ColumnKind.NOTIFICATIONS, ColumnRenderer.FEED,
            ReqFilter(kinds = listOf(1, 7, 9735)), order = 3),
    )

    /** チャンネル一覧カラム（list アイコンから開く一時カラム）。 */
    val channelListColumn = ColumnSpec(
        "c_channels", "パブリックチャット", "NIP-28 · channels",
        ColumnKind.CHANNEL_LIST, ColumnRenderer.CHANNEL_LIST, ReqFilter(kinds = listOf(40, 41)),
        pinned = false, order = 99,
    )

    private val names = listOf(
        "jack" to "jack@cash.app", "fiatjaf" to "fiatjaf@nostr.com",
        "gigi" to "dergigi@gigi.np", "pablof7z" to "pablo@f7z.io",
        "カリン" to "karin@nostr.jp", "ODELL" to "odell@citadel",
    )

    private val bodies = listOf(
        "nostr is the protocol the web should have shipped in 1994. signed events, no gatekeeper.",
        "フォルダブルで開くと複数カラム見えるの、Deck系の本領。畳むと普通のSNSに戻るのが理想。",
        "just zapped this dev 5000 sats for the relay patch ⚡ value-for-value works.",
        "reminder: your npub is your identity. back up your nsec.",
        "the outbox model (NIP-65) finally clicked. fetch from where people actually write.",
    )

    private fun profile(name: String, handle: String) = Profile(pubkey = "pk_$name", name = name, handle = handle)

    private fun note(seed: Int, i: Int): NoteUi {
        val (name, handle) = names[(seed + i) % names.size]
        return NoteUi(
            event = NostrEvent(
                id = "evt_${seed}_$i", pubkey = "pk_$name", kind = 1,
                createdAt = 1_750_000_000 - (seed + i) * 137L,
                content = bodies[(seed + i) % bodies.size],
            ),
            author = profile(name, handle),
            replies = 3 + (i * 7) % 90, reposts = 9 + (i * 13) % 200,
            zapsSats = 1000L + (i * 800), likes = 30 + (i * 17) % 400,
            images = if ((seed + i) % 4 == 3) listOf("https://example/img_$i.jpg") else emptyList(),
        )
    }

    fun feed(seed: Int): List<NoteUi> = List(8) { note(seed, it) }

    /** カラム種別に応じた仮フィード。 */
    fun feedFor(spec: ColumnSpec): List<NoteUi> = feed(spec.title.length)

    // ---- NIP-10 スレッド（タップしたノートの文脈） ----
    fun thread(rootSeed: Int = 0): List<ThreadEntry> = listOf(
        ThreadEntry(note(rootSeed, 0), depth = 0, isRoot = true),
        ThreadEntry(note(rootSeed, 4), depth = 1, replyToName = "jack", isFocused = true),
        ThreadEntry(note(rootSeed, 2), depth = 2, replyToName = "gigi"),
        ThreadEntry(note(rootSeed, 1), depth = 1, replyToName = "jack"),
        ThreadEntry(note(rootSeed, 3), depth = 2, replyToName = "fiatjaf"),
    )

    fun threadColumnFor(note: NoteUi) = ColumnSpec(
        id = "thread_${note.event.id}", title = "スレッド", subtitle = "一時表示 · NIP-10",
        kind = ColumnKind.THREAD, renderer = ColumnRenderer.THREAD,
        filter = ReqFilter(kinds = listOf(1), eventId = note.event.id), pinned = false, order = 100,
    )

    // ---- NIP-28 チャンネル ----
    val channels = listOf(
        Channel("ch_njp", "Nostr Japan", "日本語のNostr雑談", members = 340, unread = 12,
            lastMessageBy = "カリン", lastMessage = "次のミートアップ来る人ー？"),
        Channel("ch_btc", "Bitcoin Talk", "price & tech", members = 1280, unread = 3,
            lastMessageBy = "ODELL", lastMessage = "self-custody is not optional"),
        Channel("ch_dev", "#dev-help", "client devs hangout", members = 92,
            lastMessageBy = "pablof7z", lastMessage = "NIP-28 の kind:42 はここ"),
        Channel("ch_photo", "Photography", "📷 share your shots", members = 210, unread = 1,
            lastMessageBy = "みやび", lastMessage = "桜の写真あげました"),
        Channel("ch_relay", "Relay Ops", "running your own relay", members = 58,
            lastMessageBy = "sat", lastMessage = "negentropy 効くと爆速"),
    )

    private val roomTexts = listOf(
        "次のミートアップ来る人ー？", "渋谷だっけ？", "21日の19時〜です 🍻", "行きます！",
        "kind:42 のメッセージ、ここに流れる", "NIP-10 マーカーで返信もできるよ",
        "モデレーションは kind:43/44 ね", "了解、実装する",
    )

    fun roomMessages(channelId: String): List<ChannelMessage> = List(8) { i ->
        val (name, handle) = names[i % names.size]
        val mine = i % 3 == 0 && i > 0
        ChannelMessage(
            event = NostrEvent(
                id = "msg_${channelId}_$i", pubkey = "pk_$name", kind = 42,
                createdAt = 1_750_000_000 + i * 60L, content = roomTexts[i % roomTexts.size],
                tags = listOf(listOf("e", channelId, "", "root")),
            ),
            author = profile(if (mine) "あなた" else name, handle),
            isMine = mine,
            continuation = i > 0 && (i % 4 != 0),
        )
    }

    fun roomColumnFor(channel: Channel) = ColumnSpec(
        id = "room_${channel.id}", title = channel.name, subtitle = "👤 ${channel.members} · kind:42",
        kind = ColumnKind.CHANNEL_ROOM, renderer = ColumnRenderer.ROOM,
        filter = ReqFilter(kinds = listOf(42), channelId = channel.id),
        pinned = false, order = 100, unread = channel.unread,
    )

    fun channelById(id: String): Channel? = channels.firstOrNull { it.id == id }

    // ---- DM（NIP-17 想定） ----
    val dmConversations = names.mapIndexed { i, (name, handle) ->
        DmConversation(
            pubkey = "pk_$name", name = name, handle = handle,
            lastMessage = bodies[i % bodies.size].take(28),
            unread = if (i % 3 == 0) i % 4 else 0,
        )
    }

    fun dmMessages(pubkey: String): List<ChannelMessage> = List(6) { i ->
        val (name, handle) = names[i % names.size]
        val mine = i % 2 == 0
        ChannelMessage(
            event = NostrEvent(
                id = "dm_${pubkey}_$i", pubkey = pubkey, kind = 1059,
                createdAt = 1_750_000_000 + i * 60L,
                content = listOf("やあ", "NIP-17 の DM だよ", "了解！", "あとで送る", "👍", "ありがとう")[i % 6],
            ),
            author = profile(if (mine) "あなた" else name, handle),
            isMine = mine, continuation = false,
        )
    }

    // ---- 設定（左メニュー / 右内容） ----
    val settingsSections = listOf(
        "account" to "アカウント",
        "signer" to "ログイン方法",
        "relays" to "リレー",
        "appearance" to "表示",
        "about" to "このアプリについて",
    )
}
