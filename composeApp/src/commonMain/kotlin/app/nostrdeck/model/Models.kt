package app.nostrdeck.model

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
    val imageUrl: String? = null,   // imeta 由来。blurhash プレースホルダ前提
)

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
