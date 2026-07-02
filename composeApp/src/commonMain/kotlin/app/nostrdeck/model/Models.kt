package app.nostrdeck.model

import kotlinx.serialization.Serializable

/**
 * ドメインモデル（whiteboard.md の DB スキーマに対応）。
 * commonMain なので Android/iOS 双方で共有される。
 */

/** Nostr イベント (kind:1 等)。SQLDelight `events` テーブルと対応。 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val kind: Int,
    val createdAt: Long,
    val content: String,
    val tags: List<List<String>> = emptyList(),
    val sig: String = "",
)

/** 署名前イベント（content/kind/tags を指定し、id・pubkey・sig・created_at は署名時に確定）。 */
data class UnsignedEvent(
    val kind: Int,
    val content: String,
    val tags: List<List<String>> = emptyList(),
    val createdAt: Long = 0,    // 0 のとき署名時に現在時刻を充填
)

/** kind:0 プロフィール。pubkey ごとに createdAt 最大の1件だけ保持（dedup 済み）。 */
data class Profile(
    val pubkey: String,
    val name: String,
    val handle: String,       // nip05 等
    val pictureUrl: String? = null,
    val updatedAt: Long = 0,
    val about: String = "",        // 自己紹介(bio)
    val website: String? = null,   // website
    val lud16: String? = null,     // lightning address (NIP-57)
    val banner: String? = null,    // ヘッダ画像URL
)

/**
 * Deck の1カラム = 1つの REQ フィルタ。
 * 追加/削除/並べ替えはこの list の操作として表現する。
 *
 * フィード/スレッド/チャンネルルームすべてを ColumnSpec で統一して扱い、
 * [pinned] の有無だけで永続性とレール表示を制御する（whiteboard「統合モデル」）。
 *  - transient(pinned=false): タップで開き ✕ で閉じる一時カラム（スレッド/ルーム）
 *  - pinned(pinned=true)    : SQLDelight に永続化 + 左レールにアイコン常駐・並べ替え可
 * ピン留め = 一時カラムを永続セットへ昇格する操作。
 */
data class ColumnSpec(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: ColumnKind,
    val renderer: ColumnRenderer,
    val filter: ReqFilter,
    val pinned: Boolean = true,
    val order: Int = 0,         // レール/デッキ内の並び順
    val unread: Int = 0,        // レールのバッジ用
)

enum class ColumnKind {
    FOLLOWING, HASHTAG, NOTIFICATIONS, DM, GLOBAL, PROFILE,
    THREAD,         // NIP-10 返信ツリー（ツリー表示・返信ボックス）
    CHANNEL_LIST,   // NIP-28 チャンネル一覧（kind:40/41）
    CHANNEL_ROOM,   // NIP-28 チャンネルルーム（kind:42・チャット表示・下部入力）
}

/**
 * カラムのレンダラー種別。同じカラムでも見た目/並びが違うので分ける。
 *  - FEED   : 逆時系列の読み物（Following / hashtag）
 *  - THREAD : NIP-10 ツリー
 *  - ROOM   : NIP-28 チャット（時系列昇順・最新が下・下部に常設入力）
 */
enum class ColumnRenderer { FEED, THREAD, CHANNEL_LIST, ROOM }

/** Nostr REQ のフィルタ（NIP-01）。カラム購読のライフサイクルの単位。 */
@Serializable
data class ReqFilter(
    val kinds: List<Int> = listOf(1),
    val authors: List<String> = emptyList(),
    val hashtags: List<String> = emptyList(),
    val relays: List<String> = emptyList(),
    val since: Long? = null,
    /** NIP-28: kind:42 を特定チャンネルに絞る #e（チャンネル作成イベント id）。 */
    val channelId: String? = null,
    /** NIP-50 全文検索ワード。 */
    val search: String? = null,
    /** NIP-10 スレッド: 表示の起点となるノート id（タップしたノート）。 */
    val eventId: String? = null,
)

/**
 * NIP-65 リレーリストの1エントリ（Inbox/Outbox モデル）。
 *  - [read]  Inbox  : 自分宛（メンション/リプライ）を読みに行くリレー
 *  - [write] Outbox : 自分の投稿を流すリレー
 *  - [source] 由来 : 'nip65'(kind:10002) | 'default'(初期値) | 'manual'(手動)
 * kind:10002 の `r` タグ（マーカー無し=read+write 両方）に対応。
 */
data class RelayPref(
    val url: String,
    val read: Boolean = true,
    val write: Boolean = true,
    val source: String = "manual",
)

/** 回線種別ティア（whiteboard.md の NetworkPolicy）。Repository 層だけが参照する。 */
enum class NetworkTier { UNMETERED, METERED, CONSTRAINED, OFFLINE }

