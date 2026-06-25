package app.nostrdeck.data

import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReqFilter

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

    fun feed(seed: Int): List<NoteUi> = List(8) { i ->
        val (name, handle) = names[(seed + i) % names.size]
        NoteUi(
            event = NostrEvent(
                id = "evt_${seed}_$i",
                pubkey = "pk_$name",
                kind = 1,
                createdAt = 1_750_000_000 - (seed + i) * 137L,
                content = bodies[(seed + i) % bodies.size],
            ),
            author = Profile(pubkey = "pk_$name", name = name, handle = handle),
            replies = 3 + (i * 7) % 90,
            reposts = 9 + (i * 13) % 200,
            zapsSats = 1000L + (i * 800),
            likes = 30 + (i * 17) % 400,
            imageUrl = if ((seed + i) % 4 == 3) "https://example/img_$i.jpg" else null,
        )
    }
}
