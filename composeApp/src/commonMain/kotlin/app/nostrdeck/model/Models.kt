package app.nostrdeck.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * ドメインモデル（whiteboard.md の DB スキーマに対応）。
 * commonMain なので Android/iOS 双方で共有される。
 */

// [#183] NostrEvent / UnsignedEvent は :nostr-core（app.nostrdeck.model）へ移設。
// 同一パッケージのため参照側の import 変更は不要。

/** kind:0 プロフィール。pubkey ごとに createdAt 最大の1件だけ保持（dedup 済み）。 */
@Immutable
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
    FAVS,           // [#12] ふぁぼ欄: 自分がリアクション(kind:7)した投稿の一覧
    LIST,           // [#385] NIP-51 フォローセット(kind:30000)のメンバーのタイムライン
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
    /**
     * [#135] キーワード・タグフィードの単語条件（1語ずつ・NIP-50）。
     * 非空ならキーワード・タグフィードとして扱い、[hashtags] と OR で並べる
     * （役割が近い単語/タグを1カラムに集約するフィード）。
     */
    val words: List<String> = emptyList(),
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
@Immutable
data class NoteUi(
    val event: NostrEvent,
    val author: Profile,
    val replies: Int = 0,
    val reposts: Int = 0,
    val zapsSats: Long = 0,
    val likes: Int = 0,
    // 表示用本文（メディアURL除去済み）。null は「未処理＝event.content をそのまま表示」で、
    // SampleData 等が使う。**メディアのみで本文が残らない投稿は空文字**（null にすると
    // 消費側の `text ?: content` フォールバックが生URLを復活させてしまう [#326]）。
    val text: String? = null,
    val images: List<String> = emptyList(),  // 本文中の画像URL（複数可）。表示はグリッド/カルーセル
    val reactions: List<ReactionUi> = emptyList(),  // [M8-react] NIP-25/30 集約リアクション
    val repostedBy: Profile? = null,  // [M8-repost] kind:6/16 のリポスト主（非nullなら「がリポスト」ヘッダ）
    val repostAt: Long? = null,       // [M8-repost] リポストした時刻（kind:6/16 の created_at）。並びは元投稿でなくこれを使う
    val repostId: String? = null,     // [#61] リポストイベント(kind:6/16)自身の id。元投稿のコピーを一意化するキー（元投稿は null）
    val quoted: NoteUi? = null,       // [M8-repost] NIP-18 引用（q タグ）で参照する埋め込み元ノート
    val replyParent: NoteUi? = null,  // [M10] NIP-10 返信先（解決できた親ノート。返信の文脈表示用）
    val mineReacted: Boolean = false,  // [M8-counts] 自分が♡済み（ハイライト/トグル用）
    val mineReaction: ReactionUi? = null, // 自分が付けたリアクション（非♡ならその絵文字をボタンに表示）
    val mineReposted: Boolean = false, // [M8-counts] 自分がリポスト済み
    val isReply: Boolean = false,      // [M9-profile] kind:1 が #e を持つ返信か（プロフィールのタブ振り分け用）
    val customEmojis: Map<String, String> = emptyMap(), // [M10] NIP-30 本文カスタム絵文字 shortcode→画像URL
    val imeta: Map<String, ImetaInfo> = emptyMap(),     // [#140] NIP-92 メディアURL→(thumb/dim/blurhash)。プレースホルダ用
    /**
     * [#312] NIP-89 `["client", "<名前>", …]` の名前。どのアプリから投稿されたか。
     * **手元の実データでは kind:1 の 58% がこのタグを持たない**ので null が普通。
     * null のときは何も表示しない（「不明」等のプレースホルダは出さない）。
     */
    val clientName: String? = null,
    val contentWarning: String? = null, // [#5] NIP-36 content-warning（非nullなら表示前に折りたたむ。""=理由なし）
    /**
     * [#380] kind:1111（NIP-22 コメント）で親を NoteUi に解決できなかったときのルート参照。
     * NoteItem がこれから「kind X へのコメント」「<host> へのコメント」等の汎用文脈行を出す
     * （replyParent が解決できたときは通常の返信元1行プレビューが出るので null）。
     */
    val commentRoot: CommentRootRef? = null,
)

/** [#380] NIP-22 コメントのルート参照（E=イベントid / A=アドレス / I=外部識別子 / K=kind）。 */
@Immutable
data class CommentRootRef(
    val eventId: String? = null,   // ルート/親のイベント id（タップでスレッドを開ける）
    val address: String? = null,   // "kind:pubkey:d"（記事等の addressable）
    val external: String? = null,  // URL 等の外部識別子（NIP-73）
    val kind: Int? = null,         // ルートの kind（K タグ。外部識別子では null）
)

/**
 * [M8-react] 集約済みリアクション1種（NIP-25 kind:7）。
 *  - [key]      集約キー（正規化済み絵文字 or ":shortcode:"）
 *  - [display]  表示文字列（unicode 絵文字 or ":shortcode:"）
 *  - [count]    同一絵文字の合計数
 *  - [imageUrl] NIP-30 カスタム絵文字の画像 URL（無ければ display を文字表示）
 */