/** 1ノートの表示用モデル（event + 解決済み profile を束ねたもの）。 */
data class NoteUi(
    val event: NostrEvent,
    val author: Profile,
    val replies: Int = 0,
    val reposts: Int = 0,
    val zapsSats: Long = 0,
    val likes: Int = 0,
    val text: String? = null,       // 画像URL等を除いた表示用本文（null なら event.content）
    val images: List<String> = emptyList(),  // 本文中の画像URL（複数可）。表示はグリッド/カルーセル
    val reactions: List<ReactionUi> = emptyList(),  // [M8-react] NIP-25/30 集約リアクション
    val repostedBy: Profile? = null,  // [M8-repost] kind:6/16 のリポスト主（非nullなら「がリポスト」ヘッダ）
    val repostAt: Long? = null,       // [M8-repost] リポストした時刻（kind:6/16 の created_at）。並びは元投稿でなくこれを使う
    val quoted: NoteUi? = null,       // [M8-repost] NIP-18 引用（q タグ）で参照する埋め込み元ノート
    val replyParent: NoteUi? = null,  // [M10] NIP-10 返信先（解決できた親ノート。返信の文脈表示用）
    val mineReacted: Boolean = false,  // [M8-counts] 自分が♡済み（ハイライト/トグル用）
    val mineReaction: ReactionUi? = null, // 自分が付けたリアクション（非♡ならその絵文字をボタンに表示）
    val mineReposted: Boolean = false, // [M8-counts] 自分がリポスト済み
    val isReply: Boolean = false,      // [M9-profile] kind:1 が #e を持つ返信か（プロフィールのタブ振り分け用）
    val customEmojis: Map<String, String> = emptyMap(), // [M10] NIP-30 本文カスタム絵文字 shortcode→画像URL
)

/**
 * [M8-react] 集約済みリアクション1種（NIP-25 kind:7）。
 *  - [key]      集約キー（正規化済み絵文字 or ":shortcode:"）
 *  - [display]  表示文字列（unicode 絵文字 or ":shortcode:"）
 *  - [count]    同一絵文字の合計数
 *  - [imageUrl] NIP-30 カスタム絵文字の画像 URL（無ければ display を文字表示）
 */
data class ReactionUi(
    val key: String,
    val display: String,
    val count: Int,
    val imageUrl: String? = null,
)

/**
 * 自分のカスタム絵文字1件（NIP-30/NIP-51）。kind:10030 の emoji タグ・参照先 kind:30030 から解決。
 * リアクション送信時は content=":[shortcode]:"、tags に ["emoji", shortcode, url] を付ける。
 */
data class CustomEmoji(val shortcode: String, val url: String)

/** 過去に飛ばした絵文字（used_emoji）。content=Unicode 絵文字 or ":shortcode:"、url はカスタムのみ。 */
data class UsedEmoji(val content: String, val imageUrl: String?)

/** [M10-notif] 通知の種別。 */
enum class NotificationKind { REPLY, MENTION, REACTION, REPOST, ZAP }

/**
 * [M10-notif] 通知一覧の1行。自分(#p)宛のイベントを種別ごとに整形したもの。
 *  - [actor]         アクションした人（返信/リアクション/リポストの主）
 *  - [reaction]      REACTION の絵文字（NIP-25。"+"/空は ❤️ に正規化済み）
 *  - [text]          REPLY/MENTION の本文プレビュー
 *  - [targetSnippet] 対象（＝自分の）ノートの抜粋（解決できた範囲）
 *  - [targetNoteId]  対象ノート id（タップでスレッドを開く）
 */
data class NotificationUi(
    val id: String,
    val kind: NotificationKind,
    val actor: Profile,
    val createdAt: Long,
    val reaction: String? = null,         // REACTION の表示文字（絵文字 or :shortcode:）
    val reactionImageUrl: String? = null, // NIP-30 カスタム絵文字の画像URL（あれば画像表示）
    val text: String? = null,
    val targetSnippet: String? = null,
    val targetNoteId: String? = null,
    /** 対象が NIP-28 チャンネルメッセージ(kind:42)なら、そのチャンネル id（タップで開く先）。 */
    val targetChannelId: String? = null,
    /** ZAP 通知の金額(sats)。 */
    val zapSats: Long? = null,
)

/**
 * [M10] ホームタイムラインに混在表示する1行。通常の投稿(Post)と、自分宛の
 * リアクション/リポスト通知(Notice)を時系列でひとつのリストに混ぜる（nostter 風）。
 */
sealed interface FeedEntry {
    val sortAt: Long
    data class Post(val note: NoteUi) : FeedEntry {
        // リポストは「リポストした時刻」で並べる（元投稿の古い created_at では沈むため）。
        override val sortAt: Long get() = note.repostAt ?: note.event.createdAt
    }
    data class Notice(val notif: NotificationUi) : FeedEntry {
        override val sortAt: Long get() = notif.createdAt
    }
}

/** NIP-28 チャンネル（kind:40 作成 + kind:41 最新メタ）。一覧カラムの行。 */
data class Channel(
    val id: String,         // kind:40 イベント id（kind:42 の #e で参照）
    val name: String,
    val about: String,
    val pictureUrl: String? = null,
    val members: Int = 0,
    val unread: Int = 0,
    val lastMessageBy: String = "",
    val lastMessage: String = "",
)

/** DM 会話一覧の行（NIP-17 想定）。 */
data class DmConversation(
    val pubkey: String,
    val name: String,
    val handle: String,
    val lastMessage: String,
    val unread: Int = 0,
)

/** NIP-28 チャンネルメッセージ（kind:42）の表示用。チャット行。 */
data class ChannelMessage(
    val event: NostrEvent,
    val author: Profile,
    val isMine: Boolean = false,
    val continuation: Boolean = false,  // 直前と同一著者なら頭をまとめる
    val reactions: List<ReactionUi> = emptyList(),  // このメッセージへの集約リアクション（NIP-25）
)

/**
 * NIP-10 スレッドの1行（ツリーを描画順にフラット化したもの）。
 * 本実装では e/p タグから木を組んで深さ優先で並べる。サンプルは固定。
 */
data class ThreadEntry(
    val note: NoteUi,
    val depth: Int = 0,
    val replyToName: String? = null,
    val isRoot: Boolean = false,
    val isFocused: Boolean = false,     // タップ元のノート（ハイライト）
)