@Immutable
data class ReactionUi(
    val key: String,
    val display: String,
    val count: Int,
    val imageUrl: String? = null,
)

/** [#270] 投稿詳細に出す対象ノートへの反応数（リプライ/リポスト）。 */
@Immutable
data class NoteEngagement(
    val replies: Int = 0,
    val reposts: Int = 0,
)

/** [#254] 投稿詳細: リアクション1種（絵文字）と、した人の一覧（新しい順・重複なし）。 */
@Immutable
data class ReactionGroupUi(
    val display: String,
    val imageUrl: String? = null,
    val people: List<Profile> = emptyList(),
)

/**
 * 自分のカスタム絵文字1件（NIP-30/NIP-51）。kind:10030 の emoji タグ・参照先 kind:30030 から解決。
 * リアクション送信時は content=":[shortcode]:"、tags に ["emoji", shortcode, url] を付ける。
 */
data class CustomEmoji(val shortcode: String, val url: String)

/** 過去に飛ばした絵文字（used_emoji）。content=Unicode 絵文字 or ":shortcode:"、url はカスタムのみ。 */
data class UsedEmoji(val content: String, val imageUrl: String?)

/** [M10-notif] 通知の種別。 */
// [#419] DM = 自分宛の NIP-17/NIP-04 メッセージ受信（復号後の kind:14）。
enum class NotificationKind { REPLY, MENTION, REACTION, REPOST, ZAP, DM }

/**
 * [NIP-42] リレーの AUTH 要求への応答ポリシー。
 * OFF=応答しない / DM_AND_MINE=自分のリレー・DMリレーのみ(既定) / ALWAYS=常に応答。
 */
enum class AuthPolicy { OFF, DM_AND_MINE, ALWAYS }

/**
 * [M18] フォロー中TLに混ぜる通知系の表示カテゴリ。カラムの ⋯ メニューから個別に表示/非表示できる。
 * REACTIONS=自分へのリアクション / REPLIES=自分への返信・メンション / REPOSTS=自分へのリポスト /
 * MY_REACTIONS=自分がしたリアクション。
 */
enum class FeedNoticeCategory { REACTIONS, REPLIES, REPOSTS, MY_REACTIONS }

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
    /** [#254] 対象（自分の投稿）の著者。通知の1行プレビューにアバターを出すために解決済みで持つ。 */
    val targetAuthor: Profile? = null,
    /**
     * [#298] 通知の本体として描くノート。
     *  - REPLY/MENTION … 相手の返信そのもの（投稿と同じフォーマットで出す）
     *  - それ以外        … null（本体は [targetNote]）
     */
    val note: NoteUi? = null,
    /** [#298] 対象（＝自分の投稿）。リポスト/リアクション/Zap の本体を引用カードで出すのに使う。 */
    val targetNote: NoteUi? = null,
    /** 対象が NIP-28 チャンネルメッセージ(kind:42)なら、そのチャンネル id（タップで開く先）。 */
    val targetChannelId: String? = null,
    /** ZAP 通知の金額(sats)。 */
    val zapSats: Long? = null,
    /** [#419] DM 通知の未読件数（1会話=1行にまとめているので件数を持つ）。 */
    val dmUnread: Int = 0,
)

/**
 * [M10] ホームタイムラインに混在表示する1行。通常の投稿(Post)と、自分宛の
 * リアクション/リポスト通知(Notice)を時系列でひとつのリストに混ぜる（nostter 風）。
 */
sealed interface FeedEntry {
    val sortAt: Long
    data class Post(val note: NoteUi) : FeedEntry {
        // [#61] リポストのコピー（repostId 非null）は「リポストした時刻」で並べる。
        // 元投稿(repostId=null)は自分の created_at のまま動かさない（リポストで元が移動しない）。
        override val sortAt: Long get() = note.repostAt ?: note.event.createdAt
    }
    data class Notice(val notif: NotificationUi) : FeedEntry {
        override val sortAt: Long get() = notif.createdAt
    }
    /** [M16] 自分が付けたリアクション（kind:7）と、その宛先の投稿。TL に「自分が◯◯にリアクション」と出す。 */
    data class MyReaction(val reaction: ReactionUi, val target: NoteUi, val reactedAt: Long) : FeedEntry {
        override val sortAt: Long get() = reactedAt
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

/** [M13] NIP-57 Zap 受領(kind:9735)の表示用。スレッドで「誰がいくら Zap したか」をリプライ風に出す。 */
data class ZapUi(
    val id: String,
    val zapper: Profile,
    val sats: Long,
    val comment: String,
    val createdAt: Long,
)

/** DM 会話一覧の行（NIP-17 想定）。 */
data class DmConversation(
    val pubkey: String,
    val name: String,
    val handle: String,
    val lastMessage: String,
    val pictureUrl: String? = null,
    val unread: Int = 0,
    /** [#419] 相手の最新発言の created_at（DM 通知を時系列に並べる位置）。 */
    val lastIncomingAt: Long = 0,
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
