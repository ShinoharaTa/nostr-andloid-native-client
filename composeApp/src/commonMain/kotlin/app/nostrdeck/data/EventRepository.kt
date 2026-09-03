package app.nostrdeck.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import app.nostrdeck.crypto.Bech32
import app.nostrdeck.crypto.EventCrypto
import app.nostrdeck.crypto.Nip01
import app.nostrdeck.crypto.Nip17
import app.nostrdeck.crypto.currentUnixTime
import kotlin.random.Random
import app.nostrdeck.db.Event
import app.nostrdeck.db.NostrDb
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.crypto.Nip57
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.util.encodeBase64
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import app.nostrdeck.ui.extractMedia
import app.nostrdeck.model.ChannelMessage
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.CustomEmoji
import kotlin.coroutines.cancellation.CancellationException
import app.nostrdeck.model.TextScale
import app.nostrdeck.model.ThemeMode
import app.nostrdeck.model.UiScale
import app.nostrdeck.model.NoteAccentStyle
import app.nostrdeck.model.NyanMode
import app.nostrdeck.model.CustomThemePrefs
import app.nostrdeck.model.ThemeEntry
import app.nostrdeck.model.ImageCompressionPrefs
import app.nostrdeck.model.VideoCompressionPrefs
import app.nostrdeck.model.DmConversation
import app.nostrdeck.model.MuteCategory
import app.nostrdeck.model.EmbedPrefs
import app.nostrdeck.model.MuteEntry
import app.nostrdeck.model.MuteList
import app.nostrdeck.model.NetworkTier
import app.nostrdeck.model.OgpData
import app.nostrdeck.ui.ImageProxy
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.ContentToken
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.latestByDTag
import app.nostrdeck.model.Nip51Set
import app.nostrdeck.model.PinnedCache
import app.nostrdeck.model.PinnedHashtags
import app.nostrdeck.model.PinnedReconcile
import app.nostrdeck.model.reconcile
import app.nostrdeck.model.UsedHashtag
import app.nostrdeck.model.parseNip51Sets
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.tokenizeNostrContent
import app.nostrdeck.model.AuthPolicy
import app.nostrdeck.model.FeedNoticeCategory
import app.nostrdeck.model.NotificationKind
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReactionUi
import app.nostrdeck.model.RelayPref
import app.nostrdeck.model.nip65PrefsFromTags
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.model.ThreadEntry
import app.nostrdeck.model.UnsignedEvent
import app.nostrdeck.model.UsedEmoji
import app.nostrdeck.nostr.Filter
import app.nostrdeck.nostr.RelayClient
import app.nostrdeck.nostr.RelayConn
import app.nostrdeck.nostr.RelayConnState
import app.nostrdeck.nostr.RelayMessage
import app.nostrdeck.nostr.RelayProtocol
import app.nostrdeck.nostr.RelayTraffic
import app.nostrdeck.signer.SignerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * SSOT リポジトリ。リレー購読→検証→DB 書き込み、読みは DB の Flow。
 * 各カラムは [subscribeColumn]/[unsubscribeColumn] で自分のフィルタを REQ（= カラム=REQ ライフサイクル）。
 */
/** [#364] 開発者モードの接続モニタに渡すスナップショット。 */
data class ConnMonitorSnapshot(
    val tier: NetworkTier,
    val bgPausedCount: Int,
    val lastBgPausedAtSec: Long,
    val relays: List<RelayTraffic>,
    val reqs: List<ActiveReqUi>,
)

/** [#364] 生きている購読(REQ)1件。[targets]=null は全リレー向け。 */
data class ActiveReqUi(val subId: String, val targets: List<String>?, val filters: List<Filter>)

class EventRepository(
    private val db: NostrDb,
    private val scope: CoroutineScope,
    relayUrls: List<String>,
) {
    private val q = db.nostrQueries
    private val bootstrapUrls = relayUrls
    /** 接続中リレー（url→client）。NIP-65/手動で動的に増減する。 */
    private val relays = LinkedHashMap<String, RelayClient>()
    /**
     * [#50] 設定リストで read(Inbox) 有効なリレー（=常設接続すべき集合、正規化 URL）。
     * 接続数(N/M)・ステータス一覧はこの集合だけを対象にし、インデクサ/ヒント等の一時接続は数えない。
     */
    private var listRelays: Set<String> = emptySet()
    /** 新規リレー接続時に張り直すための購読中フィルタ（subId→filters）。 */
    private val activeSubs = mutableMapOf<String, List<Filter>>()
    /** relays / activeSubs への全アクセスを直列化する単一スレッド相当のディスパッチャ（CME 回避）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val relayDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * [#20/#21] KV(app_setting) 書き込みを UI スレッドから外す。
     * SQLite 書き込みは取り込みトランザクションと DB ロックを奪い合い、UI スレッドで同期実行すると
     * タップ応答が詰まる。呼び出し側で StateFlow を即時更新し、永続化はここで後追いする。
     * relayDispatcher(単一スレッド) 上で直列化して実行する。
     */
    private fun putSettingAsync(key: String, value: String) {
        scope.launch(relayDispatcher) { q.putSetting(key, value) }
    }

    /** 解決済みプロフィール（pubkey→Profile 行）。各フィードと combine して名前/アバターを反映。 */
    private val profilesFlow = q.allProfiles().asFlow().mapToList(Dispatchers.Default)

    /**
     * [#96] ユーザーリスト（フォロー中/フォロワー一覧）用の共有プロフィールマップ。
     * 一覧表示直後は kind:0 が数百件届く。行ごとに DB クエリ listener を張ると
     * profile テーブル変更のたびに全行が再クエリ→UI 更新の嵐になるため、
     * 全体テーブルの1本を 400ms サンプリングして「まとめて」流す。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun profilesMapSampled(): Flow<Map<String, app.nostrdeck.db.Profile>> =
        profilesFlow.sample(400).map { list -> list.associateBy { it.pubkey } }
            .flowOn(Dispatchers.Default)

    /**
     * [#388-review] 指定 pubkey 群のプロフィール（pubkey→行）。プロフィールの「リスト」タブで
     * 展開中セットのメンバー（高々数百）を引くのに使う。全プロフィール表を流す
     * [profilesMapSampled] と違い、対象外の kind:0 受信では発火しない。
     */
    fun profilesByPubkeysFlow(pubkeys: List<String>): Flow<Map<String, app.nostrdeck.db.Profile>> =
        if (pubkeys.isEmpty()) flowOf(emptyMap())
        else q.profilesByPubkeys(pubkeys).asFlow().mapToList(Dispatchers.Default)
            .map { list -> list.associateBy { it.pubkey } }
            .flowOn(Dispatchers.Default)

    // [M10] リアクション数/リプライ数/リポスト数の集約はタイムライン表示に不要（数字は出さない）。
    // 集計クエリ(reactionsForTargets/engagementForTargets)は購読/DBを無駄に使うため使用しない。
    // 自分宛のリアクション/リポストは通知としてタイムラインに混ぜ込む（notificationsFeed）。

    /** ログイン中ユーザーの公開鍵（kind:3 の自分判定とフォロー解決に使う）。 */
    private var myPubkey: String? = null
    /** [M8-counts] 自分の公開鍵を Flow でも公開（♡/リポスト済み判定が鍵切替に追従するため）。 */
    private val myPubkeyFlow = MutableStateFlow<String?>(null)

    /** 自分の全 kind:7 行（note_id / content / tags_json）。♡状態と自分リアクション表示の元。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val myReactionRowsFlow = myPubkeyFlow.flatMapLatest { pk ->
        if (pk == null) flowOf(emptyList()) else q.myReactionsForNotes(pk).asFlow().mapToList(Dispatchers.Default)
    }

    /** ユーザー設定のデフォルトリアクション（content, imageUrl）。♡ボタンで送る内容。 */
    private val defaultReactionState = MutableStateFlow("+" to null as String?)
    fun defaultReactionFlow(): StateFlow<Pair<String, String?>> = defaultReactionState
    /** デフォルトリアクションの正規化 content（"+"/空→"❤️"、それ以外はそのまま）。DB照合・表示判定用。 */
    private fun normalizedDefaultReaction(): String =
        defaultReactionState.value.first.let { if (it == "+" || it.isEmpty()) "❤️" else it }

    /**
     * [M8/M16] ♡が押された状態＝「自分がデフォルトリアクションを付けたノート」集合。
     * デフォルトの content を変えると追従する（設定で☆等に変更しても正しくハイライト）。
     */
    private val myReactedFlow: Flow<Set<String>> =
        combine(myReactionRowsFlow, defaultReactionState) { rows, def ->
            val target = if (def.first == "+" || def.first.isEmpty()) "❤️" else def.first
            rows.filter { it.content == target }.map { it.note_id }.toSet()
        }

    /** [M8-counts] 自分がリポスト済みのノート id 集合。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val myRepostedFlow: Flow<Set<String>> = myPubkeyFlow.flatMapLatest { pk ->
        if (pk == null) flowOf(emptySet())
        else q.myRepostedNoteIds(pk).asFlow().mapToList(Dispatchers.Default).map { it.toSet() }
    }

    /** 自分が各ノートに付けたリアクション（note_id→ReactionUi）。集約表示等に使う。 */
    private val myReactionMapFlow: Flow<Map<String, ReactionUi>> = myReactionRowsFlow.map { rows ->
        rows.associate { it.note_id to normalizeReaction(it.content, parseTags(it.tags_json)) }
    }

    /** [M10] フィードに載せるメタ: 自分が♡/リポスト済みか + 自分のリアクション絵文字。 */
    private val noteMetaFlow: Flow<NoteMeta> =
        combine(myReactedFlow, myRepostedFlow, myReactionMapFlow) { mr, mp, rx -> NoteMeta(mr, mp, rx) }

    /** 自分の kind:3 由来のフォロー集合（p タグ）。FOLLOWING カラムの authors。 */
    private val follows = MutableStateFlow<List<String>>(emptyList())
    private var followsAt = 0L

    // ---- [#396] 自分の replaceable リスト（購読・受信ゲート・State+KV・リセットを OwnReplaceable に共通化）----

    private val ownListStore = object : OwnListStore {
        override fun get(key: String): String? = q.getSetting(key).executeAsOneOrNull()
        override fun put(key: String, value: String) = putSettingAsync(key, value)
    }

    /**
     * 自分の kind:10002（NIP-65）。State は受信/発行した版の r タグから導出（Settings の編集は DB の relay 表）。
     * KV バックアップは持たない（relay 表が置き場）。
     */
    private val relayListRep = OwnReplaceable(kind = 10002, subId = "relaylist") { tags ->
        // [#390] 他人の 10002 表示と同じ解釈（marker の trim+lowercase / URL の重複畳み）。
        // 自分の設定はローカル開発リレー（ws://）を壊さないよう wss:// 以外も従来どおり通す。
        nip65PrefsFromTags(tags, requireWss = false) { normalizeRelayUrl(it) }
    }

    /** 自分の kind:10002（NIP-65）由来のリレーリスト（受信/発行した版の r タグから導出）。 */
    val relayList: StateFlow<List<RelayPref>> get() = relayListRep.state

    /**
     * 自分の kind:10030（NIP-51 絵文字リスト）。[#287] 生タグを保持して再発行時に a タグ等の未知タグを
     * 失わない。KV に永続し起動時に復元（旧形式＝タグ配列のみ、も読める）。
     */
    private val emojiListRep = OwnReplaceable(
        kind = 10030, subId = "emojilist",
        backupKey = EMOJI_LIST_TAGS_KEY, store = ownListStore, json = json,
        legacyDecode = { raw -> OwnListBackup(json.decodeFromString(ListSerializer(ListSerializer(String.serializer())), raw), 0L) },
        derive = ::emojiTagsToList,
    )

    /**
     * [#393] 自分のピン留めハッシュタグ（kind:30015 / d=pinned）。KV に永続し起動時に復元
     * （旧形式＝PinnedCache も読める）。楽観更新では発行前に at を「今」へ進める。
     */
    private val pinnedRep = OwnReplaceable(
        kind = PinnedHashtags.KIND, subId = "pinnedtags", dTag = PinnedHashtags.D_TAG,
        backupKey = PINNED_HASHTAGS_KEY, store = ownListStore, json = json,
        legacyDecode = { raw ->
            json.decodeFromString(PinnedCache.serializer(), raw).let { OwnListBackup(PinnedHashtags.toTags(it.tags), it.at) }
        },
        derive = { PinnedHashtags.fromTags(it) },
    )
    private val ownLists: List<OwnReplaceable<*>> get() = listOf(relayListRep, emojiListRep, pinnedRep)

    private var pinnedPublishJob: Job? = null
    /** デバウンス発行が失敗したときに戻す版（未発行の編集が始まった時点の確定版）。発行成功で null。 */
    private var pinnedRollback: PinnedCache? = null
    /** KV 復元直後は「ローカルが正」の可能性がある（未発行のまま終了）。初回受信で1回だけ再発行を許す。 */
    private var pinnedRepublishArmed = false
    private val pinnedHashtagsErrors = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // [#359] 回線種別（Wi-Fi/モバイル）。画質・埋め込みの節約分岐が購読する。
    private val networkPolicy = NetworkPolicy()
    private val _networkTier = MutableStateFlow(NetworkTier.UNMETERED)

    /** [#359] 現在の回線種別。UNMETERED=Wi-Fi等 / METERED=モバイル / CONSTRAINED=データセーバー。 */
    fun networkTierFlow(): StateFlow<NetworkTier> = _networkTier.asStateFlow()

    fun start() {
        // [#359] 回線種別を監視し、従量制回線では画像プロキシの幅/品質を自動で下げる。
        scope.launch {
            networkPolicy.tier.collect { tier ->
                _networkTier.value = tier
                ImageProxy.dataSaver = tier == NetworkTier.METERED || tier == NetworkTier.CONSTRAINED
            }
        }
        // [M15] 過去タイムラインはキャッシュしない: 起動毎に DM 以外のイベントを解放し、
        // リレーから読み直す。コールド起動を軽く保ち、DB を溜め込まない。
        q.transaction { q.clearTimelineEvents(); q.clearOrphanTags() }
        // 末尾スラッシュ違い（例: nos.lol と nos.lol/）で二重登録された既存行を一度だけ統合する。
        dedupeRelayUrls()
        // [#368] 古い OGP キャッシュを掃除（14日超。TL に流れた URL の数だけ増えるため）。
        q.purgeOgpCache(currentUnixTime() - OGP_PURGE_SEC)
        // ブートストラップ・リレーは**初回（リレー表が空）のみ** seed する。
        // 以前は毎回 insert+接続していたため、設定で削除した default リレーが再起動で復活していた。
        if (q.allRelays().executeAsList().isEmpty()) {
            bootstrapUrls.forEach { url ->
                val u = normalizeRelayUrl(url)
                q.insertRelayIfAbsent(u, 1, 1, "default")
                ensureRelay(u)
            }
        }
        // 永続化済みリレーのうち read(Inbox) を有効にしたものだけ購読接続する。
        // write 専用(Outbox)リレーは購読せず、配信時に一時接続して EVENT を送る（NIP-65 outbox）。
        scope.launch {
            q.allRelays().asFlow().mapToList(Dispatchers.Default).collect { rows ->
                // [#50] 常設接続すべき集合＝read 有効なリスト由来リレー。N/M 表示の分母もこれ。
                listRelays = rows.filter { it.read != 0L }.map { normalizeRelayUrl(it.url) }.toSet()
                rows.forEach { if (it.read != 0L) ensureRelay(it.url) }
                // リストが変わったらステータス表示を更新（外れたリレーは一覧から消す）。
                withContext(relayDispatcher) { refreshRelayConns() }
            }
        }
        // [M11] 既定のメディアサーバ(NIP-96)を投入（既にあれば触らない）。
        DEFAULT_MEDIA_SERVERS.forEachIndexed { i, url -> q.insertMediaServerIfAbsent(url, 1, i.toLong()) }
        // NIP-28 チャンネル一覧を取得（ピン留めルームが起動直後にチャンネルのリレーへ繋げるよう先に）。
        scope.launch { refreshChannels() }
        scope.launch { profileBatchLoop() }
        // ミュートリスト(kind:10000)を常時購読（フィルタは全カラムで常に有効）。
        subscribeMuteList("mute_global")
        // カラム別「ミュートを表示」設定を KV から復元。
        revealMutedFlow.value = q.settingsByPrefix(REVEAL_MUTED_PREFIX).executeAsList()
            .filter { it.value_ == "1" }
            .map { it.key.removePrefix(REVEAL_MUTED_PREFIX) }.toSet()
        // カラム別「自分への反応を隠す」設定を KV から復元。
        hideSelfNoticesFlow.value = q.settingsByPrefix(HIDE_SELF_NOTICES_PREFIX).executeAsList()
            .filter { it.value_ == "1" }
            .map { it.key.removePrefix(HIDE_SELF_NOTICES_PREFIX) }.toSet()
        // [M18] カラム別「非表示にする通知系カテゴリ」を KV から復元。
        loadHiddenCategories()
        // [#10] カラム別の幅を KV から復元。
        loadColumnWidths()
        // [#27] 検索履歴を KV から復元。
        loadSearchHistory()
        // リンク埋め込み設定（OGP/YouTube/Spotify）を KV から復元。
        loadEmbedPrefs()
        // 文字サイズ（小/中/大）を KV から復元。
        loadTextScale()
        // 表示サイズ（標準/大きめ/最大）を KV から復元。
        loadUiScale()
        loadBoldText()   // [#327]
        loadNyanMode()   // [#378]
        loadDeveloperMode()   // [#351]
        loadNoteAccentStyle()
        loadCustomTheme()
        loadImageCompression()
        loadVideoCompression()
        // テーマ（OSに合わせる/ライト/ダーク）を KV から復元。
        loadThemeMode()
        // デフォルトリアクション（♡ボタンの送信内容）を KV から復元。
        loadDefaultReaction()
        // [#287][#393] 絵文字リスト / ピン留めハッシュタグを KV から復元（リレーから届く前・オフラインでも使える）。
        restoreOwnLists()
        // [NIP-42] AUTH 応答ポリシーを KV から復元。
        loadAuthPolicy()
        // [#9] 通知/DM の最終閲覧時刻を KV から復元。
        loadUnreadSeen()
        scope.launch { eventBatchLoop() }
        // 受信イベントの取り込みループ（バッチ検証＋1トランザクション書き込み）。
        scope.launch { ingestLoop() }
        // 自分の kind:3（フォロー）と kind:10002（NIP-65 リレーリスト）を取得する。
        // TODO: Settings で別 nsec に切替えたら myPubkey を更新して再購読する。
        scope.launch {
            // 未ログイン時は自分の識別子が無いので、identity 依存の購読はしない。
            // ログイン後に reloadForNewIdentity() から張り直す（#login: 勝手に鍵を作らない）。
            if (!SignerProvider.hasSession()) return@launch
            val me = SignerProvider.current().publicKeyHex()
            myPubkey = me; myPubkeyFlow.value = me
            subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
            subscribeOwnLists(me)   // [#396] 10002 / 10030 / 30015
            // 自分の固定投稿(kind:10001) / ブックマーク(kind:10003)（NIP-51）。
            subscribeAll("pinnedlist", Filter(kinds = listOf(10001), authors = listOf(me), limit = 1))
            subscribeAll("bookmarklist", Filter(kinds = listOf(10003), authors = listOf(me), limit = 1))
            // NIP-17 DM: 自分の DM リレーリスト(kind:10050)と、自分宛 gift wrap(kind:1059)を購読。
            // 10050 が届いたら updateDmRelayList が DM リレーへも追加購読する（broad は維持）。
            subscribeAll("dmrelays", Filter(kinds = listOf(10050), authors = listOf(me), limit = 1))
            subscribeAll("dm_inbox", Filter(kinds = listOf(1059), pTags = listOf(me)))
            // NIP-04 旧型DM(kind:4): 受信(自分宛)・送信(自分発)の両方を購読して統合表示。
            subscribeAll("dm4_in", Filter(kinds = listOf(4), pTags = listOf(me)))
            subscribeAll("dm4_out", Filter(kinds = listOf(4), authors = listOf(me)))
            // [M16] 自分のリアクション(kind:7)を購読し、宛先ノートと共に TL へ混ぜる。
            subscribeAll("myreactions", Filter(kinds = listOf(7), authors = listOf(me), limit = 100))
        }
    }

    /**
     * リレー URL を正規化する。末尾スラッシュの有無は同一リレーとして扱う
     * （例: wss://nos.lol と wss://nos.lol/ を統一 → 二重接続と N/M 水増しを防ぐ）。
     */
    private fun normalizeRelayUrl(url: String): String = url.trim().trimEnd('/')

    /**
     * 末尾スラッシュ違いで二重登録された既存 DB 行を統合する（起動時に一度だけ）。
     * read/write は OR で束ね、正規化 URL の 1 行にまとめる。
     */
    private fun dedupeRelayUrls() {
        val rows = q.allRelays().executeAsList()
        val hasDup = rows.any { normalizeRelayUrl(it.url) != it.url }
        if (!hasDup) return
        val merged = LinkedHashMap<String, app.nostrdeck.db.Relay>()
        for (r in rows) {
            val norm = normalizeRelayUrl(r.url)
            val prev = merged[norm]
            merged[norm] = if (prev == null) r.copy(url = norm)
            else prev.copy(
                read = if (prev.read != 0L || r.read != 0L) 1 else 0,
                write = if (prev.write != 0L || r.write != 0L) 1 else 0,
            )
        }
        q.transaction {
            rows.forEach { q.deleteRelay(it.url) }
            merged.values.forEach { q.upsertRelay(it.url, it.read, it.write, it.source) }
        }
    }

    /**
     * リレーへ接続（未接続なら）。接続済みの購読を張り直して取りこぼしを防ぐ。
     * relays/activeSubs の読み書きは relayDispatcher（単一スレッド相当）に直列化する。
     */
    private fun ensureRelay(url: String) {
        val key = normalizeRelayUrl(url)
        scope.launch(relayDispatcher) {
            if (relays.containsKey(key)) return@launch
            val client = RelayClient(key, scope)
            relays[key] = client
            client.start()
            scope.launch { client.messages.collect { onMessage(it, client) } }
            // 接続状態の変化を集約フローへ反映（レール/カラムのステータス表示用）。
            // [NIP-42/#16] 切断時は AUTH の応答済みチャレンジを破棄し、再接続で確実に再 AUTH する。
            //   （再接続時は RelayClient が activeReqs を自動で張り直すため購読は自己修復する）
            scope.launch {
                client.state.collect { st ->
                    if (st == RelayConnState.DISCONNECTED) authChallengeByRelay.remove(key)
                    withContext(relayDispatcher) { refreshRelayConns() }
                }
            }
            // 限定なし(subTargets 無)のサブ、または新リレーが対象集合に含まれるサブだけ張り直す。
            activeSubs.forEach { (subId, filters) ->
                val t = subTargets[subId]
                if (t == null || key in t) client.subscribe(subId, *filters.toTypedArray())
            }
        }
    }

    // ---- リレー接続ステータス（UI 表示用・モノクロ ●/◑/○）----
    private val _relayConns = MutableStateFlow<List<RelayConn>>(emptyList())
    /** 各リレーの接続状態（url 昇順）。レール集約インジケータ/カラムヘッダが購読する。 */
    fun relayConnFlow(): StateFlow<List<RelayConn>> = _relayConns.asStateFlow()

    // [#358] バックグラウンド滞在で全リレーを一時停止するジョブ。フォアグラウンド復帰でキャンセル。
    private var bgPauseJob: Job? = null

    /**
     * アプリがフォアグラウンド復帰したときに呼ぶ。バックオフ待機中のリレーを即再接続させる。
     * （バックグラウンドで OS がソケットを切ると最大30秒のバックオフに入るため、復帰時に短縮する）
     * [#358] バックグラウンドで一時停止([onBackground])したリレーもここで再開する。
     */
    fun onForeground() {
        bgPauseJob?.cancel()
        bgPauseJob = null
        scope.launch(relayDispatcher) {
            relays.values.forEach {
                it.start()   // pause 済みなら再開（購読中の REQ は接続時に自動で張り直される）。未 pause なら no-op
                it.wake()    // バックオフ待機中なら即リトライ
            }
        }
    }

    /**
     * [#358] アプリがバックグラウンドへ移ったときに呼ぶ。[delayMs] 経過後に全リレー接続を
     * 一時停止し、グローバルTL・検索などのストリームの垂れ流しを止める（モバイル通信量対策）。
     * 復帰([onForeground])で即座に再接続・購読復元される。5分は「アプリ切替や割り込みでは
     * 切らず、置きっぱなしだけを止める」ための猶予。
     */
    fun onBackground(delayMs: Long = BG_PAUSE_DELAY_MS) {
        bgPauseJob?.cancel()
        bgPauseJob = scope.launch {
            delay(delayMs)
            withContext(relayDispatcher) {
                relays.values.forEach { it.pause() }
                // [#364] モニタ表示用の記録（切断が実際に発火しているかの確証に使う）。
                bgPausedCount++
                lastBgPausedAtSec = currentUnixTime()
            }
        }
    }

    // [#364] バックグラウンド一時停止の実績（セッション内・モニタ表示用）。
    private var bgPausedCount = 0
    private var lastBgPausedAtSec = 0L

    /** [#364] 接続・通信量・購読のスナップショット（開発者モードのモニタが定期取得する）。 */
    suspend fun connMonitorSnapshot(): ConnMonitorSnapshot = withContext(relayDispatcher) {
        ConnMonitorSnapshot(
            tier = _networkTier.value,
            bgPausedCount = bgPausedCount,
            lastBgPausedAtSec = lastBgPausedAtSec,
            relays = relays.values.map { it.trafficSnapshot() },
            reqs = activeSubs.map { (id, filters) -> ActiveReqUi(id, subTargets[id]?.toList(), filters) }
                .sortedBy { it.subId },
        )
    }

    /**
     * [#14] 既存のリレー接続を破棄して即再接続し、購読中の REQ を張り直す（タイムライン再構築）。
     * Cmd+R 等から呼ぶ。各接続が切れて張り直るため、取りこぼしや詰まりをリセットできる。
     */
    fun reconnectAll() {
        scope.launch(relayDispatcher) { relays.values.forEach { it.forceReconnect() } }
    }

    /** relays の現在状態をスナップショットして集約フローへ流す（relayDispatcher 上で呼ぶ）。 */
    private fun refreshRelayConns() {
        // [#50] N/M と一覧は設定リスト(read 有効)のリレーだけを対象にする。
        // インデクサ/ヒント等の一時接続は「リストに無いリレー」なので除外（数えない・出さない）。
        _relayConns.value = relays.entries.filter { it.key in listRelays }.sortedBy { it.key }
            .map { RelayConn(it.key, it.value.state.value) }
    }

    /**
     * [#50] 一時接続（設定リストに無いインデクサ/ヒント接続）のうち、限定 REQ の配信先に
     * なっていない＝用が済んだものを閉じる。余剰接続の常駐（“リストに無いリレーが繋がったまま”）を防ぐ。
     * relayDispatcher 上で呼ぶこと。
     */
    private fun closeIdleTransientRelays() {
        val neededByTargeted = subTargets.values.flatten().toSet()
        val closable = relays.keys.filter { it !in listRelays && it !in neededByTargeted }
        if (closable.isEmpty()) return
        closable.forEach { url ->
            relays.remove(url)?.stop()
            hintRelays.remove(url)
        }
        refreshRelayConns()
    }

    /**
     * [#50] 一時 REQ（インデクサへのプロフィール/DMリレー問い合わせ等）を一定時間後に閉じる。
     * REQ を CLOSE し、どの限定 REQ にも使われなくなった一時接続を切る（アイドルで閉じる）。
     */
    private fun scheduleTransientCleanup(subId: String, delayMs: Long = 20_000L) {
        scope.launch {
            delay(delayMs)
            withContext(relayDispatcher) {
                activeSubs.remove(subId)
                subTargets.remove(subId)
                relays.values.forEach { it.unsubscribe(subId) }
                closeIdleTransientRelays()
            }
        }
    }

    /** 全リレーへ購読（subId 上書き）。新規リレー接続時の張り直し用に記録する。 */
    private fun subscribeAll(subId: String, vararg filters: Filter) {
        val list = filters.toList()
        scope.launch(relayDispatcher) {
            activeSubs[subId] = list
            subTargets.remove(subId)  // 全リレー対象（限定なし）
            relays.values.forEach { it.subscribe(subId, *list.toTypedArray()) }
        }
    }

    /** subId ごとの配信先リレー限定（グローバルの複数リレー選択用）。未登録=全リレー。 */
    private val subTargets = mutableMapOf<String, Set<String>>()

    /**
     * 指定リレーだけへ購読（未接続なら接続する）。[targets] が空なら全リレーへ。
     * REQ の配信先のみを絞る簡易版（DB 読み出しは全リレー混在のまま）。
     */
    private fun subscribeTargeted(subId: String, targets: Set<String>, vararg filters: Filter) {
        val norm = targets.map { normalizeRelayUrl(it) }.filter { it.isNotBlank() }.toSet()
        if (norm.isEmpty()) { subscribeAll(subId, *filters); return }
        val list = filters.toList()
        scope.launch(relayDispatcher) {
            activeSubs[subId] = list
            subTargets[subId] = norm
            norm.forEach { ensureRelay(it) }  // 選択リレーへ接続保証
            relays.filterKeys { it in norm }.values.forEach { it.subscribe(subId, *list.toTypedArray()) }
        }
    }

    private fun unsubscribeAll(subId: String) {
        scope.launch(relayDispatcher) {
            activeSubs.remove(subId)
            subTargets.remove(subId)
            relays.values.forEach { it.unsubscribe(subId) }
        }
    }

    // ---- リレー設定（NIP-65 / 手動）: Settings から編集する明示的な置き場 ----

    /** DB に保存されたリレー一覧（Inbox/Outbox + source）。Settings はこれを表示・編集する。 */
    fun relaysFlow(): Flow<List<app.nostrdeck.db.Relay>> =
        q.allRelays().asFlow().mapToList(Dispatchers.Default)

    /** リレーを手動追加（read/write 既定 true）。 */
    fun addRelay(url: String) {
        val u = normalizeRelayUrl(url)
        if (u.isBlank()) return
        q.upsertRelay(u, 1, 1, "manual")
        ensureRelay(u)
    }

    /** リレーを設定から外す（次回起動で接続対象から除外。現セッションの接続は維持）。 */
    fun removeRelay(url: String) {
        val u = normalizeRelayUrl(url)
        q.deleteRelay(u)
        // 購読接続中なら閉じる（write 専用は元から接続していないので無害）。
        scope.launch(relayDispatcher) {
            relays.remove(u)?.let { it.stop(); refreshRelayConns() }
        }
    }

    /**
     * Settings の Read/Write チェック切替。DB の read/write を更新し、接続を追従させる。
     *  - read=true へ  : 未接続なら購読接続する（Inbox として読む）。
     *  - read=false へ : 購読接続を閉じる（write 専用は配信時のみ一時接続）。
     * write は配信先の選別に使うだけで、ここでは接続を張らない（NIP-65 outbox）。
     */
    fun setRelayReadWrite(url: String, read: Boolean, write: Boolean) {
        val u = normalizeRelayUrl(url)
        q.setRelayReadWrite(if (read) 1 else 0, if (write) 1 else 0, u)
        scope.launch(relayDispatcher) {
            if (read) {
                if (!relays.containsKey(u)) ensureRelay(u)
            } else {
                relays.remove(u)?.let { it.stop(); refreshRelayConns() }
            }
        }
    }

    /**
     * 現在のリレー設定（DB）を kind:10002（NIP-65）として署名・配信する。
     * `r` タグは read+write=マーカー無し / read のみ="read" / write のみ="write"。
     * 配信先は [publishTargets]（write リレー ∪ 接続中リレー）。Settings の「保存して公開」から呼ぶ。
     * 返り値は配信できたか（署名鍵が無い等で失敗したら false）。
     */
    suspend fun publishRelayList(): Boolean {
        val rows = q.allRelays().executeAsList()
        val tags = rows.mapNotNull { r ->
            val read = r.read != 0L
            val write = r.write != 0L
            when {
                read && write -> listOf("r", r.url)
                read -> listOf("r", r.url, "read")
                write -> listOf("r", r.url, "write")
                else -> null  // read/write 両方オフのリレーは公開リストに出さない
            }
        }
        return runCatching {
            val signed = publishSigned(UnsignedEvent(kind = 10002, content = "", tags = tags))
            // 自分の最新版として記録し、購読エコーで古い扱いされないようにする。
            relayListRep.commit(tags, signed.createdAt)
            true
        }.getOrElse { false }
    }

    /**
     * ログイン中の鍵が変わったとき（nsec 取り込み / 新規生成）に呼ぶ。
     * 旧アカウントに紐づく履歴・キャッシュ・フォロー/リレーリスト(NIP-65)を破棄し、
     * 新しい公開鍵で kind:3 / kind:10002 と各カラムを取り直す（=「全部飛ばして読み直し」）。
     * default/manual のリレー設定は残す。
     */
    fun reloadForNewIdentity() {
        scope.launch {
            val me = SignerProvider.current().publicKeyHex()
            myPubkey = me; myPubkeyFlow.value = me

            // 旧アカウント依存の解決済み状態をリセット。
            follows.value = emptyList(); followsAt = 0L
            // [#396] 自分のリスト（10002/10030/30015）は State・at・KV ごと空に戻す（旧アカウントの版を見せない）。
            resetOwnLists()
            // [#374] 旧アカウントの 30078 スナップショットを新アカウントで見せないように。
            syncEventByD.value = emptyMap()

            // 履歴・キャッシュを全消去（NIP-65 リレーも。default/manual は維持）。
            q.transaction {
                q.clearEvents()
                q.clearTags()
                q.clearProfiles()
                q.clearChannels()
                q.clearPublishQueue()
                q.clearNip65Relays()
                q.clearCustomEmojis()  // カスタム絵文字リストはアカウント別なので消す。
            }

            // 新しい鍵でフォロー(kind:3)と自分のリスト(10002/10030/30015)を取り直す。
            subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
            subscribeOwnLists(me)   // [#396]
            subscribeAll("pinnedlist", Filter(kinds = listOf(10001), authors = listOf(me), limit = 1))
            subscribeAll("bookmarklist", Filter(kinds = listOf(10003), authors = listOf(me), limit = 1))
            subscribeAll("dmrelays", Filter(kinds = listOf(10050), authors = listOf(me), limit = 1))
            subscribeAll("dm_inbox", Filter(kinds = listOf(1059), pTags = listOf(me)))
            subscribeAll("dm4_in", Filter(kinds = listOf(4), pTags = listOf(me)))
            subscribeAll("dm4_out", Filter(kinds = listOf(4), authors = listOf(me)))
            subscribeAll("myreactions", Filter(kinds = listOf(7), authors = listOf(me), limit = 100))

            // 開いているカラムの REQ を張り直して取りこぼしを防ぐ（relayDispatcher で直列化）。
            withContext(relayDispatcher) {
                activeSubs.forEach { (subId, filters) ->
                    relays.values.forEach { it.subscribe(subId, *filters.toTypedArray()) }
                }
            }
        }
    }

    /**
     * [safety] ローカルキャッシュ（イベント/タグ/プロフィール/チャンネル/送信待ち）を強制消去して
     * 取り直す。鍵・リレー設定(default/manual/NIP-65)・使用ハッシュタグは保持する。
     * stale なプロフィール（後から追加した kind:0 の列が空のまま等）や、不要に溜まった
     * キャッシュを安全に掃除・リセットするための手動操作。
     */
    fun purgeCache() {
        scope.launch {
            // 解決済みフォロー集合(in-memory)は保持。イベント/プロフィール等のキャッシュのみ全消去。
            q.transaction {
                q.clearEvents()
                q.clearTags()
                q.clearProfiles()
                q.clearChannels()
                q.clearPublishQueue()
            }
            // 自分のフォロー(kind:3)・自分のリスト(10002/10030/30015)と開いているカラムを張り直して再構築。
            // [#396] 従来は emojilist だけ張り直していなかった（漏れ）。
            val me = myPubkey
            if (me != null) {
                subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
                subscribeOwnLists(me)
            }
            withContext(relayDispatcher) {
                activeSubs.forEach { (subId, filters) ->
                    relays.values.forEach { it.subscribe(subId, *filters.toTypedArray()) }
                }
            }
        }
    }

    // ---- ピン留めカラムの永続化（SSOT = pinned_column）----

    /**
     * 永続化済みのピン留めカラムを読み出す（起動時に DeckState の初期値へ）。
     * 壊れた行（未知の kind/renderer・不正 JSON）はスキップする。
     */
    fun loadPinnedColumns(): List<ColumnSpec> =
        q.pinnedColumns().executeAsList().mapNotNull { row ->
            runCatching {
                ColumnSpec(
                    id = row.id, title = row.title, subtitle = row.subtitle,
                    kind = ColumnKind.valueOf(row.kind),
                    renderer = ColumnRenderer.valueOf(row.renderer),
                    filter = json.decodeFromString(ReqFilter.serializer(), row.filter_json),
                    pinned = true, order = row.sort_order.toInt(),
                )
            }.getOrNull()
        }

    /**
     * 現在のピン留めカラム集合を丸ごと保存する（全消し→並び順で再INSERT）。
     * 追加/固定/解除/並べ替えのたびに呼ぶ。順序は引数のリスト順。
     * [#374] リレー(kind:30078)への保存は自動では行わない（設定画面の「リレーへ保存」のみ）。
     */
    fun persistPinnedColumns(specs: List<ColumnSpec>) {
        persistPinnedLocal(specs)
    }

    private fun persistPinnedLocal(specs: List<ColumnSpec>) {
        q.transaction {
            q.clearPinnedColumns()
            specs.forEachIndexed { i, s ->
                q.pinColumn(
                    s.id, s.title, s.subtitle, s.kind.name, s.renderer.name,
                    json.encodeToString(ReqFilter.serializer(), s.filter), i.toLong(),
                )
            }
        }
    }

    // ---- [#374] リレー同期（NIP-78 kind:30078 の手動保存/読み込み）----
    //
    // #122 の「常時購読 + 確認UIなしの単純LWW」は廃止し、明示操作だけで動くフローに置き換えた。
    //  - 保存: 設定画面の「リレーへ保存」で、設定スナップショット（d=SETTINGS_SYNC_D）と
    //    カラム構成（d=DECK_COLUMNS_D）の2イベントを発行する。自動発行はしない。
    //  - 読み込み: 「リレーから読み込む」で一時REQにより最新1件ずつ取得し、UI 側で
    //    ローカル現在値との差分を確認 → チェックした項目だけ適用する。常時購読はしない。

    /** 30078 の content に載せるカラム1件分。他クライアントからも読める素直な JSON。
     *  #122 時代に発行済みのイベントと互換のある形式なので変更しないこと。 */
    @Serializable
    private data class DeckColumnDto(
        val id: String,
        val title: String,
        val subtitle: String = "",
        val kind: String,
        val renderer: String,
        val filter: ReqFilter = ReqFilter(),
        val order: Int = 0,
    )

    // 適用したカラム構成。App が collect して DeckState へ反映する（UI 反映の既存経路）。
    private val remoteColumnsFlow = MutableSharedFlow<List<ColumnSpec>>(replay = 1)
    fun remoteDeckColumnsFlow(): SharedFlow<List<ColumnSpec>> = remoteColumnsFlow

    // 受信/発行した自分の 30078 スナップショット（d タグ → 最新イベント）。in-memory のみ。
    private val syncEventByD = MutableStateFlow<Map<String, NostrEvent>>(emptyMap())
    private var syncFetchSeq = 0

    /** リレーから取得した同期スナップショット。null のフィールド = リレーに未保存。 */
    data class RelaySyncSnapshot(
        val settings: Map<String, String>?,
        val columns: List<ColumnSpec>?,
    )

    /**
     * ingest から: 自分の 30078（対象 d タグ）を新しい版だけメモリへ控える。
     * ここでは適用しない（適用は差分確認 UI でユーザーが選んだ項目のみ）。
     */
    private fun captureSyncEvent(e: NostrEvent) {
        if (e.pubkey != myPubkey) return
        val d = e.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return
        if (d != DECK_COLUMNS_D && d != SETTINGS_SYNC_D) return
        val prev = syncEventByD.value[d]
        if (prev != null && prev.createdAt >= e.createdAt) return
        syncEventByD.value = syncEventByD.value + (d to e)
    }

    /**
     * 自分の 30078（設定 + カラム構成）を一時REQで取得する（手動ロード）。
     * 両方揃うか [timeoutMs] で打ち切り。未ログインなら null。
     */
    suspend fun fetchRelaySync(timeoutMs: Long = 6_000): RelaySyncSnapshot? {
        val me = myPubkey ?: return null
        val subId = "syncfetch-${syncFetchSeq++}"
        subscribeAll(
            subId,
            Filter(kinds = listOf(30078), authors = listOf(me), dTags = listOf(SETTINGS_SYNC_D), limit = 1),
            Filter(kinds = listOf(30078), authors = listOf(me), dTags = listOf(DECK_COLUMNS_D), limit = 1),
        )
        withTimeoutOrNull(timeoutMs) {
            while (syncEventByD.value[SETTINGS_SYNC_D] == null || syncEventByD.value[DECK_COLUMNS_D] == null) {
                delay(200)
            }
        }
        unsubscribeAll(subId)
        val settings = syncEventByD.value[SETTINGS_SYNC_D]?.let { e ->
            runCatching { json.decodeFromString(SettingsSyncPayload.serializer(), e.content).settings }.getOrNull()
        }
        val columns = syncEventByD.value[DECK_COLUMNS_D]?.let { decodeDeckColumns(it.content) }
        return RelaySyncSnapshot(settings, columns)
    }

    /** ホワイトリスト設定の現在値スナップショット（正規化済み文字列）。差分計算・発行に使う。 */
    fun currentSyncSettings(): Map<String, String> =
        SETTINGS_SYNC_WHITELIST.associate { it.key to it.read(this) }

    /** ホワイトリスト設定のスナップショットを kind:30078（d=SETTINGS_SYNC_D）として発行する。 */
    suspend fun publishSettingsSync(): Boolean = runCatching {
        val content = json.encodeToString(
            SettingsSyncPayload.serializer(),
            SettingsSyncPayload(version = 1, settings = currentSyncSettings()),
        )
        val signed = publishSigned(
            UnsignedEvent(kind = 30078, content = content, tags = listOf(listOf("d", SETTINGS_SYNC_D))),
        )
        captureSyncEvent(signed)   // 直後のロードで「差分なし」になるように控えも更新
        true
    }.getOrElse { println("Nostrism publishSettingsSync failed: $it"); false }

    /** 現在のピン留めカラム構成を kind:30078（d=DECK_COLUMNS_D）として発行する。 */
    suspend fun publishDeckColumnsSync(): Boolean = runCatching {
        val dtos = loadPinnedColumns().sortedBy { it.order }.mapIndexed { i, s ->
            DeckColumnDto(s.id, s.title, s.subtitle, s.kind.name, s.renderer.name, s.filter, i)
        }
        val content = json.encodeToString(ListSerializer(DeckColumnDto.serializer()), dtos)
        val signed = publishSigned(
            UnsignedEvent(kind = 30078, content = content, tags = listOf(listOf("d", DECK_COLUMNS_D))),
        )
        captureSyncEvent(signed)
        true
    }.getOrElse { println("Nostrism publishDeckColumnsSync failed: $it"); false }

    /** d=DECK_COLUMNS_D の content（#122 互換の DeckColumnDto 配列）を ColumnSpec 群へ。壊れていれば null。 */
    // [#388-review] JSON 全体の decode 失敗だけを null にし、行の変換は loadPinnedColumns と同じく
    // 1件ずつ runCatching で包む。旧ビルドが未知の ColumnKind（LIST 等）を1件読んだだけで
    // 全カラムが null（＝同期の全件失敗）になっていた。
    private fun decodeDeckColumns(content: String): List<ColumnSpec>? =
        runCatching { json.decodeFromString(ListSerializer(DeckColumnDto.serializer()), content) }
            .getOrNull()
            ?.mapNotNull { d ->
                runCatching {
                    ColumnSpec(
                        id = d.id, title = d.title, subtitle = d.subtitle,
                        kind = ColumnKind.valueOf(d.kind), renderer = ColumnRenderer.valueOf(d.renderer),
                        filter = d.filter, pinned = true, order = d.order,
                    )
                }.getOrNull()
            }

    /**
     * 差分適用後のカラム構成を反映する（ローカル保存 + [remoteColumnsFlow] 経由で DeckState へ）。
     * 差分確認 UI で「適用」されたときだけ呼ばれる。
     */
    fun applySyncedColumns(specs: List<ColumnSpec>) {
        persistPinnedLocal(specs)
        scope.launch { remoteColumnsFlow.emit(specs) }
    }

    // ---- [#396] 自分のリストの共通操作（起動 / アカウント切替 / キャッシュ消去はこれを呼ぶだけ）----

    /** 自分の replaceable リスト（10002 / 10030 / 30015）を購読する。 */
    private fun subscribeOwnLists(me: String) {
        ownLists.forEach { subscribeAll(it.subId, it.filter(me)) }
    }

    /** KV バックアップを復元する（絵文字リスト / ピン留め。10002 は relay 表が置き場なので対象外）。 */
    private fun restoreOwnLists() {
        emojiListRep.restore()
        if (pinnedRep.restore()) {
            // [#393] 未発行のまま終了していた場合に備え、初回受信でローカルが新しければ1回だけ再発行する。
            pinnedRepublishArmed = pinnedRep.at > 0L
        }
    }

    /** アカウント切替: State・at・KV を空に戻し、ピン留めの未発行編集も捨てる。 */
    private fun resetOwnLists() {
        pinnedPublishJob?.cancel()
        pinnedRollback = null
        pinnedRepublishArmed = false
        ownLists.forEach { it.reset() }
    }

    // ---- カラム = REQ ライフサイクル ----
    private val openColumns = mutableSetOf<String>()

    /**
     * [#388-review] 購読中カラム → 著者（少数著者のフィルタのみ）。プロフィール画面/カラムが
     * いま見ている著者の集合で、メモリ保持マップ（NIP-65）の上限退避から守る。
     */
    private val columnAuthors = mutableMapOf<String, List<String>>()

    /** 現在プロフィール系で購読中の著者集合（退避の保護対象）。 */
    private fun protectedAuthors(): Set<String> = columnAuthors.values.flatten().toSet()

    /**
     * [#388-review] 著者キーのメモリマップを上限まで縮める。投入順に古いものから捨てるが、
     * 購読中（表示中）の著者は飛ばす。全員が保護対象なら上限を超えたままにする（消える方が害）。
     */
    private fun <V> evictAuthors(map: MutableMap<String, V>, cap: Int, onEvict: (String) -> Unit = {}) {
        if (map.size <= cap) return
        val keep = protectedAuthors()
        val victims = map.keys.filter { it !in keep }.take(map.size - cap)
        victims.forEach { map.remove(it); onEvict(it) }
    }

    /** カラム表示時に購読開始（subId = columnId）。filter.relays 指定時はそのリレーだけへ配信。 */
    fun subscribeColumn(columnId: String, filter: ReqFilter) {
        if (!openColumns.add(columnId)) return
        // [#388-review] 少数著者（プロフィール系）の購読中は、その著者のメモリ保持情報
        // （NIP-65）を上限退避の対象から外す。表示中に消えないようにするため。
        if (filter.authors.isNotEmpty() && filter.authors.size <= 3) columnAuthors[columnId] = filter.authors
        columnLoadedState.value = columnLoadedState.value - columnId  // [#17] 再購読でロード中に戻す
        // [#17] EOSE が来ない/遅いリレーでも無限ロードにしない安全網（8秒でロード済み扱い）。
        scope.launch {
            delay(8000)
            if (columnId in openColumns) columnLoadedState.value = columnLoadedState.value + columnId
        }
        // [#135] 複合検索: 条件ごとの複数フィルタ（同一 REQ 内の複数フィルタ = OR）。
        // 単語条件を含むため NIP-50 対応リレーへ投げる（タグ/著者フィルタも同リレーで解決できる）。
        if (filter.words.isNotEmpty()) {
            subscribeTargeted(columnId, SEARCH_RELAYS.toSet(), *filter.toSearchProtocols(limit = SEARCH_FETCH_LIMIT))
            return
        }
        val proto = filter.toProtocol(limit = 100)
        when {
            // [#8] 検索カラムは NIP-50 対応リレーへ（接続中リレーが未対応でも結果を取れるように）。
            // [#210] 検索は取りこぼしが多いので取得上限を増やす（表示は feedBySearch 側で LIMIT）。
            !filter.search.isNullOrBlank() -> subscribeTargeted(columnId, SEARCH_RELAYS.toSet(), filter.toProtocol(limit = SEARCH_FETCH_LIMIT))
            filter.relays.isNotEmpty() -> subscribeTargeted(columnId, filter.relays.toSet(), proto)
            // [#209] プロフィール/指定npub（少数著者）は著者の書き込みリレー(NIP-65)からも取得（アウトボックス）。
            filter.authors.isNotEmpty() && filter.authors.size <= 3 -> subscribeAuthorOutbox(columnId, filter, proto)
            else -> subscribeAll(columnId, proto)
        }
    }

    /**
     * [#209] 少数著者フィード（プロフィール等）のアウトボックス購読。
     * まず自分のリレーで即購読し、並行して著者の kind:10002(NIP-65) を取得→その write リレーからも
     * 同じ投稿を購読する。著者が実際に使うリレーの投稿を拾えるので、自分の購読リレーに無い/古い分の
     * 取りこぼし（中間抜け）が減る。追加購読はカラム ID に紐づけ、カラム閉時にまとめて CLOSE する。
     */
    private fun subscribeAuthorOutbox(columnId: String, filter: ReqFilter, proto: Filter) {
        subscribeAll(columnId, proto)   // 自分のリレーで即購読
        // [#388-review] 10002 待ちのジョブをカラム id で記録し、unsubscribeColumn で取り消す。
        // これが無いと、閉じた後に最大10秒遅れて「~outbox」購読が張られて漏れる。
        outboxJobs.remove(columnId)?.cancel()
        outboxJobs[columnId] = scope.launch {
            // 著者の NIP-65 を indexer + 自分のリレーから取得。
            // [#254-profile] 旧実装の2つの穴を塞ぐ:
            //  1. 同じ subId で subscribeTargeted → subscribeAll を呼んでいたため、subscribeAll が
            //     subTargets を消してターゲット指定が壊れていた → subId を分離
            //  2. 固定 delay(4000) はインデクサへの新規接続(TLS+WS)が間に合わないと 10002 を
            //     取り逃し、write リレー不明のまま終了 → 到着をポーリングで待つ（最大10秒）
            val needs = filter.authors.filter { authorWriteRelays(it).isEmpty() }
            if (needs.isNotEmpty()) {
                val f = Filter(kinds = listOf(10002), authors = needs, limit = needs.size)
                subscribeTargeted("$columnId~relaylist", INDEXER_RELAYS.toSet(), f)
                subscribeAll("$columnId~relaylist2", f)
                try {
                    withTimeoutOrNull(10_000) {
                        while (needs.any { authorWriteRelays(it).isEmpty() }) delay(500)
                    }
                } finally {
                    // キャンセル（カラム閉）でも一時 REQ を残さない。
                    unsubscribeAll("$columnId~relaylist")
                    unsubscribeAll("$columnId~relaylist2")
                }
            }
            // DB から write リレーを取り出し、著者の投稿を outbox リレーからも購読（未接続のものだけ）。
            val writeRelays = filter.authors.flatMap { authorWriteRelays(it) }.distinct()
                .map { normalizeRelayUrl(it) }.filter { it.isNotBlank() && it !in relays.keys }
            if (writeRelays.isNotEmpty() && columnId in openColumns) {
                subscribeTargeted("$columnId~outbox", writeRelays.toSet(), proto)
            }
        }
    }

    /**
     * [#209] 著者の kind:10002 から write リレー URL を取り出す。
     * [#254-profile] 旧実装は event テーブル（eventsByKindAuthor）を読んでいたが、ingest の
     * kind:10002 は captureNip65（メモリ）にのみ保存し event へは書かないため**常に空**で、
     * アウトボックス購読は導入時から一度も機能していなかった。captureNip65 と同じ置き場を読む。
     */
    private fun authorWriteRelays(pubkey: String): List<String> =
        nip65WriteByAuthor[pubkey].orEmpty()

    /**
     * [#135] キーワード・タグフィードの REQ フィルタ群。
     * 単語は NIP-50（1語=1フィルタ）・タグは #t（複数値=OR）で、同一 REQ 内の
     * 複数フィルタ = リレー側 OR として届く（単語とタグで REQ の仕組みが違うため分ける）。
     */
    private fun ReqFilter.toSearchProtocols(limit: Int): Array<Filter> = buildList {
        words.forEach { add(Filter(kinds = listOf(1), search = it, limit = limit)) }
        if (hashtags.isNotEmpty()) add(Filter(kinds = listOf(1), hashtags = hashtags, limit = limit))
    }.toTypedArray()

    // [#3] 過去方向の追い読み用の一発 REQ 連番。
    private var olderReqSeq = 0

    /**
     * [#3] 無限スクロール: [untilSec] より古いイベントを1回だけ取得する（過去へ継ぎ足し）。
     * カラムと同じ配信先へ until 付き REQ を投げ、少し待って CLOSE（sub を溜めない）。
     * 取り込まれた古いイベントは feedBy* クエリ(降順・上限)に載って表示される。
     */
    fun loadOlderColumn(columnId: String, filter: ReqFilter, untilSec: Long) {
        val proto = filter.toProtocol(limit = 100).copy(until = untilSec)
        val subId = "older-$columnId-${olderReqSeq++}"
        when {
            // [#135] 複合検索: 条件フィルタ群に until を付けて NIP-50 リレーへ。
            filter.words.isNotEmpty() -> subscribeTargeted(
                subId, SEARCH_RELAYS.toSet(),
                *filter.toSearchProtocols(limit = SEARCH_FETCH_LIMIT).map { it.copy(until = untilSec) }.toTypedArray(),
            )
            !filter.search.isNullOrBlank() -> subscribeTargeted(subId, SEARCH_RELAYS.toSet(), filter.toProtocol(limit = SEARCH_FETCH_LIMIT).copy(until = untilSec))
            filter.relays.isNotEmpty() -> subscribeTargeted(subId, filter.relays.toSet(), proto)
            else -> subscribeAll(subId, proto)
        }
        scope.launch { delay(6000); unsubscribeAll(subId); openColumns.remove(subId) }
    }

    /** [#388-review] カラム id → アウトボックス解決ジョブ（subscribeAuthorOutbox）。閉じたら cancel。 */
    private val outboxJobs = mutableMapOf<String, Job>()

    /** カラム除去/オフスクリーン時に CLOSE。 */
    fun unsubscribeColumn(columnId: String) {
        followingJobs.remove(columnId)?.cancel()
        notifJobs.remove(columnId)?.cancel()
        outboxJobs.remove(columnId)?.cancel()
        columnAuthors.remove(columnId)
        columnLoadedState.value = columnLoadedState.value - columnId  // [#17]
        if (openColumns.remove(columnId)) unsubscribeAll(columnId)
        // [#209] アウトボックスの追加購読も CLOSE。
        unsubscribeAll("$columnId~outbox")
        unsubscribeAll("$columnId~relaylist")
        unsubscribeAll("$columnId~relaylist2")
    }

    // ---- FOLLOWING（フォロー中）: 自分の kind:3 を authors にした購読/読み出し ----
    private val followingJobs = mutableMapOf<String, Job>()

    /**
     * フォロー中カラムの購読。フォロー集合が解決/更新されるたびに REQ を貼り直す
     * （authors = 自分のフォロー）。GLOBAL と違い「タイトル＝フォロー中」と中身が一致する。
     */
    fun subscribeFollowing(columnId: String) {
        if (!openColumns.add(columnId)) return
        // [#17][#254] 他カラム(subscribeColumn)と同じ安全網。EOSE が来ない/遅いリレーだけの状況でも
        // 「読み込み中」を出し続けない（8秒でロード済み扱い。キャッシュ表示はその間も生きている）。
        columnLoadedState.value = columnLoadedState.value - columnId
        scope.launch {
            delay(8000)
            if (columnId in openColumns) columnLoadedState.value = columnLoadedState.value + columnId
        }
        followingJobs[columnId] = scope.launch {
            follows.collect { authors ->
                // [M10] 自分の投稿もフォロー中タイムラインに出す（authors に自分を含める）。
                val withMe = (authors + listOfNotNull(myPubkey)).distinct()
                if (withMe.isNotEmpty()) {
                    // kind:1 本文 + kind:6/16 リポスト[M8-repost]（リアクション数は出さないので kind:7 は購読しない）。
                    // [#319] kind:5 削除リクエストも取る。これが無いと、別端末で消した自分の投稿が
                    // こちらに残り続ける（フォロー先が消したものも同じ）。件数は少なく負荷にならない。
                    // [#380] kind:1111 NIP-22 コメントも流す（ルート文脈の1行プレビュー付きで表示）。
                    subscribeAll(columnId, Filter(kinds = listOf(1, 6, 16, 5, 1111), authors = withMe, limit = 100))
                }
            }
        }
    }

    /**
     * [#53] カラムのプルリフレッシュ: 今の REQ を破棄して張り直す（取りこぼし解消・最新化）。
     * ソケットは張り直さず、同一接続上で unsubscribe→subscribe する（用途は最新化なので十分）。
     * 再購読で columnLoadedState もロード中に戻り、DB Flow 経由でタイムラインが再構成される。
     */
    fun refreshColumn(columnId: String, filter: ReqFilter) {
        unsubscribeColumn(columnId)
        subscribeColumn(columnId, filter)
    }

    /** [#53] フォロー中カラムのプルリフレッシュ。本体＋混ぜ込む自分宛通知の REQ を張り直す。 */
    fun refreshFollowing(columnId: String) {
        unsubscribeColumn(columnId)
        unsubscribeColumn("home_notif")
        subscribeFollowing(columnId)
        subscribeNotifications("home_notif")
    }

    /** フォロー中フィード（フォロー集合の更新に追従。自分の投稿も含む）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun followingFeed(): Flow<List<NoteUi>> =
        follows.flatMapLatest { follows ->
            // [M10] 自分の投稿も表示するため authors に自分を含める。
            val authors = (follows + listOfNotNull(myPubkey)).distinct()
            if (authors.isEmpty()) flowOf(emptyList())
            else combine(
                // [M8-repost] kind:1 + kind:6/16 リポストを含めて取得し、表示用に展開する。
                q.feedFollowingWithReposts(authors, 0L).asFlow().mapToList(Dispatchers.Default),
                profilesFlow,
                noteMetaFlow,  // [M10] 自分の♡/リポスト済み状態（ボタンのハイライト用）
            ) { rows, profiles, meta -> Triple(rows, profiles, meta) }
                // conflate: 変換は重いので rapid な profiles/rows 更新は最新だけ処理して間引く。
                .conflate()
                .map { (rows, profiles, meta) ->
                    val byPubkey = profiles.associateBy { it.pubkey }
                    // [M8-repost] リポストは元ノートに展開。メタ（反応/数/自分の状態）を表示ノートに付与。
                    // [#61] 重複排除は「完全な同一エントリ」だけを畳む。元投稿は event.id、リポストの
                    // コピーは repostId で一意化 → 元 vs リポストは別々に残り、複数人のリポストも各々残る。
                    rows.mapNotNull { row -> toFollowingNoteUi(row, byPubkey)?.let { applyMeta(it, meta) } }
                        .distinctBy { it.repostId ?: it.event.id }
                // 変換（eventById 解決・集約付与）は重いので Default に載せ、UI スレッドを塞がない（ANR 対策）。
                }.flowOn(Dispatchers.Default)
        }

    /**
     * 画面遷移（タブ切替・詳細表示）で都度フィードが空に戻る問題を避けるため、
     * フィードを共有ホット StateFlow にして「直近の値」を保持する。
     * WhileSubscribed(5s): 離脱しても 5 秒は上流を生かし、最後の値を再購読へ即返す。
     */
    private val feedSharing = SharingStarted.WhileSubscribed(5_000)

    /**
     * [M10] ホームタイムラインの混在フィード。フォロー中の投稿に、自分宛の
     * リアクション/リポスト通知をコンパクトに混ぜて新しい順に返す（nostter 風）。
     * 遷移で空に戻らないよう StateFlow にキャッシュ（[feedSharing]）。
     */
    private val followingMixedCache: StateFlow<List<FeedEntry>> by lazy {
        buildFollowingFeedMixed().stateIn(scope, feedSharing, emptyList())
    }
    fun followingFeedMixed(): StateFlow<List<FeedEntry>> = followingMixedCache

    private fun buildFollowingFeedMixed(): Flow<List<FeedEntry>> =
        combine(followingFeed(), notificationsFeed(), myReactionsFeed(), follows) { notes, notifs, myReactions, follows ->
            val followSet = follows.toSet()
            // 件数表示は不要。混ぜ込むのは「自分へのリアクション/リポスト」だけ
            //（リプライ/メンションは本文ノートとして既に流れるため重複させない）。
            val notices = notifs.filter { n ->
                when (n.kind) {
                    NotificationKind.REACTION -> true
                    // リポスト/返信はフォロー中の人のものだと本文側で展開表示され重複するので、フォロー外のみ。
                    NotificationKind.REPOST -> n.actor.pubkey !in followSet
                    NotificationKind.REPLY, NotificationKind.MENTION -> n.actor.pubkey !in followSet
                    else -> false
                }
            }
            // [#337] 以前は対象がフォロー中の本文として TL に流れている場合を除外していたが
            // （#83 の二重表示回避）、「フォロー中TLでリアクションしても何も出ない」ため
            // 自分の操作が確認できなかった。他者からのリアクション通知も同じく元投稿と
            // 併存するので、そちらに合わせて**常に出す**。
            val myRx = myReactions
            (notes.map { FeedEntry.Post(it) } + notices.map { FeedEntry.Notice(it) } + myRx)
                .sortedByDescending { it.sortAt }
        }.flowOn(Dispatchers.Default)

    /** [#12] ふぁぼ欄カラム用: 自分のリアクション＋宛先ノートのフィード（cache-first）。 */
    private val favsFeedCache: StateFlow<List<FeedEntry>> by lazy {
        myReactionsFeed().stateIn(scope, feedSharing, emptyList())
    }
    fun favsFeed(): StateFlow<List<FeedEntry>> = favsFeedCache

    /** [M16] 自分が付けた kind:7 リアクションと、その宛先ノートを TL エントリにする。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun myReactionsFeed(): Flow<List<FeedEntry>> = myPubkeyFlow.flatMapLatest { me ->
        if (me == null) flowOf(emptyList())
        else combine(
            q.myReactionsAll(me).asFlow().mapToList(Dispatchers.Default), profilesFlow, noteMetaFlow,
        ) { rows, profiles, meta ->
            val byPk = profiles.associateBy { it.pubkey }
            rows.mapNotNull { r ->
                val targetId = r.target_id ?: return@mapNotNull null
                val targetRow = q.eventById(targetId).executeAsOneOrNull()
                if (targetRow == null) { requestEvent(targetId); return@mapNotNull null }  // 未取得なら取りに行く
                val target = applyMeta(withQuoteAndReply(toNoteUi(targetRow, byPk[targetRow.pubkey]), targetRow, byPk), meta)
                FeedEntry.MyReaction(normalizeReaction(r.content, parseTags(r.tags_json)), target, r.created_at)
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * [#80] 上限付きの簡易 LRU（アクセス順）。フィードの StateFlow キャッシュは「直近の値」
     * ごと保持し続けるため、無制限だとプロフィール閲覧等でフィルタの数だけ NoteUi リストが
     * 溜まり続けて OOM に至る。上限超過時は最も古くアクセスされたものを捨てる
     * （捨てても再訪時に DB から再構築されるだけ。WhileSubscribed なので購読中の上流は
     * 参照を握る UI 側が生かしており、退避は表示に影響しない）。
     */
    private class LruCache<K, V>(private val cap: Int) {
        private val map = LinkedHashMap<K, V>()  // mutableMapOf と同じ挿入順。触れたら入れ直して末尾へ
        fun getOrPut(key: K, create: () -> V): V {
            map.remove(key)?.let { map[key] = it; return it }
            val v = create()
            map[key] = v
            if (map.size > cap) map.remove(map.keys.first())
            return v
        }
    }

    /** カラムのフィルタに対応する DB フィードを NoteUi で返す（cache-first）。
     *  遷移で空に戻らないよう filter ごとに StateFlow をキャッシュ（[feedSharing]、上限つき #80）。 */
    private val columnFeedCache = LruCache<ReqFilter, StateFlow<List<NoteUi>>>(32)
    fun columnFeed(filter: ReqFilter): StateFlow<List<NoteUi>> =
        columnFeedCache.getOrPut(filter) {
            buildColumnFeed(filter).stateIn(scope, feedSharing, emptyList())
        }

    private fun buildColumnFeed(filter: ReqFilter): Flow<List<NoteUi>> =
        // combine は入力が変わる度に発火する。profilesFlow は kind:0 受信の度に流れるため、
        // 重い変換（200件×引用/返信の DB 解決）を毎回やると Default/SQLite が飽和して
        // ライブ更新が遅延する。conflate() で「最新だけ処理」して無駄な再構築を間引く（遅延は増えない）。
        combine(rowsFlow(filter), profilesFlow, noteMetaFlow) { rows, profiles, meta -> Triple(rows, profiles, meta) }
            .conflate()
            .map { (rows, profiles, meta) ->
                val byPubkey = profiles.associateBy { it.pubkey }
                // [#134] kind:6/16 の行（プロフィールの投稿+リポスト）も扱えるよう
                // フォロー中と同じ変換に統一する（kind:1 は従来と同じ経路に落ちる）。
                rows.mapNotNull { row ->
                    toFollowingNoteUi(row, byPubkey)?.let { applyMeta(it, meta) }
                }
            }.flowOn(Dispatchers.Default)

    // ---- NIP-28 パブリックチャット（kind:40/41 一覧 + kind:42 メッセージ） ----

    /** チャンネルごとのリレー（thread.nchan.vip の content.relays 由来）。購読/配信のヒント。 */
    private val channelRelays = mutableMapOf<String, List<String>>()

    /** チャンネル一覧（最終活動が新しい順）。DB キャッシュを流す（HTTP 取得は [refreshChannels]）。 */
    fun channelsFlow(): Flow<List<app.nostrdeck.model.Channel>> =
        q.channelsByActivity().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { app.nostrdeck.model.Channel(it.id, it.name, it.about, it.picture_url) }
        }

    /**
     * チャンネル一覧を HTTP エンドポイント（運用中の thread.nchan.vip）から取得して DB へ upsert。
     * content(JSON) から name/about/picture/relays を展開し、relays は購読/配信ヒントに控える。
     */
    suspend fun refreshChannels() = withContext(Dispatchers.Default) {
        runCatching {
            val body = uploadHttp.get(CHANNELS_ENDPOINT).bodyAsText()
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return@runCatching
            val parsed = data.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val meta = o["content"]?.jsonPrimitive?.contentOrNull
                    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                val name = meta?.get("name")?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                    ?: o["name"]?.jsonPrimitive?.contentOrNull ?: ""
                val about = meta?.get("about")?.jsonPrimitive?.contentOrNull ?: ""
                val picture = meta?.get("picture")?.jsonPrimitive?.contentOrNull?.ifBlank { null }
                val createdAt = o["created_at"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val lastAt = o["latest_update"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: createdAt
                val relays = meta?.get("relays")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                if (relays.isNotEmpty()) channelRelays[id] = relays
                ChannelRow(id, name, about, picture, createdAt, lastAt)
            }
            q.transaction {
                parsed.forEach { q.upsertChannel(it.id, it.name, it.about, it.picture, it.createdAt, it.lastAt) }
            }
        }
        Unit
    }

    private data class ChannelRow(
        val id: String, val name: String, val about: String,
        val picture: String?, val createdAt: Long, val lastAt: Long,
    )

    /** チャンネルルーム表示時に購読開始（kind:42 #e=channelId）。チャンネルのリレーへも接続する。 */
    fun subscribeChannel(columnId: String, channelId: String) {
        if (!openColumns.add(columnId)) return
        connectChannelRelays(channelId)
        subscribeAll(columnId, Filter(kinds = listOf(42), eTags = listOf(channelId), limit = 200))
    }

    /** チャンネルの content.relays へ一時接続して REQ/EVENT が届くようにする（上限つき）。 */
    private fun connectChannelRelays(channelId: String) {
        val urls = channelRelays[channelId] ?: return
        scope.launch(relayDispatcher) {
            for (raw in urls) {
                val url = normalizeRelayUrl(raw)
                if (!url.startsWith("wss://") && !url.startsWith("ws://")) continue
                if (relays.containsKey(url)) continue
                if (hintRelays.size >= HINT_RELAY_CAP) break
                if (hintRelays.add(url)) ensureRelay(url)
            }
        }
    }

    /**
     * このチャンネルの kind:42 メッセージへの kind:7 リアクションを対象別に集約（絵文字→件数）。
     * 対象を当該チャンネルのメッセージに限定（SQL 側）＝全 kind:7 の走査を避ける。
     */
    private fun channelReactionsFlow(channelId: String): Flow<Map<String, List<ReactionUi>>> =
        q.reactionsForChannel(channelId).asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.groupBy { it.note_id }.mapValues { (_, rs) ->
                rs.groupBy {
                    val r = normalizeReaction(it.content, parseTags(it.tags_json))
                    r.display to r.imageUrl
                }.map { (k, list) -> ReactionUi(k.first, k.first, list.size, k.second) }
                    .sortedByDescending { it.count }
            }
        }

    /** チャンネルの kind:42 メッセージを ChannelMessage（時系列昇順・連投まとめ・集約リアクション付き）で流す。 */
    private val channelFeedCache = LruCache<String, StateFlow<List<ChannelMessage>>>(12)  // 上限つき(#80)
    fun channelMessagesFeed(channelId: String): StateFlow<List<ChannelMessage>> =
        channelFeedCache.getOrPut(channelId) {
            combine(
                // クエリは新しい順（LIMIT を最新側から効かせる #110）。表示は昇順なので反転する。
                q.messagesByChannel(channelId, 300L).asFlow().mapToList(Dispatchers.Default)
                    .map { it.asReversed() },
                profilesFlow, myPubkeyFlow, channelReactionsFlow(channelId),
            ) { rows, profiles, me, reactions ->
                val byPk = profiles.associateBy { it.pubkey }
                rows.mapIndexed { i, row ->
                    val prev = rows.getOrNull(i - 1)
                    val prof = byPk[row.pubkey]
                    ChannelMessage(
                        // tags を保持（リプライ元 #e の解決に使う）。
                        event = NostrEvent(row.id, row.pubkey, row.kind.toInt(), row.created_at, row.content, parseTags(row.tags_json), row.sig),
                        author = Profile(
                            row.pubkey, prof?.name?.takeIf { it.isNotBlank() } ?: row.pubkey.take(10),
                            prof?.handle ?: "", prof?.picture_url, lud16 = prof?.lud16,
                        ),
                        isMine = row.pubkey == me,
                        continuation = prev != null && prev.pubkey == row.pubkey && row.created_at - prev.created_at < 300,
                        reactions = reactions[row.id].orEmpty(),
                    )
                }
            }.flowOn(Dispatchers.Default).stateIn(scope, feedSharing, emptyList())
        }

    /** 表示中メッセージ群への kind:7 リアクションを購読（Slack 風集約表示のため）。id 群が変わるたび貼り直す。 */
    fun subscribeChannelReactions(subId: String, messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        openColumns.add(subId)
        subscribeAll(subId, Filter(kinds = listOf(7), eTags = messageIds.take(300), limit = 500))
    }

    /**
     * 指定チャンネルへ kind:42 メッセージを投稿（NIP-28）。ルート #e にチャンネルid を付ける。
     * [replyTo] があれば NIP-10 の返信（reply マーカー付き #e ＋ 相手 #p）も添える。
     * [#109] 通常投稿と同様、本文中の :shortcode: は NIP-30 emoji タグに、
     * nostr:npub… メンションは p タグにして送る。
     */
    /**
     * [#291] NIP-28 チャンネル作成（kind:40）。content は {name, about, picture} の JSON。
     * 一覧は thread.nchan.vip 由来のため、外部が拾うまで待たずに済むようローカルへ即 upsert し、
     * 返した id でそのままルームを開ける。
     */
    suspend fun createChannel(name: String, about: String, picture: String?): String? = runCatching {
        val content = buildJsonObject {
            put("name", name)
            put("about", about)
            if (!picture.isNullOrBlank()) put("picture", picture)
        }.toString()
        val signed = publishSigned(UnsignedEvent(kind = 40, content = content, tags = emptyList()))
        q.upsertChannel(signed.id, name, about, picture?.takeIf { it.isNotBlank() }, signed.createdAt, signed.createdAt)
        signed.id
    }.getOrNull()

    /**
     * [#291] チャンネル情報の編集（kind:41、e タグで kind:40 を指す）。
     * NIP-28 では作成者の最新 kind:41 を正とするのが通例のため、UI は自分が作成した
     * チャンネル（[myChannelIdsFlow]）にだけ編集導線を出す。
     */
    suspend fun updateChannel(channelId: String, name: String, about: String, picture: String?): Boolean = runCatching {
        val content = buildJsonObject {
            put("name", name)
            put("about", about)
            if (!picture.isNullOrBlank()) put("picture", picture)
        }.toString()
        publishSigned(UnsignedEvent(kind = 41, content = content, tags = listOf(listOf("e", channelId))))
        // last_message_at（一覧の並び順）は保持したまま名前等だけ更新する。
        val prev = q.channelById(channelId).executeAsOneOrNull()
        q.upsertChannel(
            channelId, name, about, picture?.takeIf { it.isNotBlank() },
            currentUnixTime(), prev?.last_message_at ?: 0L,
        )
        true
    }.getOrDefault(false)

    /** [#291] 自分が作成したチャンネル id（編集導線の出し分け用）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun myChannelIdsFlow(): Flow<Set<String>> =
        myPubkeyFlow.flatMapLatest { me ->
            if (me == null) flowOf(emptySet())
            else q.myChannelCreateIds(me).asFlow().mapToList(Dispatchers.Default).map { it.toSet() }
        }

    suspend fun publishChannelMessage(channelId: String, text: String, replyTo: NostrEvent? = null) {
        if (text.isBlank()) return
        val hint = channelRelays[channelId]?.firstOrNull().orEmpty()
        val tags = buildList {
            add(listOf("e", channelId, hint, "root"))
            if (replyTo != null) {
                add(listOf("e", replyTo.id, hint, "reply"))
                add(listOf("p", replyTo.pubkey, hint))
            }
            addAll(hashtagsIn(text).map { listOf("t", it) })
            addAll(emojiTagsIn(text))
            // メンション先へ p タグ（返信相手と重複したら付けない）。
            mentionPubkeysIn(text).forEach { pk ->
                if (pk != replyTo?.pubkey) add(listOf("p", pk))
            }
        }
        publishSigned(UnsignedEvent(kind = 42, content = text, tags = tags))
    }

    // ---- [M10-notif] 通知（自分=#p 宛のリプライ/メンション/リアクション/リポスト） ----
    private val notifJobs = mutableMapOf<String, Job>()

    /** 通知の購読。自分の公開鍵が定まるたびに #p=自分 の REQ を貼り直す。 */
    fun subscribeNotifications(columnId: String) {
        if (!openColumns.add(columnId)) return
        notifJobs[columnId] = scope.launch {
            myPubkeyFlow.collect { me ->
                if (me != null) {
                    // 返信/メンション(1)・リポスト(6/16)・リアクション(7)・Zap受領(9735)・
                    // NIP-22 コメント(1111)[#380] を自分宛(#p)で購読（1111 は P/p 必須なので #p で拾える）。
                    subscribeAll(columnId, Filter(kinds = listOf(1, 6, 16, 7, 9735, 1111), pTags = listOf(me), limit = 200))
                }
            }
        }
    }

    /** 通知フィード（自分宛イベントを種別ごとに整形して新しい順に）。
     *  ホーム混在フィードと通知タブの双方が購読するので StateFlow にキャッシュ（[feedSharing]）。 */
    private val notificationsCache: StateFlow<List<NotificationUi>> by lazy {
        buildNotificationsFeed().stateIn(scope, feedSharing, emptyList())
    }
    fun notificationsFeed(): StateFlow<List<NotificationUi>> = notificationsCache

    // ---- [#9] 通知/DM の未読（最終閲覧時刻方式）----
    private val notifLastSeen = MutableStateFlow(0L)
    private val dmLastSeen = MutableStateFlow(0L)
    private fun loadUnreadSeen() {
        // 初回は「今」を既読基準にする（過去の全通知でバッジが巨大化するのを防ぐ）。
        val now = currentUnixTime()
        notifLastSeen.value = q.getSetting(NOTIF_LAST_SEEN).executeAsOneOrNull()?.toLongOrNull()
            ?: now.also { q.putSetting(NOTIF_LAST_SEEN, it.toString()) }
        dmLastSeen.value = q.getSetting(DM_LAST_SEEN).executeAsOneOrNull()?.toLongOrNull()
            ?: now.also { q.putSetting(DM_LAST_SEEN, it.toString()) }
    }

    /** 通知の未読件数（最終閲覧時刻より新しい通知の数）。 */
    fun notifUnreadFlow(): Flow<Int> =
        combine(notificationsFeed(), notifLastSeen) { list, seen -> list.count { it.createdAt > seen } }

    /** 通知を既読にする（最終閲覧時刻を現在時刻に進める）。 */
    fun markNotificationsSeen() {
        val now = currentUnixTime()
        if (now > notifLastSeen.value) { notifLastSeen.value = now; putSettingAsync(NOTIF_LAST_SEEN, now.toString()) }
    }

    /** DM の未読件数（相手からの kind:14 のうち最終閲覧時刻より新しい数）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun dmUnreadFlow(): Flow<Int> = myPubkeyFlow.flatMapLatest { me ->
        if (me == null) flowOf(0)
        else combine(q.dmAllForMe(me).asFlow().mapToList(Dispatchers.Default), dmLastSeen) { rows, seen ->
            rows.count { it.pubkey != me && it.created_at > seen }
        }
    }

    /** DM を既読にする（最終閲覧時刻を現在時刻に進める）。 */
    fun markDmSeen() {
        val now = currentUnixTime()
        if (now > dmLastSeen.value) { dmLastSeen.value = now; putSettingAsync(DM_LAST_SEEN, now.toString()) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun buildNotificationsFeed(): Flow<List<NotificationUi>> =
        myPubkeyFlow.flatMapLatest { me ->
            if (me == null) flowOf(emptyList())
            else combine(
                q.notificationsFor(me).asFlow().mapToList(Dispatchers.Default),
                profilesFlow,
            ) { rows, profiles ->
                val byPubkey = profiles.associateBy { it.pubkey }
                rows.map { toNotification(it, byPubkey) }
            }.flowOn(Dispatchers.Default)
        }

    /** 自分宛イベント1件を通知行へ整形。種別は kind と #e の有無で判定（NIP-10/18/25）。 */
    private fun toNotification(row: Event, byPubkey: Map<String, app.nostrdeck.db.Profile>): NotificationUi {
        val tags = parseTags(row.tags_json)
        // 直接の対象ノート＝最後の #e（NIP-10 では末尾が reply 先になりがち）。
        val target = tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        // 9735(Zap 受領) の「相手」は receipt 発行者(LNURLサーバ)ではなく Zap 送信者(P タグ/描述)。
        val actorPubkey = if (row.kind.toInt() == 9735)
            (tags.firstOrNull { it.size >= 2 && it[0] == "P" }?.get(1) ?: zapSenderFrom(tags) ?: row.pubkey)
        else row.pubkey
        val actor = profileFor(actorPubkey, byPubkey)
        // 対象イベント本体（抜粋＋種別判定に使う）。
        val targetEvent = target?.let { q.eventById(it).executeAsOneOrNull() }
        // [#380] 対象が記事(30023)ならタイトルを抜粋に（Markdown 全文の先頭より文脈になる）。
        val snippet = targetEvent?.let { t ->
            if (t.kind.toInt() == 30023) {
                parseTags(t.tags_json).firstOrNull { it.size >= 2 && it[0] == "title" }?.get(1)
                    ?: (extractMedia(t.content).first ?: t.content).take(80)
            } else {
                (extractMedia(t.content).first ?: t.content).take(80)
            }
        }
        // [#254] 対象の著者（通知行の1行プレビューにアバターを出す）。
        val targetAuthor = targetEvent?.let { profileFor(it.pubkey, byPubkey) }
        // [#298] 通知の本体に使うノート。
        //  - 対象（自分の投稿）は引用カードで出すので NoteUi 化しておく（引用/返信の再解決はしない＝安い）
        //  - 返信/メンションは相手の投稿そのものを投稿フォーマットで出す。返信元は見出し行が担うため
        //    replyParent は落とす（NoteItem の ◁ 行と二重になる）
        val targetNote = targetEvent?.let { toNoteUi(it, byPubkey[it.pubkey]) }
        // [#380] kind:1111（NIP-22 コメント）も返信と同じく相手の投稿そのものを本体に出す。
        val selfNote = if (row.kind.toInt() == 1 || row.kind.toInt() == Nip22.KIND) {
            withQuoteAndReply(toNoteUi(row, byPubkey[row.pubkey]), row, byPubkey).copy(replyParent = null)
        } else {
            null
        }
        // 対象が kind:42（パブリックチャット）なら、そのルート #e＝チャンネル id をリンク先に。
        val channelId = targetEvent
            ?.takeIf { it.kind.toInt() == 42 }
            ?.let { rootOf(parseTags(it.tags_json)) }
        return when (row.kind.toInt()) {
            9735 -> NotificationUi(
                row.id, NotificationKind.ZAP, actor, row.created_at,
                zapSats = zapAmountSats(tags), targetNoteId = target, targetSnippet = snippet, targetAuthor = targetAuthor,
                note = selfNote, targetNote = targetNote,
                targetChannelId = channelId,
            )
            7 -> {
                // NIP-25/30: "+"/空→❤️、":shortcode:" は emoji タグから画像URLを解決。
                val rx = normalizeReaction(row.content, tags)
                NotificationUi(row.id, NotificationKind.REACTION, actor, row.created_at,
                    reaction = rx.display, reactionImageUrl = rx.imageUrl,
                    targetNoteId = target, targetSnippet = snippet, targetChannelId = channelId,
                    targetAuthor = targetAuthor, note = selfNote, targetNote = targetNote)
            }
            6, 16 -> NotificationUi(row.id, NotificationKind.REPOST, actor, row.created_at,
                targetNoteId = target, targetSnippet = snippet, targetChannelId = channelId,
                targetAuthor = targetAuthor, note = selfNote, targetNote = targetNote)
            else -> {
                // [#380] kind:1111 は常にコメント=返信扱い（トップレベルコメントは小文字 e を
                // 持たないことがあるが、メンションではない）。
                val isReply = row.kind.toInt() == Nip22.KIND || tags.any { it.size >= 2 && it[0] == "e" }
                NotificationUi(
                    row.id, if (isReply) NotificationKind.REPLY else NotificationKind.MENTION,
                    actor, row.created_at,
                    text = extractMedia(row.content).first ?: row.content,
                    targetNoteId = target, targetSnippet = snippet, targetChannelId = channelId,
                    targetAuthor = targetAuthor, note = selfNote, targetNote = targetNote,
                )
            }
        }
    }

    // ---- [M9-thread] NIP-10 スレッド ----

    /**
     * スレッド購読：起点ノートとその root を id 指定で取得し、root/起点宛の返信(#e)を購読する。
     * [#380] NIP-22 コメント(kind:1111)も購読する。#E=ルート指定でツリー全体（孫コメント含む）が
     * 一括で取れる。ルートが記事等の addressable なら #A とルート本体（座標）も購読する。
     */
    fun subscribeThread(columnId: String, focusId: String) {
        if (!openColumns.add(columnId)) return
        val ids = threadAnchorIds(focusId)
        val filters = mutableListOf(
            Filter(ids = ids),
            Filter(kinds = listOf(1, 1111), eTags = ids, limit = 200),
            Filter(kinds = listOf(1111), rootETags = ids, limit = 200),
        )
        threadRootAddress(focusId)?.let { addr ->
            filters += Filter(kinds = listOf(1111), rootATags = listOf(addr), limit = 200)
            // ルート本体（記事 30023 等）を座標で取得。根カード/記事リーダーの表示に使う。
            val parts = addr.split(":")
            val kind = parts.getOrNull(0)?.toIntOrNull()
            if (kind != null && parts.size >= 3) {
                filters += Filter(
                    kinds = listOf(kind), authors = listOf(parts[1]),
                    dTags = listOf(parts.drop(2).joinToString(":")), limit = 1,
                )
            }
        }
        subscribeAll(columnId, *filters.toTypedArray())
    }

    /** [#124] 単一イベントの DB 監視（記事ビューワー等、id 参照の表示用）。未取得なら null を流す。 */
    fun eventByIdFlow(id: String): Flow<NostrEvent?> =
        q.eventById(id).asFlow().mapToOneOrNull(Dispatchers.Default).map { row ->
            row?.let { NostrEvent(it.id, it.pubkey, it.kind.toInt(), it.created_at, it.content, parseTags(it.tags_json), it.sig) }
        }

    /** スレッド表示（深さ付きで root→返信を並べる）。DB の差分に追従する。
     *  [#380] ルートがアドレス（記事等）のときは A タグ参照のコメント群もマージする。 */
    fun threadFeed(focusId: String): Flow<List<ThreadEntry>> {
        val ids = threadAnchorIds(focusId)
        val rootId = ids.lastOrNull() ?: focusId
        val addr = threadRootAddress(focusId)
        val rowsFlow: Flow<List<Event>> =
            if (addr == null) {
                q.threadEvents(ids).asFlow().mapToList(Dispatchers.Default)
            } else {
                combine(
                    q.threadEvents(ids).asFlow().mapToList(Dispatchers.Default),
                    q.commentsByRootAddress(addr).asFlow().mapToList(Dispatchers.Default),
                ) { a, b -> (a + b).distinctBy { it.id }.sortedBy { it.created_at } }
            }
        return combine(rowsFlow, profilesFlow) { rows, profiles ->
            buildThread(rows, focusId, rootId, profiles.associateBy { it.pubkey })
        }.flowOn(Dispatchers.Default)
    }

    /** 起点 id とその root id（DB の focus イベントの e タグから解決。無ければ focus 自身）。
     *  [#380] kind:1111 は NIP-22 のルート E タグで解決する。 */
    private fun threadAnchorIds(focusId: String): List<String> {
        val focus = q.eventById(focusId).executeAsOneOrNull()
        val rootId = focus?.let {
            val tags = parseTags(it.tags_json)
            if (it.kind.toInt() == Nip22.KIND) Nip22.rootEventIdOf(tags) else rootOf(tags)
        } ?: focusId
        return listOf(focusId, rootId).distinct()
    }

    /**
     * [#380] スレッドのルートがアドレス（記事等の addressable）ならその座標 "kind:pubkey:d"。
     *  - focus が kind:1111 … ルート A タグ
     *  - focus 自身が addressable（記事リーダーから開いたコメント欄）… 自分の座標
     */
    private fun threadRootAddress(focusId: String): String? {
        val focus = q.eventById(focusId).executeAsOneOrNull() ?: return null
        val kind = focus.kind.toInt()
        return when {
            kind == Nip22.KIND -> Nip22.rootAddressOf(parseTags(focus.tags_json))
            kind in 30_000..39_999 -> {
                val d = parseTags(focus.tags_json).firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
                "$kind:${focus.pubkey}:$d"
            }
            else -> null
        }
    }

    /** 取得済みイベント群から深さ優先のスレッドを組む（NIP-10 の e マーカー/位置で親を決める）。
     *  [#380] kind:1111 は NIP-22 解釈（小文字 e=親、無ければルート E/A 直下）で並立させる。 */
    private fun buildThread(
        rows: List<Event>, focusId: String, rootId: String,
        byPubkey: Map<String, app.nostrdeck.db.Profile>,
    ): List<ThreadEntry> {
        // アドレス→id（1111 の親 a 解決用）。ルートの記事等が取得済みならここに載る。
        val addrToId = rows.filter { it.kind in 30_000..39_999 }.associate { row ->
            val d = parseTags(row.tags_json).firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
            "${row.kind}:${row.pubkey}:$d" to row.id
        }
        // ノートとして並べるのは kind:1/1111 のみ。記事本体等の非ノートルートは
        // 行として出さない（根カード/記事リーダー側が担う。全文がノート面に出るのを防ぐ）。
        val notes = rows.filter { it.kind.toInt() == 1 || it.kind.toInt() == Nip22.KIND }
        val byId = notes.associateBy { it.id }
        val parentOf = notes.associate { row ->
            val tags = parseTags(row.tags_json)
            row.id to if (row.kind.toInt() == Nip22.KIND) Nip22.threadParentOf(tags, addrToId)
            else replyParentOf(tags)
        }
        val children = HashMap<String, MutableList<Event>>()
        notes.forEach { row ->
            val p = parentOf[row.id]
            if (p != null && byId.containsKey(p)) children.getOrPut(p) { mutableListOf() }.add(row)
        }
        val out = ArrayList<ThreadEntry>()
        fun visit(row: Event, depth: Int) {
            val parentId = parentOf[row.id]
            val replyToName = parentId?.let { byId[it] }?.let { parent ->
                byPubkey[parent.pubkey]?.name?.takeIf { it.isNotBlank() } ?: parent.pubkey.take(8)
            }
            out.add(
                ThreadEntry(
                    note = withQuoteAndReply(toNoteUi(row, byPubkey[row.pubkey]), row, byPubkey),
                    depth = depth, replyToName = replyToName,
                    isRoot = row.id == rootId, isFocused = row.id == focusId,
                ),
            )
            children[row.id]?.sortedBy { it.created_at }?.forEach { visit(it, depth + 1) }
        }
        // 親が取得集合に居ない（=スレッドの起点）行から DFS。
        notes.filter { parentOf[it.id] == null || parentOf[it.id] !in byId }
            .sortedBy { it.created_at }
            .forEach { visit(it, 0) }
        return out
    }

    // [#314] NIP-10 の読み取り/組み立ては Nip10 に集約（純関数なので単体テストできる）。
    private fun replyParentOf(tags: List<List<String>>): String? = Nip10.replyParentOf(tags)

    private fun rootOf(tags: List<List<String>>): String? = Nip10.rootOf(tags)

    /** [M10] 自分の♡/リポスト/リアクション状態を NoteUi に反映（ボタンのハイライト・絵文字表示用）。 */
    private fun applyMeta(ui: NoteUi, meta: NoteMeta): NoteUi = ui.copy(
        mineReacted = ui.event.id in meta.myReacted,
        mineReaction = meta.myReaction[ui.event.id],
        mineReposted = ui.event.id in meta.myReposted,
    )

    /**
     * [#384] 指定ユーザーの addressable イベント（記事 kind:30023 等）を新しい順で流す。
     * replaceable なので同一 `d` タグは最新版だけに畳む（リレーには古い版も残っている）。
     * 購読は呼び出し側が [subscribeColumn] で行う（ここは DB の読み出しのみ）。
     */
    fun addressableEventsFlow(kind: Int, pubkey: String, limit: Long = 100): Flow<List<NostrEvent>> =
        q.eventsByKindAuthorLimit(kind.toLong(), pubkey, limit).asFlow().mapToList(Dispatchers.Default)
            .map { rows ->
                latestByDTag(
                    rows.map {
                        NostrEvent(it.id, it.pubkey, it.kind.toInt(), it.created_at, it.content, parseTags(it.tags_json), it.sig)
                    },
                )
            }.flowOn(Dispatchers.Default)

    // ---- [M9-profile] プロフィール表示 / フォロー操作 ----

    /** 指定 pubkey の解決済みプロフィール（kind:0）を流す。未取得なら null。 */
    // [#244] プロフィール明示オープン時の一時 sub 連番（UI スレッドからのみ触る。
    // バッチループ側の profileReqSeq とは分離してレースを避ける）。
    private var profileOpenSeq = 0

    fun profileFlow(pubkey: String): Flow<Profile?> =
        q.profileByPubkey(pubkey).asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.firstOrNull()?.let {
                Profile(it.pubkey, it.name, it.handle, it.picture_url, it.updated_at, it.about, it.website, it.lud16, it.banner)
            }
        }

    /**
     * プロフィール画面を開いたとき等に kind:0 の取得を促す。
     * [#244] バッチ REQ（セッション内 dedup あり）に加えて、明示的に開いた相手は
     * 毎回強制再取得する（接続中リレー全体 + インデクサ）。キャッシュ済みでも
     * 相手が kind:0 を更新していれば updateProfileIfNewer で反映される。
     */
    fun loadProfile(pubkey: String) {
        requestProfile(pubkey)
        val subId = "profile-open-${profileOpenSeq++}"
        subscribeAll(subId, Filter(kinds = listOf(0, 10002), authors = listOf(pubkey), limit = 4))
        scope.launch { delay(10_000); unsubscribeAll(subId) }
        requestProfileFromIndexers(listOf(pubkey))
    }

    /**
     * NIP-05 検証。`nip05`（kind:0 の handle, 例: name@example.com）を
     * `https://<domain>/.well-known/nostr.json?name=<local>` で引き、
     * 返ってきた pubkey が当該ユーザーの hex と一致するか確認する。
     * 一致 → true（OK）／不一致・取得失敗・不正形式 → false（異常）。
     */
    suspend fun verifyNip05(pubkey: String, nip05: String): Boolean = withContext(Dispatchers.Default) {
        runCatching {
            val id = nip05.trim()
            if (id.isEmpty()) return@runCatching false
            val at = id.indexOf('@')
            // 「name@domain」。@ が無い場合はドメインのみとみなし local="_"（ルート識別子）。
            val local = if (at >= 0) id.substring(0, at) else "_"
            val domain = (if (at >= 0) id.substring(at + 1) else id).lowercase()
            if (domain.isEmpty() || !domain.contains('.')) return@runCatching false
            val url = "https://$domain/.well-known/nostr.json?name=$local"
            val body = uploadHttp.get(url).bodyAsText()
            val names = json.parseToJsonElement(body).jsonObject["names"]?.jsonObject ?: return@runCatching false
            val resolved = names[local]?.jsonPrimitive?.contentOrNull ?: return@runCatching false
            resolved.equals(pubkey, ignoreCase = true)
        }.getOrDefault(false)
    }

    /** [M10] 本文メンション解決用に pubkey(hex)→表示名 のマップを流す（名前が空のものは除外）。 */
    fun profileNames(): Flow<Map<String, String>> =
        profilesFlow.map { rows -> rows.filter { it.name.isNotBlank() }.associate { it.pubkey to it.name } }

    /** [M11-compose] ログイン中の公開鍵（アバターのシード等に使う。未確定なら null）。 */
    fun loggedInPubkey(): Flow<String?> = myPubkeyFlow

    /** [M11-compose] ログイン中アカウント自身の解決済みプロフィール（投稿モーダルのヘッダ表示用）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun myProfileFlow(): Flow<Profile?> =
        myPubkeyFlow.flatMapLatest { me -> if (me == null) flowOf(null) else profileFlow(me) }

    /**
     * [M11] メンション補完用：キャッシュ済みプロフィールを name / handle(nip05) の前方一致で検索。
     * 大文字小文字を無視し、name が非空のものを優先して最大 [limit] 件返す（同期・キャッシュのみ）。
     */
    suspend fun searchProfiles(prefix: String, limit: Int = 8): List<Profile> {
        val p = prefix.trim().lowercase()
        if (p.isEmpty()) return emptyList()
        // [#250] SQL 側で前方一致 + LIMIT。従来は全プロフィールをメモリへ読んでから filter して
        // おり、入力1文字ごとにメインスレッドが数十〜数百ms 止まっていた（キーボードのフリーズ）。
        // 実行も Default へ退避してメインスレッドを塞がない。
        return withContext(Dispatchers.Default) {
            q.searchProfilesByPrefix(p, limit.toLong()).executeAsList()
                .map { Profile(it.pubkey, it.name, it.handle, it.picture_url, it.updated_at) }
        }
    }

    /**
     * [#310] ユーザー検索（kind:0）。検索画面の「ユーザー」タブが使う。
     *
     * NIP-50 対応リレーへ kind:0 の全文検索を投げる。届いた kind:0 は ingest が profile 表へ
     * 入れるので、表示は [searchProfilesFlow] がローカルを読むだけでよい（他の検索と同じ
     * cache-first の流れ）。購読は検索語ごとに張り替える。
     */
    fun subscribeProfileSearch(subId: String, query: String) {
        val q0 = query.trim()
        if (q0.isEmpty()) return
        unsubscribeColumn(subId)
        openColumns.add(subId)
        subscribeTargeted(
            subId, SEARCH_RELAYS.toSet(),
            Filter(kinds = listOf(0), search = q0, limit = 100),
        )
    }

    /**
     * [#310] ローカルの profile 表から検索語に一致するユーザーを流す。
     * 部分一致（名前 / NIP-05 / 自己紹介）で、前方一致を上位に寄せる。
     */
    fun searchProfilesFlow(query: String, limit: Int = 50): Flow<List<Profile>> {
        val q0 = query.trim().lowercase()
        if (q0.isEmpty()) return flowOf(emptyList())
        return q.searchProfilesByText(q0, limit.toLong()).asFlow().mapToList(Dispatchers.Default)
            .map { rows ->
                rows.map {
                    Profile(
                        it.pubkey, it.name, it.handle, it.picture_url, it.updated_at,
                        about = it.about, website = it.website, lud16 = it.lud16, banner = it.banner,
                    )
                }
            }
            .flowOn(Dispatchers.Default)
    }

    /** 自分がこの pubkey をフォロー中か（kind:3 の更新に追従）。 */
    fun isFollowingFlow(pubkey: String): Flow<Boolean> = follows.map { pubkey in it }

    /** [#264] フォロー中の pubkey 一覧（テーマストアの「フォロー中」絞り込み等）。 */
    fun followsFlow(): StateFlow<List<String>> = follows

    /** フォロー追加（kind:3 を publish）。楽観的に follows へ反映。 */
    suspend fun follow(pubkey: String) {
        val cur = follows.value
        if (pubkey in cur) return
        publishContacts(cur + pubkey)
    }

    /** フォロー解除。 */
    suspend fun unfollow(pubkey: String) {
        val cur = follows.value
        if (pubkey !in cur) return
        publishContacts(cur - pubkey)
    }

    /** 現在のフォロー集合を kind:3（p タグ）として publish し、楽観反映する。 */
    private suspend fun publishContacts(list: List<String>) {
        publishSigned(UnsignedEvent(kind = 3, content = "", tags = list.map { listOf("p", it) }))
        followsAt = currentUnixTime()
        follows.value = list
    }

    /**
     * フィルターに一致するキャッシュ済みノートを破棄する（カラムのフィルター編集時）。
     * UI は DB Flow を読むため一旦空になり、貼り直した REQ の受信で埋め直される（=リロード）。
     */
    fun purgeFeedCache(filter: ReqFilter) {
        val ids = when {
            filter.hashtags.isNotEmpty() -> q.feedByHashtag(filter.hashtags.first().lowercase())
            filter.authors.isNotEmpty() -> q.feedByAuthors(filter.authors, 0L)
            !filter.search.isNullOrBlank() -> q.feedBySearch(filter.search)
            else -> q.recentNotes(300L)
        }.executeAsList().map { it.id }
        q.transaction { ids.forEach { id -> q.deleteEventById(id); q.deleteTagsForEvent(id) } }
    }

    private fun rowsFlow(filter: ReqFilter): Flow<List<Event>> {
        // [#135] 複合検索: SQL では広めに取り、AND/OR の締めは Kotlin 側で行う。
        if (filter.words.isNotEmpty()) return compositeSearchRows(filter)
        return when {
            filter.hashtags.isNotEmpty() -> q.feedByHashtag(filter.hashtags.first().lowercase())
            // [#134] プロフィール（投稿+リポスト）: kind:6/16 を含む要求は専用クエリで。
            filter.authors.isNotEmpty() && filter.kinds.any { it == 6 || it == 16 } ->
                q.feedAuthorsWithReposts(filter.authors, 0L)
            filter.authors.isNotEmpty() -> q.feedByAuthors(filter.authors, 0L)
            !filter.search.isNullOrBlank() -> q.feedBySearch(filter.search)
            else -> q.recentNotes(300L)
        }.asFlow().mapToList(Dispatchers.Default)
    }

    /**
     * [#135] キーワード・タグフィードのローカル読み出し（条件は OR）。
     *
     * [#259] 以前は `recentNotes(600)`＝「**全ノートの最新600件**」という共通の窓から Kotlin 側で
     * 絞り込んでいた。そのため他カラム（フォロー中/グローバル等）の新着が流れ込むと、検索リレーから
     * 取得した該当イベントが窓の外へ押し出され、**検索を繰り返すほど結果が数件に減る**不具合があった。
     * 窓をやめて、単語は `content LIKE`・タグは `event_tag` 索引で **SQL 側から直接**引き、
     * 各クエリ結果を id で重複排除して新しい順に並べる（母集合が窓に依存しなくなる）。
     */
    private fun compositeSearchRows(filter: ReqFilter): Flow<List<Event>> {
        val perQuery = SEARCH_ROWS_PER_QUERY
        val sources = buildList<Flow<List<Event>>> {
            filter.words.forEach { w ->
                add(q.searchNotesByWord(w.lowercase(), perQuery).asFlow().mapToList(Dispatchers.Default))
            }
            if (filter.hashtags.isNotEmpty()) {
                add(
                    q.feedByHashtagsAny(filter.hashtags.map { it.lowercase() }, perQuery)
                        .asFlow().mapToList(Dispatchers.Default),
                )
            }
        }
        if (sources.isEmpty()) return flowOf(emptyList())
        if (sources.size == 1) {
            return sources.first().map { it.take(SEARCH_ROWS_TOTAL) }.flowOn(Dispatchers.Default)
        }
        // 複数条件は OR。各クエリの結果を結合し、id で重複排除して新しい順に。
        return combine(sources) { lists ->
            lists.asSequence().flatMap { it.asSequence() }
                .distinctBy { it.id }
                .sortedByDescending { it.created_at }
                .take(SEARCH_ROWS_TOTAL)
                .toList()
        }.flowOn(Dispatchers.Default)
    }

    private fun ReqFilter.toProtocol(limit: Int) = Filter(
        authors = authors.ifEmpty { null },
        kinds = kinds.ifEmpty { listOf(1) },
        hashtags = hashtags.ifEmpty { null },
        eTags = channelId?.let { listOf(it) },  // NIP-28: kind:42 を #e でチャンネルに絞る
        search = search,
        limit = limit,
    )

    /**
     * kind:1 ノートを投稿（NIP-01）。
     * 署名 → 楽観的にローカル DB へ挿入（即時表示）→ publish_queue へ積み、各リレーへ送信。
     */
    suspend fun publishNote(content: String, contentWarning: String? = null) {
        // NIP-24/NIP-12: 本文中の #ハッシュタグ を 't' タグ / NIP-30: :shortcode: を emoji タグに。
        // [#5] NIP-36: センシティブ指定時は content-warning タグを付与（理由は任意）。
        // [#350] NIP-27: nostr:npub/nprofile メンションへ p タグ（無いと Bot 等の #p 購読に届かない）。
        val tags = hashtagsIn(content).map { listOf("t", it) } + emojiTagsIn(content) +
            Nip27.mentionPTags(content) +
            (if (contentWarning != null) listOf(listOf("content-warning", contentWarning)) else emptyList())
        val signed = publishSigned(UnsignedEvent(kind = 1, content = content, tags = tags))
        recordHashtags(content, signed.createdAt)
    }

    /**
     * [#13] 連投スレッド。[segments] を先頭から順に投稿し、2件目以降は NIP-10 で
     * root(先頭) と reply(直前) を e タグに付けて自己スレッド化する。
     */
    suspend fun publishThread(segments: List<String>, contentWarning: String? = null) {
        val segs = segments.map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return
        var rootId: String? = null
        var prevId: String? = null
        var myPk: String? = null
        for (seg in segs) {
            val tags = buildList {
                if (rootId != null) {
                    // [#314] e タグ5番目に作者を入れる。自己スレッドなので root も直前も自分。
                    val me = myPk
                    add(if (me != null) listOf("e", rootId!!, "", "root", me) else listOf("e", rootId!!, "", "root"))
                    if (prevId != null && prevId != rootId) {
                        add(if (me != null) listOf("e", prevId!!, "", "reply", me) else listOf("e", prevId!!, "", "reply"))
                    }
                    me?.let { add(listOf("p", it)) }
                }
                addAll(hashtagsIn(seg).map { listOf("t", it) })
                addAll(emojiTagsIn(seg))
                // [#350] NIP-27 メンションの p タグ（自己スレッド用に積んだ自分の p と重複させない）。
                addAll(Nip27.mentionPTags(seg, mapNotNull { if (it.size >= 2 && it[0] == "p") it[1] else null }))
                // [#315] CW は全セグメントに付ける。1本目だけだと後続が素通しになり、
                // タイムラインでは各セグメントが独立したノートとして流れるため意味を成さない。
                if (contentWarning != null) add(listOf("content-warning", contentWarning))
            }
            val signed = publishSigned(UnsignedEvent(kind = 1, content = seg, tags = tags))
            recordHashtags(seg, signed.createdAt)
            if (rootId == null) { rootId = signed.id; myPk = signed.pubkey }
            prevId = signed.id
        }
    }

    // [#13] 投稿の下書き（未送信テキスト）を1枠だけ KV に保持。閉じたら保存/次回開いたら復元。
    fun saveDraft(text: String) = putSettingAsync(COMPOSE_DRAFT, text)
    fun loadDraft(): String = q.getSetting(COMPOSE_DRAFT).executeAsOneOrNull().orEmpty()
    fun clearDraft() = putSettingAsync(COMPOSE_DRAFT, "")

    /**
     * [#316] 連投で積んだセグメントの下書き。本文1枠の [saveDraft] とは別枠。
     *
     * 積んだぶんが保存されないと、5本書いたところでシートを閉じただけで全部消える。
     * 区切り文字ではなく JSON 配列で持つ（本文に何が入っていても壊れないため）。
     * [editIndex] は「本文がスレッドの何番目か」。復元時に順序を保つのに要る。
     */
    fun saveThreadDraft(segments: List<String>, editIndex: Int) {
        if (segments.isEmpty()) { clearThreadDraft(); return }
        val payload = buildJsonObject {
            put("edit", JsonPrimitive(editIndex))
            put("segs", JsonArray(segments.map { JsonPrimitive(it) }))
        }
        putSettingAsync(COMPOSE_THREAD_DRAFT, payload.toString())
    }

    /** 保存済みの連投下書き（セグメント列と本文の位置）。無ければ null。 */
    fun loadThreadDraft(): Pair<List<String>, Int>? = runCatching {
        val raw = q.getSetting(COMPOSE_THREAD_DRAFT).executeAsOneOrNull().orEmpty()
        if (raw.isBlank()) return null
        val o = json.parseToJsonElement(raw).jsonObject
        val segs = (o["segs"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: return null
        if (segs.isEmpty()) return null
        segs to (o["edit"]?.jsonPrimitive?.content?.toIntOrNull() ?: segs.size).coerceIn(0, segs.size)
    }.getOrNull()

    fun clearThreadDraft() = putSettingAsync(COMPOSE_THREAD_DRAFT, "")

    /**
     * [M8] NIP-25 リアクション（kind:7）。デフォルトは "+"（♡=いいね）。即時にカウント反映。
     * カスタム絵文字は [emoji]=":shortcode:" + [imageUrl] を渡すと NIP-30 の `emoji` タグを付ける。
     * "+" 以外はピッカーの「最近」（used_emoji）に記録する。
     */
    suspend fun publishReaction(target: NostrEvent, emoji: String = "+", imageUrl: String? = null) {
        val tags = buildList {
            add(listOf("e", target.id))
            add(listOf("p", target.pubkey))
            if (imageUrl != null && emoji.length >= 2 && emoji.startsWith(":") && emoji.endsWith(":")) {
                add(listOf("emoji", emoji.substring(1, emoji.length - 1), imageUrl))
            }
        }
        publishSigned(UnsignedEvent(kind = 7, content = emoji, tags = tags))
        recordUsedEmoji(emoji, imageUrl)
    }

    /**
     * [#6] NIP-56 通報。kind:1984 で対象の投稿/ユーザーを報告する。
     * [type] は "illegal"/"spam"/"nudity"/"profanity"/"impersonation"/"malware"/"other"。
     * 児童の安全に関わる内容は "illegal" を用いる。[reason] は任意の補足。
     */
    suspend fun reportNote(target: NostrEvent, type: String, reason: String = "") {
        val tags = listOf(
            listOf("e", target.id, type),
            listOf("p", target.pubkey),
        )
        publishSigned(UnsignedEvent(kind = 1984, content = reason, tags = tags))
    }

    /** [#95] NIP-56 ユーザー通報。対象イベントの無い通報は p タグにレポートタイプを付す。 */
    suspend fun reportUser(pubkey: String, type: String, reason: String = "") {
        publishSigned(UnsignedEvent(kind = 1984, content = reason, tags = listOf(listOf("p", pubkey, type))))
    }

    /** リアクションピッカーの「最近」用に、飛ばした絵文字を記録（"+"/空は対象外）。 */
    private fun recordUsedEmoji(content: String, imageUrl: String?) {
        if (content == "+" || content.isEmpty()) return
        val now = currentUnixTime()
        q.insertUsedEmojiIfAbsent(content, imageUrl, now)
        q.touchUsedEmoji(now, imageUrl, content)
    }

    /** リアクションピッカー: 自分のカスタム絵文字（NIP-51 kind:10030/30030 由来）一覧。 */
    fun customEmojisFlow(): Flow<List<CustomEmoji>> =
        q.allCustomEmojis().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { CustomEmoji(it.shortcode, it.image_url) }
        }

    /** リアクションピッカー: 過去に飛ばした絵文字（最近/よく使う順）。 */
    fun recentEmojisFlow(): Flow<List<UsedEmoji>> =
        q.usedEmojisByRecency().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { UsedEmoji(it.content, it.image_url) }
        }

    /**
     * [M8-counts] ♡ のトグル。未リアクションなら "+" を送信、既にリアクション済みなら
     * NIP-09 削除イベント(kind:5)で取り消し、ローカルからも除去する（ハイライト/数が即反映）。
     */
    suspend fun toggleReaction(target: NostrEvent) = reactWithDefault(target)

    /**
     * [M16] ♡ボタン＝デフォルトリアクションのトグル。未リアクションなら設定のデフォルト内容で kind:7、
     * 既に同じデフォルト内容で付けていれば NIP-09 削除(kind:5)で取り消す。
     * （絵文字ピッカーからのリアクションは publishReaction で何度でも重ねられる＝ここでは触らない）
     */
    suspend fun reactWithDefault(target: NostrEvent) {
        val pk = myPubkey ?: SignerProvider.current().publicKeyHex().also {
            myPubkey = it; myPubkeyFlow.value = it
        }
        val (content, img) = defaultReactionState.value
        val stored = normalizedDefaultReaction()
        val mineId = q.myReactionIdForContent(pk, target.id, stored).executeAsOneOrNull()
        if (mineId != null) {
            // [#319] k タグを付けて NIP-09 の推奨形に揃える。ローカルは deleted_event にも
            // 残す（消したリアクションが再取得で戻り、押した状態に見えるのを防ぐ）。
            publishSigned(
                UnsignedEvent(kind = 5, content = "", tags = listOf(listOf("e", mineId), listOf("k", "7"))),
            )
            q.transaction { forgetEventLocally(mineId, currentUnixTime()) }
        } else {
            publishReaction(target, content, img)
        }
    }

    /** デフォルトリアクションを KV から復元（未設定は "+"＝❤️）。start() から呼ぶ。 */
    private fun loadDefaultReaction() {
        val c = q.getSetting(DEFAULT_REACTION_CONTENT).executeAsOneOrNull()?.ifBlank { null } ?: "+"
        val img = q.getSetting(DEFAULT_REACTION_IMAGE).executeAsOneOrNull()?.ifBlank { null }
        defaultReactionState.value = c to img
    }

    /** デフォルトリアクションを設定（設定画面のピッカーから）。content=":shortcode:" のときは imageUrl も保存。 */
    fun setDefaultReaction(content: String, imageUrl: String?) {
        defaultReactionState.value = content to imageUrl
        putSettingAsync(DEFAULT_REACTION_CONTENT, content)
        putSettingAsync(DEFAULT_REACTION_IMAGE, imageUrl ?: "")
    }

    /** [M8] NIP-18 リポスト（kind:6）。content は空でよく、表示側は e タグから元ノートを解決する。 */
    suspend fun publishRepost(target: NostrEvent) {
        publishSigned(
            UnsignedEvent(
                kind = 6, content = "",
                tags = listOf(listOf("e", target.id), listOf("p", target.pubkey)),
            ),
        )
    }

    /**
     * [M10] NIP-18 引用リポスト（kind:1）。q タグ + p タグで参照し、本文末尾に nostr:nevent… を添える。
     * 表示側は q タグから引用元を解決して埋め込みカードにする（toFollowingNoteUi）。
     */
    suspend fun publishQuote(target: NostrEvent, text: String, contentWarning: String? = null) {
        val note = runCatching { Nip19.hexToNote(target.id) }.getOrNull()
        val body = if (note != null) (if (text.isBlank()) "nostr:$note" else "$text\nnostr:$note") else text
        // [#350] NIP-27 メンションの p タグ（引用先の作者と重複させない）。
        val tags = listOf(listOf("q", target.id), listOf("p", target.pubkey)) +
            hashtagsIn(text).map { listOf("t", it) } + emojiTagsIn(body) +
            Nip27.mentionPTags(text, listOf(target.pubkey)) +
            (if (contentWarning != null) listOf(listOf("content-warning", contentWarning)) else emptyList())
        val signed = publishSigned(UnsignedEvent(kind = 1, content = body, tags = tags))
        recordHashtags(text, signed.createdAt)
    }

    /**
     * [M8] NIP-10 返信（kind:1）。本文の #タグも 't' 化する。
     *
     * [#314] 以前は返信先が何であっても `["e", id, "", "reply"]` 1本を付けていた。
     * そのためトップレベル投稿への返信が他クライアントから「ルート不明の返信」に見え、
     * スレッド途中への返信では root が落ちて元スレッドから切り離されていた。
     * 組み立ては Nip10.replyTags に移し、root を解決したうえで仕様どおりに並べる。
     */
    // ---- [#319] NIP-09 削除リクエスト（kind:5）----

    /**
     * 自分のイベントに削除リクエストを出す。
     *
     * **これは「消してください」という依頼であって、消えたことの保証ではない。**
     * リレーが尊重するかは相手次第で、既に取り込んだクライアントが表示を続けることもある。
     * UI 側でもその旨を明示すること。
     *
     * タグは `["e", <id>]` ＋ `["k", <kind>]`。addressable(30000-39999) は id だけだと
     * 版が変わると効かないので `["a", "<kind>:<pubkey>:<d>"]` も付ける。
     * [reason] は content に入る（任意。空でよい）。
     *
     * ローカルは即座に消す。リレーが尊重しなくても手元からは見えなくなるほうが期待に近い。
     * 消した id は [deleted_event] に残し、再取得しても復活させない。
     */
    suspend fun requestDelete(event: NostrEvent, reason: String = ""): Boolean = runCatching {
        val me = myPubkey ?: SignerProvider.current().publicKeyHex().also {
            myPubkey = it; myPubkeyFlow.value = it
        }
        // 他人のイベントに削除リクエストを出しても無意味（リレーは作者一致を見る）。
        if (event.pubkey != me) return false
        val tags = buildList {
            add(listOf("e", event.id))
            add(listOf("k", event.kind.toString()))
            if (event.kind in 30_000..39_999) {
                val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
                add(listOf("a", "${event.kind}:${event.pubkey}:$d"))
            }
        }
        publishSigned(UnsignedEvent(kind = 5, content = reason, tags = tags))
        forgetEventLocally(event.id, currentUnixTime())
        if (event.kind in 30_000..39_999) {
            val d = event.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
            forgetAddrLocally(event.kind, event.pubkey, d, currentUnixTime())
        }
        true
    }.getOrElse {
        println("Nostrism requestDelete failed: $it")
        false
    }

    /**
     * 受信した削除リクエストを反映する。
     *
     * **作者本人のものだけ効かせる。** そうしないと、他人が `["e", <あなたの投稿>]` を含む
     * kind:5 を投げるだけで手元から消せてしまう。手元に対象が無いときは作者を確認できないので
     * 何もしない（後で本体が降ってきたら、その時点では削除リクエストを再処理できないが、
     * 他人の投稿を消される危険を冒すよりよい）。
     */
    private fun ingestDeletion(e: NostrEvent) {
        e.tags.forEach { t ->
            if (t.size < 2) return@forEach
            when (t[0]) {
                "e" -> {
                    val row = q.eventById(t[1]).executeAsOneOrNull() ?: return@forEach
                    if (row.pubkey == e.pubkey) forgetEventLocally(t[1], e.createdAt)
                }
                "a" -> {
                    // "<kind>:<pubkey>:<d>"。pubkey が座標に入っているので作者を確認できる。
                    val parts = t[1].split(":")
                    if (parts.size < 3) return@forEach
                    val kind = parts[0].toIntOrNull() ?: return@forEach
                    if (parts[1] != e.pubkey) return@forEach
                    forgetAddrLocally(kind, parts[1], parts.drop(2).joinToString(":"), e.createdAt)
                }
            }
        }
    }

    /** 手元から消し、再取得で復活しないよう id を覚える。 */
    private fun forgetEventLocally(id: String, deletedAt: Long) {
        q.markEventDeleted(id, deletedAt)
        q.deleteTagsForEvent(id)
        q.deleteEventById(id)
    }

    /** addressable を座標で消す。削除リクエストより新しい版は残す（再公開したものまで消さない）。 */
    private fun forgetAddrLocally(kind: Int, pubkey: String, d: String, deletedAt: Long) {
        q.markAddrDeleted("$kind:$pubkey:$d", deletedAt)
        q.deleteAddrEvents(kind.toLong(), pubkey, deletedAt, d)
    }

    suspend fun publishReply(target: NostrEvent, text: String, contentWarning: String? = null) {
        // [#314] 返信先のタグは **DB から引き直す**。タイムライン経路の NoteUi は再コンポーズ
        // 最適化のため event.tags を空で持つ（toFollowingNoteUi の [#140]）ので、渡ってきた
        // target.tags をそのまま信じると root が解決できず、スレッド途中への返信でも
        // 「返信先＝ルート」と誤って組み立ててしまう（＝元スレッドから切り離される）。
        val targetTags = target.tags.ifEmpty {
            q.eventById(target.id).executeAsOneOrNull()?.let { parseTags(it.tags_json) }.orEmpty()
        }
        // [#380] NIP-22 コメント(kind:1111)への返信は kind:1111 で発行してツリーの一貫性を保つ。
        // それ以外（新規コメント・kind:1 への返信）は従来どおり kind:1（旧クライアント互換）。
        if (target.kind == Nip22.KIND) {
            val nip22Tags = Nip22.replyTags(target.id, target.pubkey, targetTags)
            val inheritedPs22 = nip22Tags.mapNotNull { if (it.size >= 2 && (it[0] == "p" || it[0] == "P")) it[1] else null }
            val tags = nip22Tags +
                hashtagsIn(text).map { listOf("t", it) } + emojiTagsIn(text) +
                Nip27.mentionPTags(text, inheritedPs22) +
                (if (contentWarning != null) listOf(listOf("content-warning", contentWarning)) else emptyList())
            val signed = publishSigned(UnsignedEvent(kind = Nip22.KIND, content = text, tags = tags))
            recordHashtags(text, signed.createdAt)
            return
        }
        // ルート作者は e タグ5番目に入れる。ローカルに無ければ省く（無理に埋めない）。
        val rootId = rootOf(targetTags)
        val rootAuthor = if (rootId == null || rootId == target.id) target.pubkey
        else q.eventById(rootId).executeAsOneOrNull()?.pubkey
        val nip10Tags = Nip10.replyTags(
            targetId = target.id, targetPubkey = target.pubkey, targetTags = targetTags,
            rootAuthor = rootAuthor, selfPubkey = myPubkey,
        )
        // [#350] NIP-27 メンションの p タグ。NIP-10 で継承済みの p（返信相手・スレッド参加者）
        // とは重複させない。返信文中で新たに呼んだ相手にだけ足す。
        val inheritedPs = nip10Tags.mapNotNull { if (it.size >= 2 && it[0] == "p") it[1] else null }
        val tags = nip10Tags +
            hashtagsIn(text).map { listOf("t", it) } + emojiTagsIn(text) +
            Nip27.mentionPTags(text, inheritedPs) +
            (if (contentWarning != null) listOf(listOf("content-warning", contentWarning)) else emptyList())
        val signed = publishSigned(UnsignedEvent(kind = 1, content = text, tags = tags))
        recordHashtags(text, signed.createdAt)
    }

    /**
     * 署名 → 楽観的にローカル DB へ挿入（即時表示）→ publish_queue へ積み、各リレーへ送信。
     * 署名済みイベントを返す（ハッシュタグ記録の createdAt 等に使う）。
     */
    /**
     * NIP-89: 公開コンテンツイベントに `["client","Nostrism"]` を付与する（既にあれば触らない）。
     * 対象は投稿/返信/引用(1)・リポスト(6/16)・リアクション(7)・パブリックチャット(42) のみ。
     * プロフィール(0)/フォロー(3)/削除(5)/各種リスト(10000/10002/10030)/DM 等には付けない。
     */
    private fun withClientTag(unsigned: UnsignedEvent): UnsignedEvent {
        if (unsigned.kind !in CLIENT_TAG_KINDS) return unsigned
        if (unsigned.tags.any { it.firstOrNull() == "client" }) return unsigned
        return unsigned.copy(tags = unsigned.tags + listOf(listOf("client", CLIENT_NAME)))
    }

    private suspend fun publishSigned(unsigned: UnsignedEvent): NostrEvent {
        val signed = SignerProvider.current().sign(withClientTag(unsigned))
        val payload = RelayProtocol.event(signed)
        // kind:7 は ingest と同じ正規化("+"/空→❤️)でローカル保存し、集約表示と整合させる。
        val storedContent =
            if (signed.kind == 7) (if (signed.content == "+" || signed.content.isEmpty()) "❤️" else signed.content)
            else signed.content
        // 本体とタグ索引を原子的に書く。別々だと索引前の瞬間を読んだクエリが
        // 「e タグの無い kind:7」を観測してしまう(#78)。
        q.transaction {
            q.insertEvent(signed.id, signed.pubkey, signed.kind.toLong(), signed.createdAt, storedContent, tagsToJson(signed.tags), signed.sig)
            indexTags(signed)
            q.enqueuePublish(signed.id, payload, signed.createdAt, 0)
        }
        // NIP-65 outbox: write(Outbox) リレー ∪ 接続中(Inbox)リレーへ配信する。
        publishTo(payload)
        // TODO: handle OK/NIP-20, retry from publish_queue
        return signed
    }

    /**
     * 署名済みイベント JSON を配信する。配信先は write リレー ∪ 接続中リレー。
     * 接続中(=Inbox/read)のものはそのまま送り、未接続の write 専用リレーへは
     * 一時接続を張って送信し、フラッシュ後に閉じる（購読は張らない）。
     */
    private suspend fun publishTo(payload: String) = withContext(relayDispatcher) {
        val writeUrls = q.allRelays().executeAsList().filter { it.write != 0L }.map { it.url }
        val connectedUrls = relays.keys.toList()
        (writeUrls + connectedUrls).toSet().forEach { url ->
            val c = relays[url]
            if (c != null) c.publish(payload) else scope.launch { publishTransient(url, payload) }
        }
    }

    /** 指定リレー**のみ**へ配信する（NIP-17 DM: 受信者/自分の kind:10050 リレーへ届けるため）。 */
    private suspend fun publishToRelays(payload: String, urls: Collection<String>) = withContext(relayDispatcher) {
        urls.map { normalizeRelayUrl(it) }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.toSet()
            .forEach { url ->
                val c = relays[url]
                if (c != null) c.publish(payload) else scope.launch { publishTransient(url, payload) }
            }
    }

    /** 未接続の write 専用リレーへ一時接続で1イベントを配信する（購読なし・送信後に閉じる）。 */
    private suspend fun publishTransient(url: String, payload: String) {
        val c = RelayClient(url, scope)
        c.start()
        c.publish(payload)  // outgoing は BUFFERED。接続確立後にフラッシュされる。
        delay(8_000)         // 送信フレームを流す猶予を取ってから閉じる。
        c.stop()
    }

    /** レコメンド用に使用したハッシュタグを記録（最近順）。 */
    private fun recordHashtags(content: String, ts: Long) {
        hashtagsIn(content).forEach { tag ->
            q.insertHashtagIfAbsent(tag, ts)
            q.touchHashtag(ts, tag)
        }
    }

    /** [#393] 使ったことのあるハッシュタグ（最終使用日つき・新しい順）。整理画面の一覧用。 */
    fun usedHashtagsWithTimeFlow(): Flow<List<UsedHashtag>> =
        q.usedHashtagsWithTime().asFlow().mapToList(Dispatchers.Default)
            .map { rows -> rows.map { UsedHashtag(it.tag, it.last_used) } }

    /** 投稿で使ったハッシュタグ（最近順・タグのみ）。ComposeSheet のサジェスト/チップに使う。[#395] 同じクエリから導出。 */
    fun usedHashtagsFlow(): Flow<List<String>> =
        usedHashtagsWithTimeFlow().map { rows -> rows.map { it.tag } }

    // ---- [#393] ピン留めハッシュタグ（NIP-51 kind:30015 / d=pinned）----
    // 購読・受信ゲート・State+KV は [pinnedRep]（OwnReplaceable）。ここは発行方式（楽観+デバウンス）だけ。

    /** 自分のピン留めハッシュタグ（表示順）。KV キャッシュ → 受信した 30015 で更新。 */
    fun pinnedHashtagsFlow(): StateFlow<List<String>> = pinnedRep.state

    /** 手元の版（一覧 + at）。reconcile とロールバックの単位。 */
    private fun pinnedCache() = PinnedCache(pinnedRep.state.value, pinnedRep.at)

    private fun commitPinned(cache: PinnedCache) = pinnedRep.commit(PinnedHashtags.toTags(cache.tags), cache.at)

    /** デバウンス発行の失敗通知（ComposeSheet がトーストする）。 */
    fun pinnedHashtagsErrors(): SharedFlow<Unit> = pinnedHashtagsErrors

    /**
     * ピン留めを置き換える（チップ長押し用）。正規化・重複除去・上限 [PinnedHashtags.MAX] で切り詰めて
     * 即時に StateFlow/KV へ反映し、30015 の発行は連打対策に 300ms デバウンスする。
     *
     * 楽観更新では created_at も「今」へ進める。これで 300ms 内に届く古い 30015 のエコーが
     * [updatePinnedHashtags] のゲートで弾かれ、編集を上書き・再発行する競合が起きない。
     * 発行は呼び出し時に捕捉した一覧を使う（発行時に State を読み直さない）。
     * 発行に失敗したら（署名不可等）編集前の版へ戻し、[pinnedHashtagsErrors] へ通知する。
     */
    fun setPinnedHashtags(tags: List<String>) {
        val norm = PinnedHashtags.normalizeList(tags)
        if (norm == pinnedRep.state.value) return
        if (pinnedRollback == null) pinnedRollback = pinnedCache()
        commitPinned(PinnedCache(norm, maxOf(currentUnixTime(), pinnedRep.at)))
        pinnedPublishJob?.cancel()
        pinnedPublishJob = scope.launch {
            delay(300)
            val ok = publishPinnedHashtagsNow(norm)
            if (ok) {
                pinnedRollback = null
            } else {
                pinnedRollback?.let { commitPinned(it) }
                pinnedRollback = null
                pinnedHashtagsErrors.tryEmit(Unit)
            }
        }
    }

    /** 整理画面の「保存」。即時に 30015 を発行し、成否を返す（成功時はキャッシュも更新）。 */
    suspend fun savePinnedHashtags(tags: List<String>): Boolean {
        val norm = PinnedHashtags.normalizeList(tags)
        pinnedPublishJob?.cancel()
        pinnedRollback = null
        return publishPinnedHashtagsNow(norm)
    }

    /** 30015 を発行し、成功なら署名時刻でキャッシュを確定する。 */
    private suspend fun publishPinnedHashtagsNow(tags: List<String>): Boolean = runCatching {
        if (!SignerProvider.hasSession()) return@runCatching false
        val signed = publishSigned(
            UnsignedEvent(kind = PinnedHashtags.KIND, content = "", tags = PinnedHashtags.toTags(tags)),
        )
        // 自分の最新版として記録し、購読エコーで古い扱いされないようにする。
        commitPinned(PinnedCache(tags, signed.createdAt))
        pinnedRepublishArmed = false
        true
    }.getOrDefault(false)

    /**
     * 受信した自分の 30015（d=pinned）。新しい版ならキャッシュを置き換え、古い版は無視する。
     * KV 復元直後の初回受信でローカルの方が新しく内容も違えば（未発行のまま終了した等）、
     * ローカルを正として1回だけ再発行する（2回目以降は古いエコーが来ても無視＝再発行ループを防ぐ）。
     * 共通ゲート（[OwnReplaceable.accept]）ではなく [PinnedHashtags.reconcile] を使うのは、この再発行判定のため。
     */
    private fun updatePinnedHashtags(e: NostrEvent) {
        val cache = pinnedCache()
        when (PinnedHashtags.reconcile(cache, e, myPubkey)) {
            is PinnedReconcile.Accept -> {
                pinnedRepublishArmed = false
                // 楽観更新中（未発行）の編集を、同時刻以降の受信で潰さない。
                if (pinnedRollback != null && pinnedPublishJob?.isActive == true) return
                pinnedRep.commit(e.tags, e.createdAt)
            }
            PinnedReconcile.Republish -> {
                if (!pinnedRepublishArmed) return
                pinnedRepublishArmed = false
                val local = cache.tags
                scope.launch { publishPinnedHashtagsNow(local) }
            }
            PinnedReconcile.Ignore -> Unit
        }
    }

    /**
     * 本文から #ハッシュタグ を抽出（小文字化・重複除去・順序保持）。
     * Unicode 対応（日本語タグも可）：'#' の後、letter/digit/'_' が続く範囲を1タグとする。
     */
    private fun hashtagsIn(content: String): List<String> {
        val out = LinkedHashSet<String>()
        var i = 0
        while (i < content.length) {
            if (content[i] == '#') {
                val start = i + 1
                var j = start
                while (j < content.length && (content[j].isLetterOrDigit() || content[j] == '_')) j++
                if (j > start) out.add(content.substring(start, j).lowercase())
                i = j
            } else i++
        }
        return out.toList()
    }

    /**
     * 本文中の `:shortcode:` を自分の既知カスタム絵文字と照合し、NIP-30 `["emoji", code, url]` を返す。
     * 未知の shortcode は無視（画像 URL が無いとタグにできないため）。投稿/返信/引用に付与する。
     */
    private fun emojiTagsIn(content: String): List<List<String>> {
        val codes = Regex(""":([A-Za-z0-9_+-]+):""").findAll(content).map { it.groupValues[1] }.toSet()
        if (codes.isEmpty()) return emptyList()
        val known = q.allCustomEmojis().executeAsList().associate { it.shortcode to it.image_url }
        return codes.mapNotNull { code -> known[code]?.let { url -> listOf("emoji", code, url) } }
    }

    /** [#109][#350] 本文中の `nostr:npub/nprofile` メンションを hex pubkey へ復号（重複除去・復号失敗は無視）。 */
    private fun mentionPubkeysIn(content: String): List<String> = Nip27.mentionPubkeys(content)

    // ---- kind:0 バッチ解決 ----
    private val authorRequests = Channel<String>(Channel.UNLIMITED)

    private fun requestProfile(pubkey: String) {
        authorRequests.trySend(pubkey)
    }

    /**
     * kind:0 を**インデクサ系リレー**からも確実に取得する（DM相手のアイコン/名前が接続中リレーに
     * 無い場合の取りこぼし対策）。一時接続で kind:0/10002 を要求し、通常の profile 解決と統合する。
     */
    private fun requestProfileFromIndexers(pubkeys: List<String>) {
        val targets = pubkeys.filter { it.isNotBlank() }.distinct()
        if (targets.isEmpty()) return
        scope.launch(relayDispatcher) {
            INDEXER_RELAYS.forEach { url ->
                val u = normalizeRelayUrl(url)
                if (!relays.containsKey(u) && hintRelays.size < HINT_RELAY_CAP && hintRelays.add(u)) ensureRelay(u)
            }
        }
        // kind:0（プロフィール）と kind:10002（NIP-65 リレーリスト）をインデクサ集合へ要求。
        val subId = "idx_profiles_${targets.first().take(6)}"
        subscribeTargeted(subId, INDEXER_RELAYS.toSet(),
            Filter(kinds = listOf(0, 10002), authors = targets, limit = targets.size * 2))
        // [#50] 用が済んだらインデクサへの一時接続を閉じる（リストに無いリレーの常駐を防ぐ）。
        scheduleTransientCleanup(subId)
    }

    /** 本文中の `nostr:npub/nprofile`（接頭辞任意）を hex に復号し、表示名解決のため kind:0 を要求する。 */
    private fun requestMentionedProfiles(content: String) {
        Nip27.mentionPubkeys(content).forEach { requestProfile(it) }
    }

    // ---- [M10] イベント id バッチ解決（返信先の親ノートなど未キャッシュ分を取得） ----
    private val eventRequests = Channel<String>(Channel.UNLIMITED)

    /**
     * 引用/返信の解決のために一時接続したリレーヒント集合（NIP-19/NIP-10 の relay ヒント）。
     * 重複接続と接続数の暴発を防ぐため上限つき。ステータス表示からは除外する（設定リレーのみ表示）。
     */
    private val hintRelays = mutableSetOf<String>()

    /**
     * [#124] naddr（kind+著者+dタグ）を event id へ解決する。
     * DB に既存ならそれを返し、無ければ接続中リレー + リレーヒントへ #d 付き REQ を投げて
     * 取り込まれるのを最大 [timeoutMs] 待つ。見つからなければ null。
     */
    // [#124] resolveAddress 実行中の kind。addressable(3xxxx) は基本 whitelist 外なので、
    // 解決の間だけ ingest が event テーブルへ保存できるようにする。
    private val addressKindsWanted = mutableSetOf<Int>()

    suspend fun resolveAddress(
        kind: Int,
        author: String,
        dTag: String,
        hints: List<String> = emptyList(),
        timeoutMs: Long = 6000,
    ): String? {
        fun dbLookup(): String? = q.eventsByKindAuthor(kind.toLong(), author).executeAsList()
            .firstOrNull { row ->
                runCatching { parseTags(row.tags_json).any { it.size >= 2 && it[0] == "d" && it[1] == dTag } }
                    .getOrDefault(false)
            }?.id
        dbLookup()?.let { return it }

        // ヒントリレーへ一時接続（requestEvent と同じ規則・接続数上限つき）。
        if (hints.isNotEmpty()) {
            withContext(relayDispatcher) {
                for (raw in hints) {
                    val url = normalizeRelayUrl(raw)
                    if (!url.startsWith("wss://") && !url.startsWith("ws://")) continue
                    if (relays.containsKey(url)) continue
                    if (hintRelays.size >= HINT_RELAY_CAP) break
                    if (hintRelays.add(url)) ensureRelay(url)
                }
            }
        }
        val subId = "addr_${kind}_${author.take(8)}_${dTag.hashCode()}"
        addressKindsWanted.add(kind)
        subscribeAll(subId, Filter(kinds = listOf(kind), authors = listOf(author), dTags = listOf(dTag), limit = 1))
        var found: String? = null
        var waited = 0L
        while (found == null && waited < timeoutMs) {
            delay(300); waited += 300
            found = dbLookup()
        }
        unsubscribeAll(subId)
        addressKindsWanted.remove(kind)
        return found
    }

    /**
     * イベント id の取得を要求する。[hints] があれば、そのリレー（未接続なら上限内で一時接続）
     * にも REQ が届くようにする。接続済み/ヒント無しなら従来どおり接続中リレーへ問い合わせる。
     * [#101] nostr:nevent1… ディープリンク（リレーヒント付き）からも呼ぶため公開。
     */
    fun requestEvent(id: String, hints: List<String> = emptyList()) {
        if (hints.isNotEmpty()) {
            scope.launch(relayDispatcher) {
                for (raw in hints) {
                    val url = normalizeRelayUrl(raw)
                    if (!url.startsWith("wss://") && !url.startsWith("ws://")) continue
                    if (relays.containsKey(url)) continue                 // 既接続なら不要
                    if (hintRelays.size >= HINT_RELAY_CAP) break          // 接続数の暴発を防ぐ
                    if (hintRelays.add(url)) ensureRelay(url)
                }
            }
        }
        eventRequests.trySend(id)
    }

    private var eventReqSeq = 0

    private suspend fun eventBatchLoop() {
        val requested = mutableSetOf<String>()
        val pending = mutableSetOf<String>()
        while (true) {
            val first = eventRequests.receive()
            if (first !in requested) pending.add(first)
            withTimeoutOrNull(400) {
                while (true) {
                    val next = eventRequests.receive()
                    if (next !in requested) pending.add(next)
                }
            }
            if (pending.isEmpty()) continue
            val batch = pending.toList()
            requested.addAll(pending)
            pending.clear()
            // 「今回の新規 id だけ」を一意の subId で取得する。
            // 累積 id を1つの sub に積み続けるとフィルタが肥大化し、リレーの ids 上限
            // （strfry/Damus は ~1000）を超えた時点で REQ ごと拒否され、以降の id 解決が
            // 全滅する（引用元/返信元が一切展開されなくなる）。バッチ毎に新しい sub にする。
            batch.chunked(500).forEach { chunk ->
                val subId = "events-${eventReqSeq++}"
                subscribeAll(subId, Filter(ids = chunk, limit = chunk.size))
                // 蓄積イベントは EOSE 後すぐ届く。一定時間で CLOSE して sub を溜めない。
                scope.launch { delay(10_000); unsubscribeAll(subId) }
            }
        }
    }

    private var profileReqSeq = 0

    private suspend fun profileBatchLoop() {
        val requested = mutableSetOf<String>()
        val pending = mutableSetOf<String>()
        while (true) {
            val first = authorRequests.receive()
            if (first !in requested) pending.add(first)
            withTimeoutOrNull(400) {
                while (true) {
                    val next = authorRequests.receive()
                    if (next !in requested) pending.add(next)
                }
            }
            if (pending.isEmpty()) continue
            val batch = pending.toList()
            requested.addAll(pending)
            pending.clear()
            // 「今回の新規 pubkey だけ」を一意の subId で取得する（id 解決ループと同じ理由）。
            // 累積 authors を1つの "profiles" sub に積み続けるとフィルタが肥大化し、リレーの
            // フィルタ要素上限（strfry 等 ~1000）を超えた時点で REQ ごと拒否され、以降の
            // kind:0 解決が全滅する＝プロフィールが二度と更新されなくなる（実報告 #244）。
            batch.chunked(500).forEach { chunk ->
                val subId = "profiles-${profileReqSeq++}"
                subscribeAll(subId, Filter(kinds = listOf(0), authors = chunk, limit = chunk.size))
                // 蓄積イベント（kind:0）は EOSE 後すぐ届く。一定時間で CLOSE して sub を溜めない。
                scope.launch { delay(10_000); unsubscribeAll(subId) }
            }
        }
    }

    // 受信イベントの取り込みキュー。ソケット読取スレッドを塞がないよう trySend で流し込み、
    // [ingestLoop] がまとめて署名検証＋1トランザクション書き込みする。
    private val ingestChannel = Channel<NostrEvent>(Channel.UNLIMITED)

    // ---- [NIP-42] AUTH ----
    /** AUTH 応答ポリシー（既定=自分/DMリレーのみ）。KV 永続。 */
    private val authPolicyState = MutableStateFlow(AuthPolicy.DM_AND_MINE)
    fun authPolicyFlow(): StateFlow<AuthPolicy> = authPolicyState
    private fun loadAuthPolicy() {
        authPolicyState.value = when (q.getSetting(AUTH_POLICY).executeAsOneOrNull()) {
            "off" -> AuthPolicy.OFF; "always" -> AuthPolicy.ALWAYS; else -> AuthPolicy.DM_AND_MINE
        }
    }
    fun setAuthPolicy(p: AuthPolicy) {
        authPolicyState.value = p
        putSettingAsync(AUTH_POLICY, when (p) { AuthPolicy.OFF -> "off"; AuthPolicy.ALWAYS -> "always"; AuthPolicy.DM_AND_MINE -> "dm" })
    }

    private val authChallengeByRelay = mutableMapOf<String, String>()  // url → 応答済みチャレンジ（重複応答の抑止）
    /** ポリシー判定: この URL の AUTH 要求に応答するか。 */
    private fun shouldAuth(url: String): Boolean = when (authPolicyState.value) {
        AuthPolicy.OFF -> false
        AuthPolicy.ALWAYS -> true
        AuthPolicy.DM_AND_MINE -> {
            val u = normalizeRelayUrl(url)
            val mine = q.allRelays().executeAsList().map { normalizeRelayUrl(it.url) }.toSet()
            val dm = myPubkey?.let { dmRelaysByAuthor.value[it] }?.map { normalizeRelayUrl(it) }?.toSet() ?: emptySet()
            u in mine || u in dm
        }
    }

    /**
     * [NIP-42] リレーの AUTH チャレンジに kind:22242 で応答し、成立後に購読を張り直す。
     * ポリシー該当リレーのみ。自分の pubkey をそのリレーに証明するため、既定は自分/DMリレー限定。
     */
    private suspend fun handleAuthChallenge(client: RelayClient, challenge: String) {
        if (challenge.isBlank() || !shouldAuth(client.url)) return
        // 同じチャレンジには一度だけ応答（relayDispatcher 直列化で dedup がアトミック）。
        val key = normalizeRelayUrl(client.url)
        if (authChallengeByRelay[key] == challenge) return
        authChallengeByRelay[key] = challenge
        val signed = runCatching {
            SignerProvider.current().sign(
                UnsignedEvent(
                    kind = 22242, content = "",
                    tags = listOf(listOf("relay", client.url), listOf("challenge", challenge)),
                )
            )
        }.getOrNull() ?: return
        client.publish(RelayProtocol.auth(signed))
        delay(300)  // AUTH の OK を待ってから購読(1059 等の制限イベント)を取り直す
        client.resendSubscriptions()
    }

    private fun onMessage(msg: RelayMessage, client: RelayClient) {
        when (msg) {
            is RelayMessage.Event -> ingestChannel.trySend(msg.event)
            // [NIP-42] AUTH 応答は relayDispatcher(直列)で処理し、チャレンジ重複応答を dedup する。
            is RelayMessage.Auth -> scope.launch(relayDispatcher) { handleAuthChallenge(client, msg.challenge) }
            // [#17] EOSE = 蓄積イベント送信完了。どこか1リレーから来たらそのカラムを「読込済み」に。
            is RelayMessage.Eose -> columnLoadedState.value = columnLoadedState.value + msg.subscriptionId
            else -> {}
        }
    }

    // [#17] カラム(サブスク)別の「初期読込完了(EOSE受信済み)」集合。空表示とロード表示の判別に使う。
    private val columnLoadedState = MutableStateFlow<Set<String>>(emptySet())
    fun columnLoadedFlow(): StateFlow<Set<String>> = columnLoadedState

    /**
     * 取り込みループ。短時間到着分をまとめて（最大 [INGEST_BATCH]）、
     *  1. 署名検証（ソケット読取パス外で・重い JNI をここに集約）
     *  2. **1トランザクション**で DB 書き込み（commit/クエリ通知の回数を激減）
     * を行う。これで「1件ずつ autocommit → fsync」による TL 構築の遅延を解消する。
     */
    private suspend fun ingestLoop() {
        val batch = ArrayList<NostrEvent>(INGEST_BATCH)
        val seen = HashSet<String>()
        while (true) {
            batch.clear(); seen.clear()
            val first = ingestChannel.receive()
            if (seen.add(first.id)) batch.add(first)
            // 連続到着分を短い窓でまとめる。
            withTimeoutOrNull(80) {
                while (batch.size < INGEST_BATCH) {
                    val e = ingestChannel.receive()
                    if (seen.add(e.id)) batch.add(e)
                }
            }
            withContext(Dispatchers.Default) {
                val valid = batch.filter { EventCrypto.verify(it) }   // 重い署名検証は Default で
                if (valid.isNotEmpty()) q.transaction { valid.forEach { runCatching { ingest(it) } } }
            }
        }
    }

    /** 1イベントの取り込み（署名検証は [ingestLoop] で済ませ、ここは DB 書き込み＋副作用のみ）。 */
    private fun ingest(e: NostrEvent) {
        // [#319] 削除済みは取り込まない。リレーは削除を尊重しないことがあり、消したはずのものが
        // 再取得で戻ってくる。個々の insert 箇所ではなくここ1箇所で弾く。
        if (e.kind != 5 && q.isEventDeleted(e.id).executeAsOne() > 0L) return
        // addressable は版が変わると id も変わるので、座標でも見る。削除リクエストより
        // 新しい版は通す（消したあとに同じ名前で公開し直したものまで消さないため）。
        if (e.kind in 30_000..39_999) {
            val d = e.tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: ""
            val at = q.addrDeletedAt("${e.kind}:${e.pubkey}:$d").executeAsOneOrNull()
            if (at != null && e.createdAt <= at) return
        }
        when (e.kind) {
            5 -> ingestDeletion(e)   // [#319] NIP-09 削除リクエスト
            // [#380] 1111=NIP-22 コメント。kind:1 と同じ扱いで保存する（タグ索引に E/A も入る）。
            1, 1111 -> {
                q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, e.content, tagsToJson(e.tags), e.sig)
                indexTags(e)
                requestProfile(e.pubkey)
            }
            7 -> {
                // [M8-react] NIP-25 リアクション。content を正規化("+"/空→❤️)して保存し e タグを索引化。
                val content = when (e.content) { "+", "" -> "❤️"; else -> e.content }
                q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, content, tagsToJson(e.tags), e.sig)
                indexTags(e)
                // [M16] 自分のリアクションは宛先ノートも TL に出すため、対象イベントの取得を促す。
                if (e.pubkey == myPubkey) e.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)?.let { requestEvent(it) }
            }
            // [M8-repost] NIP-18 リポスト(kind:6) / 汎用リポスト(kind:16)。
            //   本体を保存し q/e を索引、リポスト主の profile を要求。content に元イベント JSON が
            //   埋め込まれていれば元も保存して eventById で解決可能にする（無ければ e タグの id を参照）。
            6, 16 -> {
                q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, e.content, tagsToJson(e.tags), e.sig)
                indexTags(e)
                requestProfile(e.pubkey)
                parseEmbeddedEvent(e.content)?.let { orig ->
                    q.insertEvent(orig.id, orig.pubkey, orig.kind.toLong(), orig.createdAt, orig.content, tagsToJson(orig.tags), orig.sig)
                    indexTags(orig)
                    requestProfile(orig.pubkey)
                }
            }
            42 -> {
                // NIP-28 チャンネルメッセージ。保存して #e を索引し、著者 profile を要求。
                // ルート #e（=チャンネルid）で一覧の最終活動時刻を前進させる。
                q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, e.content, tagsToJson(e.tags), e.sig)
                indexTags(e)
                requestProfile(e.pubkey)
                // 本文中の nostr:npub… メンションの表示名も引けるよう kind:0 を要求。
                requestMentionedProfiles(e.content)
                rootOf(e.tags)?.let { q.touchChannelActivity(e.createdAt, it, e.createdAt) }
            }
            0 -> upsertProfile(e)
            3 -> { updateFollows(e); captureContacts(e) }  // 自分のフォロー更新＋全 pubkey の集計[#96/#97/#98]
            10002 -> { captureNip65(e); if (relayListRep.accept(e, myPubkey)) applyRelayList(relayListRep.state.value) }
            10000 -> updateMuteList(e)    // NIP-51 ミュートリスト
            10001 -> updatePinnedList(e)  // NIP-51 固定投稿（プロフィール上部）
            10003 -> updateBookmarkList(e) // NIP-51 ブックマーク
            4 -> ingestLegacyDm(e)        // NIP-04 旧型DM（kind:4）を復号して kind:14 に統合保存
            1059 -> ingestGiftWrap(e)     // NIP-17 DM（gift wrap を復号して kind:14 保存）
            9735 -> ingestZapReceipt(e)   // NIP-57 Zap 受領（#e 集計・受信通知に使う）
            10050 -> updateDmRelayList(e) // NIP-17 DM リレーリスト
            10030 -> updateEmojiList(e)   // NIP-51 自分の絵文字リスト
            30030 -> updateEmojiSet(e)    // NIP-51 絵文字セット（10030 の a タグ参照先）
            30078 -> {
                captureSyncEvent(e) // [#374] NIP-78 アプリデータ（設定/カラム構成の手動同期用の控え）
                // [#288] 配布テーマ（t=nostrism-theme）は **他人の分も** event テーブルへ保存する。
                // themeEntriesFlow は event テーブルを読むので、保存しないとストア一覧に出ない。
                // 自分のテーマだけ出ていたのは publishSigned がローカル保存していたから。
                if (e.tags.any { it.size >= 2 && it[0] == "t" && it[1] == ThemeEntry.DISCOVERY_TAG }) {
                    // 本体とタグ索引は原子的に（索引前を読むと t タグ無しに見え、一覧から漏れる #78）。
                    q.transaction {
                        q.insertEvent(
                            e.id, e.pubkey, e.kind.toLong(), e.createdAt, e.content, tagsToJson(e.tags), e.sig,
                        )
                        indexTags(e)
                    }
                    requestProfile(e.pubkey)   // 一覧に作者名を出すため
                }
            }
            // [#124] NIP-23 長文記事。nevent 参照から記事ビューワーで開けるよう本体を保存する。
            // [#389] NIP-51 セット（30000 フォローセット / 30003 ブックマークセット）も同じ経路。
            // p タグ数百件のセットで event_tag が肥大しないよう、索引は kind 別に絞る（indexTags）。
            // [#393] 30015 Interest set（自分の d=pinned はピン留めハッシュタグとして取り込む）。
            30000, 30003, 30015, 30023 -> {
                q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, e.content, tagsToJson(e.tags), e.sig)
                indexTags(e)
                requestProfile(e.pubkey)
                if (e.kind == PinnedHashtags.KIND) updatePinnedHashtags(e)
            }
            // [#124] naddr 解決中の addressable kind（3xxxx）だけ一時的に保存する。
            else -> if (e.kind in 30000..39999 && e.kind in addressKindsWanted) {
                q.insertEvent(e.id, e.pubkey, e.kind.toLong(), e.createdAt, e.content, tagsToJson(e.tags), e.sig)
                indexTags(e)
                requestProfile(e.pubkey)
            }
        }
    }

    // ---- NIP-51 ミュートリスト（kind:10000）----

    private var muteListAt = 0L
    private val muteFlow = MutableStateFlow<MuteList?>(null)
    // 再発行（編集）時に失わないよう、p/word/t/e 以外の未知タグを公開/非公開それぞれ保持する。
    private var muteOtherPublic: List<List<String>> = emptyList()
    private var muteOtherPrivate: List<List<String>> = emptyList()

    /** 解析済みミュートリスト（公開 + 復号済み非公開）。未取得は null。 */
    fun muteListFlow(): StateFlow<MuteList?> = muteFlow

    // カラム別「ミュートを表示（フィルタ解除）」の集合。KV(app_setting)に永続。
    private val revealMutedFlow = MutableStateFlow<Set<String>>(emptySet())
    fun revealMutedColumns(): StateFlow<Set<String>> = revealMutedFlow

    /** カラムでミュートを表示するか（目アイコン）を切り替え、KV に保存する。 */
    fun setColumnRevealMuted(columnId: String, reveal: Boolean) {
        revealMutedFlow.value = if (reveal) revealMutedFlow.value + columnId else revealMutedFlow.value - columnId
        putSettingAsync(REVEAL_MUTED_PREFIX + columnId, if (reveal) "1" else "0")
    }

    // フォロー中カラムで「自分への反応（kind:7/フォロー外リポスト）」を隠すカラム集合。KV 永続。
    private val hideSelfNoticesFlow = MutableStateFlow<Set<String>>(emptySet())
    fun hideSelfNoticesColumns(): StateFlow<Set<String>> = hideSelfNoticesFlow

    fun setColumnHideSelfNotices(columnId: String, hide: Boolean) {
        hideSelfNoticesFlow.value = if (hide) hideSelfNoticesFlow.value + columnId else hideSelfNoticesFlow.value - columnId
        putSettingAsync(HIDE_SELF_NOTICES_PREFIX + columnId, if (hide) "1" else "0")
    }

    // [M18] フォロー中カラムで「非表示にする通知系カテゴリ」をカラム別に持つ。KV 永続（カンマ区切り）。
    private val hiddenCategoriesFlow = MutableStateFlow<Map<String, Set<FeedNoticeCategory>>>(emptyMap())
    fun columnHiddenCategoriesFlow(): StateFlow<Map<String, Set<FeedNoticeCategory>>> = hiddenCategoriesFlow

    fun setColumnCategoryHidden(columnId: String, category: FeedNoticeCategory, hidden: Boolean) {
        val next = hiddenCategoriesFlow.value[columnId].orEmpty().let { if (hidden) it + category else it - category }
        hiddenCategoriesFlow.value = hiddenCategoriesFlow.value.toMutableMap().apply {
            if (next.isEmpty()) remove(columnId) else put(columnId, next)
        }
        putSettingAsync(FEED_CAT_HIDDEN_PREFIX + columnId, next.joinToString(",") { it.name })
    }

    // [#10] カラム幅（"S"/"M"/"L"）をカラム別に持つ。KV 永続。未設定は既定(M)。
    private val columnWidthsState = MutableStateFlow<Map<String, String>>(emptyMap())
    fun columnWidthsFlow(): StateFlow<Map<String, String>> = columnWidthsState
    fun setColumnWidth(columnId: String, size: String) {
        columnWidthsState.value = columnWidthsState.value + (columnId to size)
        putSettingAsync(COL_WIDTH_PREFIX + columnId, size)
    }
    private fun loadColumnWidths() {
        columnWidthsState.value = q.settingsByPrefix(COL_WIDTH_PREFIX).executeAsList()
            .associate { it.key.removePrefix(COL_WIDTH_PREFIX) to it.value_ }
    }

    // [#27] 検索履歴（新しい順・上限30・KV 永続）。検索タブの履歴一覧に使う。
    private val searchHistoryState = MutableStateFlow<List<String>>(emptyList())
    fun searchHistoryFlow(): StateFlow<List<String>> = searchHistoryState
    fun addSearchHistory(term: String) {
        val t = term.trim()
        if (t.isEmpty()) return
        val next = (listOf(t) + searchHistoryState.value.filter { it != t }).take(30)
        searchHistoryState.value = next
        putSettingAsync(SEARCH_HISTORY, next.joinToString("\n"))
    }
    fun removeSearchHistory(term: String) {
        val next = searchHistoryState.value.filter { it != term }
        searchHistoryState.value = next
        putSettingAsync(SEARCH_HISTORY, next.joinToString("\n"))
    }
    fun clearSearchHistory() {
        searchHistoryState.value = emptyList()
        putSettingAsync(SEARCH_HISTORY, "")
    }
    private fun loadSearchHistory() {
        searchHistoryState.value = q.getSetting(SEARCH_HISTORY).executeAsOneOrNull()
            ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }

    private fun loadHiddenCategories() {
        hiddenCategoriesFlow.value = q.settingsByPrefix(FEED_CAT_HIDDEN_PREFIX).executeAsList()
            .associate { row ->
                row.key.removePrefix(FEED_CAT_HIDDEN_PREFIX) to
                    row.value_.split(",").mapNotNull { runCatching { FeedNoticeCategory.valueOf(it) }.getOrNull() }.toSet()
            }.filterValues { it.isNotEmpty() }
    }

    /**
     * 自分の kind:10000 を購読する（設定 > ミュートの表示中）。
     * 接続中の全リレーへ REQ を張り、最新の1件を [updateMuteList] で解析する。
     */
    fun subscribeMuteList(columnId: String = "mute_list") {
        if (!openColumns.add(columnId)) return
        // 購読ジョブの管理は notifJobs を流用（unsubscribeColumn で cancel される）。
        notifJobs[columnId] = scope.launch {
            myPubkeyFlow.collect { me ->
                if (me != null) {
                    subscribeAll(columnId, Filter(kinds = listOf(10000), authors = listOf(me), limit = 1))
                }
            }
        }
    }

    /**
     * kind:10000 を解析して [muteFlow] へ。公開タグ（p/word/t/e）に加え、
     * 非公開分は content を復号して統合する:
     *  - "?iv=" を含む → NIP-04（レガシー）。自分自身との ECDH で復号。
     *  - 含まない     → NIP-44。自分自身の会話鍵で復号。
     * 復号に失敗した場合のみ nip44Locked=true（編集は不可＝上書きで失うのを防ぐ）。
     * 解析後、ミュート対象ユーザーの kind:0 をバッチ REQ（接続中の全リレー）で解決する。
     */
    private fun updateMuteList(e: NostrEvent) {
        if (e.pubkey != myPubkey) return
        if (e.createdAt <= muteListAt) return
        muteListAt = e.createdAt
        scope.launch {
            var locked = false
            val priv: List<List<String>> = when {
                e.content.isBlank() -> emptyList()
                else -> runCatching {
                    val signer = SignerProvider.current()
                    val json = if ("?iv=" in e.content) signer.nip04Decrypt(e.pubkey, e.content)
                    else signer.nip44Decrypt(e.pubkey, e.content)
                    parseTags(json)
                }.getOrElse { locked = true; emptyList() }
            }
            // 公開/非公開を (category,value) でマージして1件に統合する。
            val merged = LinkedHashMap<Pair<MuteCategory, String>, MuteEntry>()
            fun ingestTags(tags: List<List<String>>, private: Boolean, other: MutableList<List<String>>) {
                tags.forEach { t ->
                    val cat = if (t.size >= 2) MuteCategory.fromTag(t[0]) else null
                    if (cat == null) { other.add(t); return@forEach }
                    val key = cat to t[1]
                    val cur = merged[key]
                    merged[key] = MuteEntry(
                        category = cat, value = t[1],
                        isPublic = (cur?.isPublic ?: false) || !private,
                        isPrivate = (cur?.isPrivate ?: false) || private,
                    )
                }
            }
            val otherPub = mutableListOf<List<String>>()
            val otherPriv = mutableListOf<List<String>>()
            ingestTags(e.tags, private = false, other = otherPub)
            ingestTags(priv, private = true, other = otherPriv)
            muteOtherPublic = otherPub
            muteOtherPrivate = otherPriv
            muteFlow.value = MuteList(entries = merged.values.toList(), nip44Locked = locked, updatedAt = e.createdAt)
            merged.values.filter { it.category == MuteCategory.USER }.forEach { requestProfile(it.value) }
        }
    }

    /**
     * ミュートリストを再発行する（NIP-51 編集）。[entries] のうち公開分は tags、非公開分は
     * NIP-44 で暗号化して content に載せる。両フラグ false の項目は含めない（＝解除）。
     * 未知タグ（[muteOtherPublic]/[muteOtherPrivate]）は失わないよう引き継ぐ。
     * replaceable なので最新の created_at で上書きされる。
     */
    suspend fun publishMuteList(entries: List<MuteEntry>): Boolean = runCatching {
        val me = myPubkey ?: SignerProvider.current().publicKeyHex().also { myPubkey = it; myPubkeyFlow.value = it }
        val publicTags = entries.filter { it.isPublic }.map { listOf(it.category.tag, it.value) } + muteOtherPublic
        val privateTags = entries.filter { it.isPrivate }.map { listOf(it.category.tag, it.value) } + muteOtherPrivate
        val content = if (privateTags.isEmpty()) ""
        else SignerProvider.current().nip44Encrypt(me, tagsToJson(privateTags))
        val signed = publishSigned(UnsignedEvent(kind = 10000, content = content, tags = publicTags))
        // 楽観反映（購読エコーの取りこぼしに備える）。
        muteListAt = signed.createdAt
        muteFlow.value = MuteList(entries = entries.filter { it.isPublic || it.isPrivate }, updatedAt = signed.createdAt)
        true
    }.getOrElse { false }

    /**
     * 指定ユーザーを**非公開**でミュートする（NIP-51 の private "p"）。
     * 現在のミュートリストに `p:pubkey` を isPrivate=true でマージして再発行する。
     * 既に公開ミュート済みならその公開フラグは維持したまま非公開も立てる。
     * 復号できない非公開項目がある（[MuteList.nip44Locked]）と再発行で失う恐れがあるため中止する。
     * 戻り値: 発行できたか（既にミュート済み/ロック中/失敗は false）。
     */
    suspend fun muteUserPrivate(pubkey: String): Boolean {
        val current = muteFlow.value
        if (current?.nip44Locked == true) return false          // 編集不可（NIP-44 ロック中）
        val entries = current?.entries ?: emptyList()
        val existing = entries.find { it.category == MuteCategory.USER && it.value == pubkey }
        if (existing?.isPrivate == true) return false            // 既に非公開ミュート済み
        val merged = if (existing != null) {
            entries.map { if (it === existing) it.copy(isPrivate = true) else it }
        } else {
            entries + MuteEntry(MuteCategory.USER, pubkey, isPublic = false, isPrivate = true)
        }
        return publishMuteList(merged)
    }

    /** [#94/#95] 自分がミュート中のユーザー pubkey 集合（公開/非公開の別を問わない）。 */
    fun mutedUsersFlow(): Flow<Set<String>> =
        muteFlow.map { m -> m?.entries?.filter { it.category == MuteCategory.USER }?.map { it.value }?.toSet() ?: emptySet() }

    /**
     * [#94/#95] 指定ユーザーのミュートを解除する（公開/非公開の両方の USER エントリを除いて再発行）。
     * 戻り値: 発行できたか（未ミュート/NIP-44 ロック中/失敗は false）。
     */
    suspend fun unmuteUser(pubkey: String): Boolean {
        val current = muteFlow.value ?: return false
        if (current.nip44Locked) return false                    // 編集不可（NIP-44 ロック中）
        val filtered = current.entries.filterNot { it.category == MuteCategory.USER && it.value == pubkey }
        if (filtered.size == current.entries.size) return false  // ミュートしていない
        return publishMuteList(filtered)
    }

    /** [#4] 自分のミュートワード一覧（NIP-51 kind:10000 の private "word"）。 */
    fun muteWordsFlow(): Flow<List<String>> =
        muteFlow.map { m -> m?.entries?.filter { it.category == MuteCategory.WORD }?.map { it.value } ?: emptyList() }

    /** ミュートワードを追加（非公開＝NIP-44 で暗号化）。空/重複は false。/.../ で正規表現。 */
    suspend fun addMuteWord(word: String): Boolean {
        val w = word.trim()
        if (w.isEmpty()) return false
        val current = muteFlow.value
        if (current?.nip44Locked == true) return false
        val entries = current?.entries ?: emptyList()
        if (entries.any { it.category == MuteCategory.WORD && it.value.equals(w, ignoreCase = true) }) return false
        return publishMuteList(entries + MuteEntry(MuteCategory.WORD, w, isPublic = false, isPrivate = true))
    }

    /** ミュートワードを削除。 */
    suspend fun removeMuteWord(word: String): Boolean {
        val current = muteFlow.value ?: return false
        if (current.nip44Locked) return false
        return publishMuteList(current.entries.filterNot { it.category == MuteCategory.WORD && it.value == word })
    }

    // ---- NIP-51 固定投稿(kind:10001) / ブックマーク(kind:10003) ----

    /** 自分の編集可能な e-id リスト（公開 e タグ）。非公開/未知タグは content/other で温存し再発行で失わない。 */
    private class EIdList {
        var at = 0L
        var content: String = ""
        var other: List<List<String>> = emptyList()
        val ids = MutableStateFlow<List<String>>(emptyList())  // 追加順を保持
    }
    private val bookmarkList = EIdList()
    private val pinnedList = EIdList()

    /** 自分のブックマーク(kind:10003)の event id（追加順）。 */
    fun bookmarkIdsFlow(): StateFlow<List<String>> = bookmarkList.ids
    /** 自分の固定投稿(kind:10001)の event id（追加順）。 */
    fun pinnedIdsFlow(): StateFlow<List<String>> = pinnedList.ids

    /** 他ユーザーも含む固定投稿リスト（author→ordered event id）。プロフィール表示用。 */
    private val pinsByAuthor = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val pinsAtByAuthor = mutableMapOf<String, Long>()

    private fun updateBookmarkList(e: NostrEvent) {
        if (e.pubkey != myPubkey || e.createdAt <= bookmarkList.at) return
        bookmarkList.at = e.createdAt
        bookmarkList.content = e.content
        val ids = ArrayList<String>(); val other = ArrayList<List<String>>()
        e.tags.forEach { t -> if (t.size >= 2 && t[0] == "e") ids.add(t[1]) else other.add(t) }
        bookmarkList.other = other
        bookmarkList.ids.value = ids.distinct()
        if (ids.isNotEmpty()) subscribeAll("bookmark_items", Filter(ids = ids.distinct(), limit = ids.size))
    }

    private fun updatePinnedList(e: NostrEvent) {
        if ((pinsAtByAuthor[e.pubkey] ?: 0L) >= e.createdAt) return
        pinsAtByAuthor[e.pubkey] = e.createdAt
        val ids = e.tags.filter { it.size >= 2 && it[0] == "e" }.map { it[1] }.distinct()
        pinsByAuthor.value = pinsByAuthor.value + (e.pubkey to ids)
        if (ids.isNotEmpty()) subscribeAll("pinitems_${e.pubkey.take(8)}", Filter(ids = ids, limit = ids.size))
        if (e.pubkey == myPubkey) {   // 自分の分は編集用リストにも反映（other/content を温存）。
            pinnedList.at = e.createdAt
            pinnedList.content = e.content
            pinnedList.other = e.tags.filterNot { it.size >= 2 && it[0] == "e" }
            pinnedList.ids.value = ids
        }
    }

    private suspend fun publishEIdList(target: EIdList, kind: Int, ids: List<String>): Boolean = runCatching {
        val tags = ids.map { listOf("e", it) } + target.other
        val signed = publishSigned(UnsignedEvent(kind = kind, content = target.content, tags = tags))
        target.at = signed.createdAt
        target.ids.value = ids
        true
    }.getOrElse { false }

    /** ブックマークをトグル（NIP-51 kind:10003 の公開 e タグ）。戻り値=操作後にブックマーク済みか。 */
    suspend fun toggleBookmark(eventId: String): Boolean {
        val cur = bookmarkList.ids.value
        val was = eventId in cur
        publishEIdList(bookmarkList, 10003, if (was) cur - eventId else cur + eventId)
        return !was
    }

    /** 固定投稿をトグル（NIP-51 kind:10001、自分のノートのみ）。戻り値=操作後に固定済みか。 */
    suspend fun togglePinned(eventId: String): Boolean {
        val cur = pinnedList.ids.value
        val was = eventId in cur
        publishEIdList(pinnedList, 10001, if (was) cur - eventId else cur + eventId)
        // pinsByAuthor（自分の分）も即時反映。
        myPubkey?.let { pinsByAuthor.value = pinsByAuthor.value + (it to (if (was) cur - eventId else cur + eventId)) }
        return !was
    }

    /** id リスト順に DB から NoteUi を解決する（未取得 id はスキップ）。ブックマーク/固定表示用。 */
    private fun notesByIds(ids: List<String>): Flow<List<NoteUi>> =
        if (ids.isEmpty()) flowOf(emptyList())
        else combine(
            q.eventsByIds(ids).asFlow().mapToList(Dispatchers.Default), profilesFlow, noteMetaFlow,
        ) { rows, profiles, meta ->
            val byPubkey = profiles.associateBy { it.pubkey }
            val byId = rows.associateBy { it.id }
            ids.mapNotNull { id ->
                byId[id]?.let { r -> applyMeta(withQuoteAndReply(toNoteUi(r, byPubkey[r.pubkey]), r, byPubkey), meta) }
            }
        }.flowOn(Dispatchers.Default)

    /** 自分のブックマーク済みノート（追加順の新しい方が上＝逆順表示）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun bookmarkedNotesFlow(): Flow<List<NoteUi>> =
        bookmarkList.ids.flatMapLatest { notesByIds(it.asReversed()) }.flowOn(Dispatchers.Default)

    /** 指定ユーザーの固定投稿を購読し、固定 note を追加順で返す（ProfileColumn 上部用）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun pinnedNotesFor(pubkey: String): Flow<List<NoteUi>> {
        subscribeAll("pins_${pubkey.take(8)}", Filter(kinds = listOf(10001), authors = listOf(pubkey), limit = 1))
        return pinsByAuthor.map { it[pubkey] ?: emptyList() }.distinctUntilChanged()
            .flatMapLatest { notesByIds(it) }.flowOn(Dispatchers.Default)
    }

    /**
     * 自分の kind:10030（NIP-51 絵文字リスト）。直接の `emoji` タグを取り込み、
     * `a`(=30030:pubkey:dtag) 参照のセット作者へ購読を張って kind:30030 を取りに行く。古い版は無視。
     */
    /** [#287] 自分の絵文字リスト（kind:10030 直下の emoji タグのみ。30030 セット由来は含まない）。 */
    fun myEmojiListFlow(): StateFlow<List<CustomEmoji>> = emojiListRep.state

    private fun emojiTagsToList(tags: List<List<String>>): List<CustomEmoji> =
        tags.filter { it.size >= 3 && it[0] == "emoji" && it[1].isNotBlank() && it[2].isNotBlank() }
            .map { CustomEmoji(it[1], it[2]) }

    /**
     * [#287] 絵文字エディタからの再発行。emoji タグを [emojis] で置き換え、
     * それ以外のタグ（30030 参照の a タグ等）はそのまま維持する。
     */
    suspend fun publishEmojiList(emojis: List<CustomEmoji>): Boolean = runCatching {
        val keep = emojiListRep.tags.filter { !(it.size >= 3 && it[0] == "emoji") }
        val tags = keep + emojis.map { listOf("emoji", it.shortcode, it.url) }
        val removed = emojiListRep.state.value.map { it.shortcode }.toSet() - emojis.map { it.shortcode }.toSet()
        val signed = publishSigned(UnsignedEvent(kind = 10030, content = "", tags = tags))
        // 自分の最新版として記録（State/at/KV を同時に確定）し、購読エコーで古い扱いされないようにする。
        emojiListRep.commit(tags, signed.createdAt)
        val now = currentUnixTime()
        emojis.forEach { q.upsertCustomEmoji(it.shortcode, it.url, now) }
        removed.forEach { q.deleteCustomEmoji(it) }
        true
    }.getOrDefault(false)

    private fun updateEmojiList(e: NostrEvent) {
        // [#396] 受信ゲート（自分の・手元の版以上）と State/at/KV の更新は共通処理。
        if (!emojiListRep.accept(e, myPubkey)) return
        importEmojiTags(e)
        e.tags.filter { it.size >= 2 && it[0] == "a" && it[1].startsWith("30030:") }.forEach { t ->
            val author = t[1].split(":").getOrNull(1) ?: return@forEach
            if (author.isNotBlank()) {
                subscribeAll("emojiset_$author", Filter(kinds = listOf(30030), authors = listOf(author)))
            }
        }
    }

    /** kind:30030 絵文字セット。`emoji` タグ(shortcode/url)を取り込む。 */
    private fun updateEmojiSet(e: NostrEvent) = importEmojiTags(e)

    /** NIP-30 `["emoji", shortcode, url]` タグを custom_emoji へ upsert。 */
    private fun importEmojiTags(e: NostrEvent) {
        val now = currentUnixTime()
        e.tags.filter { it.size >= 3 && it[0] == "emoji" && it[1].isNotBlank() && it[2].isNotBlank() }
            .forEach { q.upsertCustomEmoji(it[1], it[2], now) }
    }

    /** 自分の kind:3 から p タグ（フォロー先）を取り出す。古い版は無視（created_at で判定）。 */
    private fun updateFollows(e: NostrEvent) {
        if (e.pubkey != myPubkey) return
        if (e.createdAt < followsAt) return
        followsAt = e.createdAt
        follows.value = e.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
    }

    // ---- [#96/#97/#98] ソーシャルグラフ（任意 pubkey の kind:3 集計） ----

    /** 全 pubkey の kind:3 をメモリに集計（pubkey → 最新の created_at と p タグ）。DB には入れない。 */
    // [#80-OOM] kind:3 は1通で数百〜数千の p タグを持つ巨大イベント。届いた全員分の
    // 全リストを保持すると、フォロワー集計（kind:3 を最大500通引き込む）で 100MB 級に
    // 膨らみ heap(256MB) が枯渇する。全リストは「明示的に要求した対象（閲覧中の
    // プロフィール）」だけ LRU（最大8人）で保持し、フォロワー集計には真偽値しか積まない。
    private val contactsByAuthor = MutableStateFlow<Map<String, Pair<Long, List<String>>>>(emptyMap())
    private val contactsInterest = ArrayDeque<String>()  // 全リスト保持を許可した pubkey（挿入順・最大8）

    /** 進行中のフォロワー集計（target → 発行者 → (createdAt, 対象を含むか)）。フェッチ中のみ登録。 */
    private val followerScans = mutableMapOf<String, MutableMap<String, Pair<Long, Boolean>>>()

    /** kind:3 の取り込み。発行者ごと最新版のみ・保持は要求済み対象と進行中集計に限定。 */
    private fun captureContacts(e: NostrEvent) {
        if (e.pubkey in contactsInterest) {
            val prev = contactsByAuthor.value[e.pubkey]
            if (prev == null || prev.first < e.createdAt) {
                val ps = e.tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }
                contactsByAuthor.value = contactsByAuthor.value + (e.pubkey to (e.createdAt to ps))
            }
        }
        // 進行中のフォロワー集計へは「対象を含むかどうか」だけ記録（リスト本体は保持しない）。
        followerScans.forEach { (target, seen) ->
            val prev = seen[e.pubkey]
            if (prev == null || prev.first < e.createdAt) {
                seen[e.pubkey] = e.createdAt to e.tags.any { it.size >= 2 && it[0] == "p" && it[1] == target }
            }
        }
    }

    /**
     * [#96/#98] 対象ユーザーのフォロー先（kind:3 の p タグ）。
     * 呼び出し時に接続中リレー＋インデクサへ単発 REQ を投げ、届き次第 flow が更新される。
     * 自分は publish の楽観反映も追う既存 [follows] をそのまま返す。
     */
    fun followsOf(pubkey: String): Flow<List<String>> {
        if (pubkey == myPubkey) return follows
        requestContactsOf(pubkey)
        return contactsByAuthor.map { it[pubkey]?.second.orEmpty() }
    }

    /** 対象の kind:3 を単発 REQ し、全リスト保持の許可（LRU 最大8）を与える。 */
    private fun requestContactsOf(pubkey: String) {
        if (pubkey in contactsInterest) return  // 取得済み（更新は通常の受信で追える範囲でよい）
        contactsInterest.addLast(pubkey)
        if (contactsInterest.size > 8) {
            val evicted = contactsInterest.removeFirst()
            contactsByAuthor.value = contactsByAuthor.value - evicted
        }
        val subId = "contacts_of_${pubkey.take(12)}"
        subscribeAll(subId, Filter(kinds = listOf(3), authors = listOf(pubkey), limit = 1))
        subscribeTargeted("${subId}_idx", INDEXER_RELAYS.toSet(), Filter(kinds = listOf(3), authors = listOf(pubkey), limit = 1))
        scope.launch { delay(6000); unsubscribeAll(subId); unsubscribeAll("${subId}_idx") }
    }

    /** ページング付きフォロワー集計の1ページ分。[hasMore]=true なら続きを取れる見込みがある。 */
    data class FollowersPage(val followers: List<String>, val hasMore: Boolean)

    // フォロワー集計の累積（対象1人分のみ保持）と続きカーソル（観測済み kind:3 の最古 created_at - 1）。
    private var followerAccumTarget: String? = null
    private var followerAccum = mutableMapOf<String, Pair<Long, Boolean>>()
    private var followerCursor: Long? = null

    /**
     * [#97] 対象を p タグに含む kind:3 の発行者＝フォロワーを1ページ分収集する
     * （リレーで観測できた範囲のみ・全数ではない）。
     * [#80-OOM 続報] kind:3 は1通で数百〜数千 p タグの巨大イベントで、全接続リレーに
     * limit 500 で投げるとパース洪水だけで heap が枯渇する（プロフィールを開くと OOM）。
     *  - 自動では実行しない（フォロワー一覧を開いた時のみ呼ぶ）
     *  - インデクサリレーだけに投げる（全接続リレーへの重複 REQ をやめる）
     *  - limit [pageSize] のページング（観測した created_at を until カーソルに続きを取る）
     * 同一発行者は最新の kind:3 だけ採用し、対象を含まない発行者は除外（アンフォロー検出）。
     * メモリには「含むか」の真偽値しか積まない（[captureContacts] の followerScans 経由）。
     */
    suspend fun fetchFollowersPage(pubkey: String, reset: Boolean, pageSize: Int = 100): FollowersPage {
        if (reset || followerAccumTarget != pubkey) {
            followerAccumTarget = pubkey
            followerAccum = mutableMapOf()
            followerCursor = null
        }
        val seen = followerAccum
        val before = seen.size
        followerScans[pubkey] = seen
        val subId = "followers_of_${pubkey.take(12)}"
        subscribeTargeted(
            subId, INDEXER_RELAYS.toSet(),
            Filter(kinds = listOf(3), pTags = listOf(pubkey), limit = pageSize, until = followerCursor),
        )
        try {
            delay(2500)
        } finally {
            // 画面離脱等でキャンセルされても REQ と集計テーブルを残さない。
            followerScans.remove(pubkey)
            unsubscribeAll(subId)
        }
        followerCursor = seen.values.minOfOrNull { it.first }?.let { it - 1 }
        return FollowersPage(
            followers = seen.filterValues { it.second }.keys.toList(),
            hasMore = seen.size > before,
        )
    }

    /** [#99] 対象ユーザーの NIP-65 リレー（受信済みキャッシュから最大 [max] 件）。nprofile のリレーヒント用。 */
    fun nip65RelaysOf(pubkey: String, max: Int = 3): List<String> =
        nip65ByAuthor[pubkey]?.second?.take(max).orEmpty()

    /**
     * 自分の kind:10002（NIP-65）から `r` タグを取り出してリレーリストへ。
     * マーカー無し=read+write、"read"=Inbox のみ、"write"=Outbox のみ。
     * DB に 'nip65' として保存し、read(Inbox) のものだけ購読接続する。古い版は無視。
     * write 専用(Outbox)は購読せず、配信時に一時接続する（NIP-65 outbox）。
     */
    // ---- [#relay-recs] リレーのオススメ（フォロー中の NIP-65 集計） ----

    /** フォロー中の kind:10002 をメモリに集計（pubkey → 最新の r タグ URL 群）。DB には入れない。 */
    private val nip65ByAuthor = mutableMapOf<String, Pair<Long, List<String>>>()

    private fun captureNip65(e: NostrEvent) {
        val prev = nip65ByAuthor[e.pubkey]
        if (prev != null && prev.first >= e.createdAt) return
        val urls = e.tags.filter { it.size >= 2 && it[0] == "r" }
            .map { normalizeRelayUrl(it[1]) }
            .filter { it.startsWith("wss://") }
        nip65ByAuthor[e.pubkey] = e.createdAt to urls
        // [#254-profile] write リレー（marker 無し=両用 / "write"）も保持。アウトボックス購読が使う。
        val writeUrls = e.tags.filter { it.size >= 2 && it[0] == "r" && (it.size < 3 || it[2] == "write") }
            .map { normalizeRelayUrl(it[1]) }
            .filter { it.startsWith("wss://") }
        nip65WriteByAuthor[e.pubkey] = writeUrls
        // [#386] プロフィールの「使用リレー」表示用に read/write マーカー付きで保持する
        // （上の2つは購読先の解決用で、マーカーを落としてしまっている）。
        val prefs = nip65PrefsFromTags(e.tags) { normalizeRelayUrl(it) }
        if (prefs.isNotEmpty()) {
            val next = nip65PrefsByAuthor.value.toMutableMap()
            next[e.pubkey] = prefs
            evictAuthors(next, NIP65_PREFS_AUTHOR_CAP)   // 表示中の著者は残す [#388-review]
            nip65PrefsByAuthor.value = next
        }
    }

    /** [#386] 著者 → NIP-65 の `r` タグ（read/write マーカー込み）。メモリのみ・セッション内。 */
    private val nip65PrefsByAuthor = MutableStateFlow<Map<String, List<RelayPref>>>(emptyMap())

    /**
     * [#386] 指定ユーザーの使用リレー（kind:10002）。未受信なら空。
     * 購読はプロフィール画面が張っている kind:10002 の REQ に相乗りする。
     */
    fun nip65PrefsOf(pubkey: String): Flow<List<RelayPref>> =
        nip65PrefsByAuthor.map { it[pubkey].orEmpty() }.distinctUntilChanged()


    /** [#254-profile] 著者 → write リレー（captureNip65 が更新。メモリのみ・セッション内）。 */
    private val nip65WriteByAuthor = mutableMapOf<String, List<String>>()

    // ---- [#385] NIP-51 セット（kind:30000 フォローセット / 30003 ブックマークセット）----

    /**
     * [#385][#389] 指定ユーザーの公開 NIP-51 セット（新しい順）。記事(30023)と同じく
     * event テーブルを読む（版の畳み込みは addressableEventsFlow / parseNip51Sets）。
     * 購読は [subscribeColumn] 側で行う。
     */
    fun listSetsOf(pubkey: String): Flow<List<Nip51Set>> =
        combine(
            addressableEventsFlow(30000, pubkey),
            addressableEventsFlow(30003, pubkey),
        ) { follows, bookmarks -> parseNip51Sets(follows + bookmarks) }
            .flowOn(Dispatchers.Default)

    /** [#385] ブックマークセットの中身表示用（id 順に DB から解決。未取得はスキップ）。 */
    fun notesByIdsFlow(ids: List<String>): Flow<List<NoteUi>> = notesByIds(ids)

    /**
     * 「フォロー中でよく使われているリレー」を返す（url → 使用人数、多い順）。
     * フォロー先の kind:10002 を接続中リレー＋インデクサへ一括要求し、数秒集めて集計する。
     * 常に“いまの”有力候補が出る nostr ネイティブなレコメンド（静的リストは新規垢向けフォールバック）。
     */
    suspend fun fetchRelayRecommendations(): List<Pair<String, Int>> {
        val authors = follows.value.take(300)  // REQ の肥大化を避けて直近300人まで
        if (authors.isEmpty()) return emptyList()
        val subId = "recs_nip65"
        subscribeAll(subId, Filter(kinds = listOf(10002), authors = authors))
        subscribeTargeted("${subId}_idx", INDEXER_RELAYS.toSet(), Filter(kinds = listOf(10002), authors = authors))
        try {
            delay(3500)
        } finally {
            // 画面離脱等で呼び出し元コルーチンがキャンセルされても REQ を残さない
            // （unsubscribeAll は repo スコープへ enqueue するのでキャンセル中でも安全）。
            unsubscribeAll(subId)
            unsubscribeAll("${subId}_idx")
        }
        val followSet = authors.toSet()
        val registered = q.allRelays().executeAsList().map { normalizeRelayUrl(it.url) }.toSet()
        return nip65ByAuthor.filterKeys { it in followSet }
            .values.flatMap { it.second.distinct() }
            .groupingBy { it }.eachCount()
            .filterKeys { it !in registered }
            .entries.sortedByDescending { it.value }
            .take(12)
            .map { it.key to it.value }
    }

    /**
     * [#74] 「フォロー中が DM 受信に使っているリレー」を返す（url → 使用人数、多い順）。
     * フォロー先の kind:10050 を接続中リレー＋インデクサへ一括要求し、数秒集めて集計する。
     * DM リレーには適性（NIP-42 AUTH・gift wrap 保持）が要るため、静的リストではなく
     * 実際に DM 受信に使われているリレーを提示する（ingest の updateDmRelayList が
     * 全 pubkey の 10050 を保持しているのでそれを集計する）。
     */
    suspend fun fetchDmRelayRecommendations(): List<Pair<String, Int>> {
        val authors = follows.value.take(300)  // REQ の肥大化を避けて直近300人まで
        if (authors.isEmpty()) return emptyList()
        val subId = "recs_10050"
        subscribeAll(subId, Filter(kinds = listOf(10050), authors = authors))
        subscribeTargeted("${subId}_idx", INDEXER_RELAYS.toSet(), Filter(kinds = listOf(10050), authors = authors))
        try {
            delay(3500)
        } finally {
            // 画面離脱等でキャンセルされても REQ を残さない（fetchRelayRecommendations と同じ）。
            unsubscribeAll(subId)
            unsubscribeAll("${subId}_idx")
        }
        val followSet = authors.toSet()
        val registered = myPubkey?.let { dmRelaysByAuthor.value[it] }.orEmpty().toSet()
        return dmRelaysByAuthor.value.filterKeys { it in followSet && it != myPubkey }
            .values.flatMap { it.distinct() }
            .groupingBy { it }.eachCount()
            .filterKeys { it !in registered }
            .entries.sortedByDescending { it.value }
            .take(12)
            .map { it.key to it.value }
    }

    /**
     * 受信した自分の kind:10002 を relay 表と接続に反映する（受信ゲートと State は [relayListRep]）。
     * [entries] は受け入れた版の r タグから導出したリレー設定。
     */
    private fun applyRelayList(entries: List<RelayPref>) {
        entries.forEach {
            q.upsertRelay(it.url, if (it.read) 1 else 0, if (it.write) 1 else 0, "nip65")
            if (it.read) ensureRelay(it.url)
        }
        // [#default-purge] 自分の NIP-65 が取れたら、ブートストラップ用 default リレーは用済み。
        // 一覧から削除し接続も閉じる（同じ URL が NIP-65 にあれば upsert で nip65 へ昇格済みなので対象外）。
        // read できるリレーが1つも無いリストの場合だけは、接続手段を失わないよう default を残す。
        if (entries.any { it.read }) {
            val keep = entries.map { it.url }.toSet()
            q.allRelays().executeAsList()
                .filter { it.source == "default" && normalizeRelayUrl(it.url) !in keep }
                .forEach { row ->
                    val u = normalizeRelayUrl(row.url)
                    q.deleteRelay(row.url)
                    scope.launch(relayDispatcher) {
                        relays.remove(u)?.let { it.stop(); refreshRelayConns() }
                    }
                }
        }
    }

    /** #t/#e/#p をタグ索引へ（ハッシュタグ等のカラム検索用）。't' は小文字化。
     *  [#389] 索引するタグ名は kind 別（[indexableTagKeys]）。 */
    private fun indexTags(e: NostrEvent) {
        val keys = indexableTagKeys(e.kind)
        e.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] in keys) {
                val value = if (tag[0] == "t") tag[1].lowercase() else tag[1]
                q.insertTag(e.id, tag[0], value)
            }
        }
    }

    /**
     * 自分の最新 kind:0 の生 content(JSON)。未取得なら null。編集時の未知フィールド温存に使う。
     * イベント表は起動時 purge で消える & 受信 kind:0 は profile 表にしか入らないため、
     * 自分の分だけ KV(MY_PROFILE_JSON)に退避したものを優先で読む（無ければイベント表）。
     */
    fun myProfileContent(): String? {
        val pk = myPubkey ?: return null
        return q.getSetting(MY_PROFILE_JSON).executeAsOneOrNull()?.ifBlank { null }
            ?: q.myProfileContent(pk).executeAsOneOrNull()
    }

    /**
     * [M18-#2] プロフィール(kind:0)を発行。既存 content の**未知フィールドは保持**し、標準キーだけ上書き。
     * 空文字のキーは削除。表示名は `name` に集約し、既存が `display_name`/`displayName` を持つ場合のみ同値で同期。
     * [fields] は "name"/"about"/"picture"/"banner"/"website"/"lud16"/"nip05" のうち編集対象のみ。
     *
     * [#171] 署名（Keychain/外部署名等）や配信の失敗を握って **成否を Boolean で返す**。
     * 以前は例外を投げ、呼び出し側が捕捉せず「保存成功」表示のまま実発行されない不具合があった
     * （iOS で顕在化。署名がキャンセル/失敗しても更新されない）。
     */
    suspend fun publishProfile(fields: Map<String, String>): Boolean {
        return try {
            val pk = myPubkey ?: SignerProvider.current().publicKeyHex().also { myPubkey = it; myPubkeyFlow.value = it }
            // KV優先(purge耐性)で生JSONを読む。イベント表は起動時purgeで消えるため直読みは不可。
            val base = myProfileContent()
                ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            val map = LinkedHashMap<String, JsonElement>()
            base?.forEach { (k, v) -> map[k] = v }  // 未知フィールドを温存
            fields.forEach { (k, v) -> if (v.isBlank()) map.remove(k) else map[k] = JsonPrimitive(v) }
            // 表示名(name)を display_name/displayName にも同期（既存が持っている場合のみ）
            fields["name"]?.let { nm ->
                listOf("display_name", "displayName").forEach { key ->
                    if (map.containsKey(key)) { if (nm.isBlank()) map.remove(key) else map[key] = JsonPrimitive(nm) }
                }
            }
            val content = json.encodeToString(JsonObject.serializer(), JsonObject(map))
            val signed = publishSigned(UnsignedEvent(kind = 0, content = content, tags = emptyList()))
            q.putSetting(MY_PROFILE_JSON, content)  // 次回編集の温存元を更新（purge 耐性のため KV に保持）
            upsertProfile(signed)  // ローカル projection を即更新
            true
        } catch (e: CancellationException) {
            throw e  // コルーチンのキャンセルは握らない
        } catch (e: Throwable) {
            println("Nostrism publishProfile failed: ${e.message}")
            false
        }
    }

    private fun upsertProfile(e: NostrEvent) {
        // NIP-01 kind:0 の content は JSON 文字列（user metadata）。標準フィールドを整理して取り込む。
        val o = runCatching { json.parseToJsonElement(e.content).jsonObject }.getOrNull()
        fun str(vararg keys: String): String? =
            keys.firstNotNullOfOrNull { k -> o?.get(k)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
        // 表示名は display_name(または displayName) を優先、無ければ name。
        val name = str("display_name", "displayName", "name") ?: ""
        val nip05 = str("nip05") ?: ""                        // NIP-05 認証ID(name@domain)
        val picture = str("picture")                          // アバター
        val about = str("about") ?: ""                        // 自己紹介(bio)
        val website = str("website")                          // ウェブサイト
        val lud16 = str("lud16", "lud06")                     // Lightning アドレス(NIP-57)
        val banner = str("banner")                            // ヘッダ画像
        q.insertProfileIfAbsent(e.pubkey, name, nip05, picture, e.createdAt, about, website, lud16, banner)
        q.updateProfileIfNewer(name, nip05, picture, e.createdAt, about, website, lud16, banner, e.pubkey, e.createdAt)
        // 自分の kind:0 は生JSONを KV に退避（編集時の未知フィールド温存。purge で消えないように）。
        if (e.pubkey == myPubkey) q.putSetting(MY_PROFILE_JSON, e.content)
    }

    private fun toNoteUi(row: Event, prof: app.nostrdeck.db.Profile?): NoteUi {
        val name = prof?.name?.takeIf { it.isNotBlank() } ?: row.pubkey.take(10)
        // [#326] extractMedia の null は「表示する本文が残らなかった」。NoteUi.text の null は
        // 「未処理（content を表示せよ）」なので、ここで空文字に落とさないと消費側の
        // フォールバックが剥がしたはずの URL を復活させる（動画のみの投稿で実際に起きた）。
        val (strippedText, images) = extractMedia(row.content)
        val text = strippedText ?: ""
        val tags = parseTags(row.tags_json)
        // NIP-10: kind:1 が #e を持てば返信（プロフィールの「投稿/リプライ」振り分け用）。
        // [#380] kind:1111 NIP-22 コメントは常に返信扱い。
        val isReply = (row.kind.toInt() == 1 && tags.any { it.size >= 2 && it[0] == "e" }) ||
            row.kind.toInt() == Nip22.KIND
        // NIP-30: 本文中の :shortcode: → 画像URL のマップ。
        val emojis = tags.filter { it.size >= 3 && it[0] == "emoji" }.associate { it[1] to it[2] }
        // NIP-36: content-warning タグ（あれば表示前に折りたたむ）。2要素目が理由（任意）。
        val cw = tags.firstOrNull { it.isNotEmpty() && it[0] == "content-warning" }
            ?.let { if (it.size >= 2) it[1] else "" }
        // [#312] NIP-89 client タグ（どのアプリから投稿されたか）。
        // 形は ["client", "<名前>", "<31990:...>"?, "<relay>"?]。名前だけの2要素が大半。
        // 名前が空のものは表示しても意味がないので落とす。極端に長い名前は行を壊すため丸める。
        val client = tags.firstOrNull { it.size >= 2 && it[0] == "client" }
            ?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.length > 24) it.take(24) + "…" else it }
        return NoteUi(
            event = NostrEvent(row.id, row.pubkey, row.kind.toInt(), row.created_at, row.content, emptyList(), row.sig),
            author = Profile(row.pubkey, name, prof?.handle ?: "", prof?.picture_url, lud16 = prof?.lud16),
            text = text, images = images, isReply = isReply, customEmojis = emojis, contentWarning = cw,
            clientName = client,
            // [#140] event.tags は再コンポーズ最適化のため空で持つ（従来仕様）。表示に必要な
            // imeta（dim/blurhash/thumb）だけをここで抽出して NoteUi に載せる。
            imeta = app.nostrdeck.model.imetaInfo(tags),
        )
    }

    // ---- [M8-repost] フォロー中タイムラインのリポスト/引用展開 ----

    /**
     * [M8-repost] フォロー中の1行を表示用 NoteUi に。
     *  - kind:6/16 … 元ノートを表示し repostedBy にリポスト主を設定（元が解決できなければ null=非表示）。
     *  - kind:1    … q タグがあれば quoted に引用元を解決して載せる。
     */
    private fun toFollowingNoteUi(row: Event, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi? {
        return when (row.kind.toInt()) {
            6, 16 -> {
                val tags = parseTags(row.tags_json)
                val eTag = tags.firstOrNull { it.size >= 2 && it[0] == "e" }
                val origId = eTag?.getOrNull(1)
                // 元ノートも通常表示と同じく引用/返信を解決する（俳句bot 等の nevent 引用が
                // リポスト経由だと生リンクのまま残っていた）。
                val origRow = origId?.let { q.eventById(it).executeAsOneOrNull() }
                val original = origRow?.let { withQuoteAndReply(toNoteUi(it, byPubkey[it.pubkey]), it, byPubkey) }
                    ?: parseEmbeddedEvent(row.content)?.let { noteUiFromEvent(it, byPubkey) }
                if (original == null) {
                    // 元ノートが未取得ならリポストを捨てず、リレーヒント(e タグ3要素目)付きで取得を促す。
                    // 届き次第フィードが再解決され、フォロー中の人のリポストが表示される。
                    if (origId != null) requestEvent(origId, eTag?.getOrNull(2)?.let { listOf(it) }.orEmpty())
                    return null
                }
                // [#61] リポストは元投稿のコピーとして別エントリで出す。リポストイベント自身の id を
                // 持たせ、元投稿(repostId=null)と id 衝突しない一意キーにする（元は元の位置に残す）。
                original.copy(
                    repostedBy = profileFor(row.pubkey, byPubkey),
                    repostAt = row.created_at,
                    repostId = row.id,
                )
            }
            else -> withQuoteAndReply(toNoteUi(row, byPubkey[row.pubkey]), row, byPubkey)
        }
    }

    /**
     * 引用(本文中の nostr:nevent・note / q タグ)と返信親を解決して NoteUi に載せる。
     *  - 本文の参照は「解決できたときだけ」カード化し、その参照トークンを本文から取り除く。
     *    解決できない（未取得の）参照はリンク(↗note1…)のまま残す（届けば次の再解決で展開）。
     *  - 本文に参照が無い場合は q タグから引用元を補完する。
     */
    private fun withQuoteAndReply(
        base: NoteUi, row: Event, byPubkey: Map<String, app.nostrdeck.db.Profile>,
    ): NoteUi {
        // 画像が無いノートは base.text が null（表示は event.content）。それでも本文中の nevent/note を
        // カード化できるよう、実際に表示される本文（base.text ?: content）を対象に参照を解決する。
        val src = base.text ?: row.content
        val (cleaned, inlineQuoted) = resolveInlineQuote(src, byPubkey)
        val quoted = inlineQuoted ?: run {
            // 本文に解決できる参照が無い → q タグから補完（relay ヒント= 3要素目。未取得なら取得を促す）。
            val qtag = parseTags(row.tags_json).firstOrNull { it.size >= 2 && it[0] == "q" }
            val quotedId = qtag?.getOrNull(1)
            val hints = qtag?.getOrNull(2)?.let { listOf(it) }.orEmpty()
            quotedId?.let { resolveNoteUi(it, byPubkey) ?: run { requestEvent(it, hints); null } }
        }
        // インライン参照を解決してテキストを削った場合のみ text を差し替える。未解決なら元の base.text を維持
        // （null のままにして event.content 表示に委ねる＝挙動を変えない）。
        val newText = if (inlineQuoted != null) cleaned else base.text
        // マーカー無しの旧式引用（e タグ＋本文 nevent 併記）では返信親と引用先が同じ id になる。
        // 引用カードで表示済みのものを返信文脈でも出すと二重表示になるので抑止する。
        val replyParent = resolveReplyParent(row, byPubkey)
            ?.takeIf { it.event.id != quoted?.event?.id }
        // [#380] 1111 で親を NoteUi に解決できなかった場合は、ルート参照（K/E/A/I）から
        // 汎用の文脈行（「kind X へのコメント」等）を出せるよう構造情報を渡す。
        val commentRoot = if (row.kind.toInt() == Nip22.KIND && replyParent == null) {
            val tags = parseTags(row.tags_json)
            app.nostrdeck.model.CommentRootRef(
                eventId = Nip22.parentEventIdOf(tags) ?: Nip22.rootEventIdOf(tags),
                address = Nip22.rootAddressOf(tags),
                external = Nip22.rootExternalOf(tags),
                kind = Nip22.rootKindOf(tags),
            )
        } else {
            null
        }
        return base.copy(text = newText, quoted = quoted, replyParent = replyParent, commentRoot = commentRoot)
    }

    /**
     * 本文中の最初の nostr:nevent1.../note1... を引用元 NoteUi に解決する。
     *  - 解決できれば (参照を除いた本文, 引用 NoteUi) を返す。
     *  - 未取得なら requestEvent で取得を促し、(本文はそのまま=リンクを残す, null) を返す。
     */
    private fun resolveInlineQuote(
        text: String?, byPubkey: Map<String, app.nostrdeck.db.Profile>,
    ): Pair<String?, NoteUi?> {
        if (text.isNullOrEmpty()) return text to null
        val ref = findEventRef(text) ?: return text to null
        val quoted = resolveNoteUi(ref.id, byPubkey)
        if (quoted == null) {
            requestEvent(ref.id, ref.relays)  // nevent のリレーヒントも使って取得を促す
            return text to null  // 未解決はリンクのまま残す
        }
        val cleaned = (text.substring(0, ref.start) + text.substring(ref.end)).trim()
        return cleaned to quoted
    }

    /** 本文中の nevent/note 参照1件（位置・id・埋め込みリレーヒント）。 */
    private class EventRef(val start: Int, val end: Int, val id: String, val relays: List<String>)

    /**
     * 本文を走査し最初の解決可能な nevent1.../note1... を返す（id と nevent TLV のリレーヒント付き）。
     * `nostr:` 接頭辞付き・素の表記の両方に対応（接頭辞があれば開始位置に含めて除去する）。
     *
     * [#369] 走査は共通トークナイザ [tokenizeNostrContent] に一本化。URL が先に1トークンとして
     * 確定するので、`https://…/post/note1…` のような URL パス中の bech32 は引用扱いにならない
     * （URL は表示側の OGP カードに委ねる。njump.me 等の例外ドメインも作らない）。
     * 以前は独自 Regex（直前が英数字の場合のみ除外）で、`/` 区切りの URL 内 note1 を拾っていた。
     */
    private fun findEventRef(text: String): EventRef? {
        for (tok in tokenizeNostrContent(text)) {
            if (tok !is ContentToken.NostrRef) continue
            if (!tok.bech.startsWith("note1") && !tok.bech.startsWith("nevent1")) continue
            Nip19.eventBechToIdAndRelays(tok.bech)?.let { (id, relays) ->
                return EventRef(tok.start, tok.end, id, relays)
            }
        }
        return null
    }

    /** [M8-repost] イベント id を DB から解決して表示用 NoteUi に（無ければ null）。 */
    private fun resolveNoteUi(eventId: String, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi? {
        val row = q.eventById(eventId).executeAsOneOrNull() ?: return null
        return toNoteUi(row, byPubkey[row.pubkey])
    }

    /**
     * [M10] kind:1 が返信(#e)なら、その親ノートを解決して返す（返信の文脈表示用）。
     * キャッシュに無ければ id 指定で取得を促し、届き次第フィードが再解決される。
     * [#380] kind:1111（NIP-22）は 小文字 e=親 → ルート E → 親 a(ローカル解決のみ) の順で引く。
     * 親が記事(30023)なら1行プレビューにタイトルを出す（本文 Markdown 全文を流し込まない）。
     */
    private fun resolveReplyParent(row: Event, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi? {
        val kind = row.kind.toInt()
        val tags = parseTags(row.tags_json)
        val parentId = when (kind) {
            1 -> replyParentOf(tags)
            Nip22.KIND -> Nip22.parentEventIdOf(tags)
                ?: Nip22.rootEventIdOf(tags)
                ?: Nip22.parentAddressOf(tags)?.let { addressToIdLocal(it) }
            else -> null
        } ?: return null
        // 返信先 e タグの relay ヒント（3要素目）があれば取得に使う。
        val hints = tags.firstOrNull { it.size >= 3 && it[0] == "e" && it[1] == parentId }?.get(2)
            ?.takeIf { it.isNotEmpty() }?.let { listOf(it) }.orEmpty()
        val parent = resolveNoteUi(parentId, byPubkey) ?: run { requestEvent(parentId, hints); return null }
        if (parent.event.kind != 30023) return parent
        // 記事: 1行プレビュー用にタイトル（無ければ summary → 本文の最初の非空行）を text へ。
        val parentRow = q.eventById(parentId).executeAsOneOrNull()
        val ptags = parentRow?.let { parseTags(it.tags_json) }.orEmpty()
        fun tagOf(name: String) = ptags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)?.takeIf { it.isNotBlank() }
        val title = tagOf("title") ?: tagOf("summary")
            ?: parent.event.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return parent.copy(text = title ?: parent.text)
    }

    /**
     * [#380] アドレス "kind:pubkey:d" をローカル DB だけで event id へ解決する（能動取得はしない。
     * フィードの変換ループから呼ばれるため。取得込みの解決は resolveAddress）。
     */
    private fun addressToIdLocal(address: String): String? {
        val parts = address.split(":")
        val kind = parts.getOrNull(0)?.toLongOrNull() ?: return null
        if (parts.size < 3) return null
        val author = parts[1]
        val d = parts.drop(2).joinToString(":")
        return q.eventsByKindAuthor(kind, author).executeAsList().firstOrNull { row ->
            runCatching { parseTags(row.tags_json).any { it.size >= 2 && it[0] == "d" && it[1] == d } }
                .getOrDefault(false)
        }?.id
    }

    /** [M8-repost] NostrEvent + 解決済み profile → NoteUi（content 埋め込みの元ノート用）。 */
    private fun noteUiFromEvent(ev: NostrEvent, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi {
        val prof = byPubkey[ev.pubkey]
        val name = prof?.name?.takeIf { it.isNotBlank() } ?: ev.pubkey.take(10)
        val (strippedText, images) = extractMedia(ev.content)
        val text = strippedText ?: ""   // [#326] toNoteUi と同じ理由
        return NoteUi(
            event = ev,
            author = Profile(ev.pubkey, name, prof?.handle ?: "", prof?.picture_url),
            text = text, images = images,
            imeta = app.nostrdeck.model.imetaInfo(ev.tags),   // [#140]
        )
    }

    /** [M8-repost] pubkey → 表示用 Profile（未解決なら短縮 pubkey を名前に）。 */
    private fun profileFor(pubkey: String, byPubkey: Map<String, app.nostrdeck.db.Profile>): Profile {
        val p = byPubkey[pubkey]
        return Profile(pubkey, p?.name?.takeIf { it.isNotBlank() } ?: pubkey.take(10), p?.handle ?: "", p?.picture_url)
    }

    /** [M8-repost] NIP-18: kind:6 の content に埋め込まれた元イベント JSON を NostrEvent へ（無ければ null）。 */
    private fun parseEmbeddedEvent(content: String): NostrEvent? = runCatching {
        val o = json.parseToJsonElement(content).jsonObject
        NostrEvent(
            id = o["id"]!!.jsonPrimitive.content,
            pubkey = o["pubkey"]!!.jsonPrimitive.content,
            kind = o["kind"]!!.jsonPrimitive.int,
            createdAt = o["created_at"]!!.jsonPrimitive.long,
            content = o["content"]!!.jsonPrimitive.content,
            tags = (o["tags"] as? JsonArray)?.map { t -> t.jsonArray.map { it.jsonPrimitive.content } } ?: emptyList(),
            sig = o["sig"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }.getOrNull()

    // 画像抽出は UI と共通実装（app.nostrdeck.ui.extractMedia）を使う。

    private fun tagsToJson(tags: List<List<String>>): String = buildJsonArray {
        tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) }
    }.toString()

    // ---- [M8] 集約ヘルパ ----

    /** [M10] フィードに載せるメタ（自分が♡/リポスト済みか + 自分のリアクション絵文字）。 */
    private data class NoteMeta(
        val myReacted: Set<String>,
        val myReposted: Set<String>,
        val myReaction: Map<String, ReactionUi> = emptyMap(),
    )

    /** tags_json（[[..],[..]]）を List<List<String>> に復元。壊れていれば空。 */
    private fun parseTags(tagsJson: String): List<List<String>> = runCatching {
        json.parseToJsonElement(tagsJson).jsonArray.map { arr ->
            arr.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        }
    }.getOrDefault(emptyList())

    /**
     * kind:7 の content を集約キー/表示/カスタム絵文字 URL へ正規化する。
     *  - "+"/空 → ❤️（like）、"-" → 👎
     *  - ":shortcode:" → NIP-30 カスタム絵文字。emoji タグから URL を解決（無ければ文字表示）
     *  - それ以外 → unicode 絵文字をそのままキーにする
     */
    private fun normalizeReaction(content: String, tags: List<List<String>>): ReactionUi {
        val c = content.trim()
        if (c == "+" || c.isEmpty()) return ReactionUi("❤️", "❤️", 0)
        if (c == "-") return ReactionUi("👎", "👎", 0)
        if (c.length >= 2 && c.startsWith(":") && c.endsWith(":")) {
            val shortcode = c.substring(1, c.length - 1)
            val url = tags.firstOrNull { it.size >= 3 && it[0] == "emoji" && it[1] == shortcode }?.get(2)
            return ReactionUi(c, c, 0, url)
        }
        return ReactionUi(c, c, 0)
    }

    // ---- [M11] media upload (NIP-96/98) ----

    /**
     * 画像アップロードと NIP-96 探索に使う HttpClient（リレーの WebSocket とは別系統）。
     * [#55] タイムアウトを設定: スリープ復帰後に TCP が黙って死んでいても無限ハングせず失敗させ、
     * ComposeSheet の sendError 経路（＝送信ボタン再有効化・再送）へ確実に乗せる。
     */
    private val uploadHttp = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000   // 1リクエスト全体（大きめ画像も許容）
            connectTimeoutMillis = 15_000   // 接続確立
            socketTimeoutMillis = 30_000    // 無通信（ソケット黙殺の検出）
        }
    }

    /** [M11] DB のメディアサーバ一覧（Settings 用）。enabled/順序つき。 */
    fun mediaServersFlow(): Flow<List<app.nostrdeck.db.Media_server>> =
        q.allMediaServers().asFlow().mapToList(Dispatchers.Default)

    /** [M11] メディアサーバを手動追加（末尾に。enabled 既定 true）。 */
    fun addMediaServer(url: String) {
        val u = url.trim().trimEnd('/')
        if (u.isBlank()) return
        val next = q.allMediaServers().executeAsList().size.toLong()
        q.insertMediaServerIfAbsent(u, 1, next)
    }

    /** [M11] メディアサーバを設定から除去。 */
    fun removeMediaServer(url: String) = q.deleteMediaServer(url)

    /** [M11] メディアサーバの有効/無効を切替え。 */
    fun setMediaServerEnabled(url: String, enabled: Boolean) =
        q.setMediaServerEnabled(if (enabled) 1 else 0, url)

    // ---- [M14] リンク埋め込み（OGP / YouTube / Spotify）の設定 + OGP 取得 ----

    private val embedPrefsFlow = MutableStateFlow(EmbedPrefs())
    /** リンク埋め込み設定（設定 > 表示）。 */
    fun embedPrefsFlow(): StateFlow<EmbedPrefs> = embedPrefsFlow

    /** KV から埋め込み設定を復元（未設定は既定=有効）。start() から呼ぶ。 */
    private fun loadEmbedPrefs() {
        fun b(key: String, def: Boolean) = q.getSetting(EMBED_PREFIX + key).executeAsOneOrNull()?.let { it == "1" } ?: def
        embedPrefsFlow.value = EmbedPrefs(
            youtube = b("youtube", true), spotify = b("spotify", true),
            ogp = b("ogp", true), ogpImages = b("ogp_images", true),
            video = b("video", true),
            hideCardedUrls = b("hide_carded_urls", true),   // [#326] 既定は畳む
        )
    }

    fun setEmbedPrefs(prefs: EmbedPrefs) {
        embedPrefsFlow.value = prefs
        putSettingAsync(EMBED_PREFIX + "youtube", if (prefs.youtube) "1" else "0")
        putSettingAsync(EMBED_PREFIX + "spotify", if (prefs.spotify) "1" else "0")
        putSettingAsync(EMBED_PREFIX + "ogp", if (prefs.ogp) "1" else "0")
        putSettingAsync(EMBED_PREFIX + "ogp_images", if (prefs.ogpImages) "1" else "0")
        putSettingAsync(EMBED_PREFIX + "video", if (prefs.video) "1" else "0")
        putSettingAsync(EMBED_PREFIX + "hide_carded_urls", if (prefs.hideCardedUrls) "1" else "0")
    }

    // ---- [#appearance] 文字サイズ（小/中/大。小=従来）----

    private val textScaleState = MutableStateFlow(TextScale.SMALL)
    /** 文字サイズ設定（設定 > 表示）。fontScale への乗算係数として App ルートで適用する。 */
    fun textScaleFlow(): StateFlow<TextScale> = textScaleState

    fun setTextScale(scale: TextScale) {
        textScaleState.value = scale
        putSettingAsync(TEXT_SCALE_KEY, scale.id)
    }

    /** KV から文字サイズを復元（未設定は小=従来サイズ）。start() から呼ぶ。 */
    private fun loadTextScale() {
        textScaleState.value = TextScale.fromId(q.getSetting(TEXT_SCALE_KEY).executeAsOneOrNull())
    }

    // ---- [#247] 画像アップロード圧縮（低/中の長辺px + 再エンコード品質）----

    private val imageCompressionState = MutableStateFlow(ImageCompressionPrefs.DEFAULT)
    /** 画像圧縮設定（設定 > メディアサーバー）。投稿の圧縮パラメータとして ComposeSheet が参照。 */
    fun imageCompressionFlow(): StateFlow<ImageCompressionPrefs> = imageCompressionState

    fun setImageCompression(prefs: ImageCompressionPrefs) {
        // 範囲外はここでクランプして保存（UI 側の入力検証に依存しない）。
        val clamped = ImageCompressionPrefs(
            lowMaxDim = prefs.lowMaxDim.coerceIn(ImageCompressionPrefs.MIN_DIM, ImageCompressionPrefs.MAX_DIM),
            midMaxDim = prefs.midMaxDim.coerceIn(ImageCompressionPrefs.MIN_DIM, ImageCompressionPrefs.MAX_DIM),
            quality = prefs.quality.coerceIn(ImageCompressionPrefs.MIN_QUALITY, ImageCompressionPrefs.MAX_QUALITY),
        )
        imageCompressionState.value = clamped
        putSettingAsync(IMG_LOW_DIM_KEY, clamped.lowMaxDim.toString())
        putSettingAsync(IMG_MID_DIM_KEY, clamped.midMaxDim.toString())
        putSettingAsync(IMG_QUALITY_KEY, clamped.quality.toString())
    }

    /** KV から画像圧縮設定を復元（未設定/不正値は既定）。start() から呼ぶ。 */
    private fun loadImageCompression() {
        imageCompressionState.value = ImageCompressionPrefs.from(
            q.getSetting(IMG_LOW_DIM_KEY).executeAsOneOrNull(),
            q.getSetting(IMG_MID_DIM_KEY).executeAsOneOrNull(),
            q.getSetting(IMG_QUALITY_KEY).executeAsOneOrNull(),
        )
    }

    // ---- [#248] 動画アップロード圧縮（低/中の縦解像度p）----

    private val videoCompressionState = MutableStateFlow(VideoCompressionPrefs.DEFAULT)
    /** 動画圧縮設定（設定 > メディアサーバー）。投稿のトランスコード先解像度として ComposeSheet が参照。 */
    fun videoCompressionFlow(): StateFlow<VideoCompressionPrefs> = videoCompressionState

    fun setVideoCompression(prefs: VideoCompressionPrefs) {
        val clamped = VideoCompressionPrefs(
            lowHeight = prefs.lowHeight.coerceIn(VideoCompressionPrefs.MIN_HEIGHT, VideoCompressionPrefs.MAX_HEIGHT),
            midHeight = prefs.midHeight.coerceIn(VideoCompressionPrefs.MIN_HEIGHT, VideoCompressionPrefs.MAX_HEIGHT),
        )
        videoCompressionState.value = clamped
        putSettingAsync(VIDEO_LOW_H_KEY, clamped.lowHeight.toString())
        putSettingAsync(VIDEO_MID_H_KEY, clamped.midHeight.toString())
    }

    /** KV から動画圧縮設定を復元（未設定/不正値は既定）。start() から呼ぶ。 */
    private fun loadVideoCompression() {
        videoCompressionState.value = VideoCompressionPrefs.from(
            q.getSetting(VIDEO_LOW_H_KEY).executeAsOneOrNull(),
            q.getSetting(VIDEO_MID_H_KEY).executeAsOneOrNull(),
        )
    }

    // ---- [#256][#257] ノート種別の視覚表示（なし/縦ライン/背景色）----

    private val noteAccentState = MutableStateFlow(NoteAccentStyle.NONE)
    /** 種別の視覚表示スタイル（設定 > 表示）。NoteItem が参照する。既定 NONE=従来の見た目。 */
    fun noteAccentStyleFlow(): StateFlow<NoteAccentStyle> = noteAccentState

    fun setNoteAccentStyle(style: NoteAccentStyle) {
        noteAccentState.value = style
        putSettingAsync(NOTE_ACCENT_STYLE_KEY, style.id)
    }

    /** KV から復元（未設定は NONE）。start() から呼ぶ。 */
    private fun loadNoteAccentStyle() {
        noteAccentState.value = NoteAccentStyle.fromId(q.getSetting(NOTE_ACCENT_STYLE_KEY).executeAsOneOrNull())
    }

    // ---- [#258] カスタムテーマ（背景/文字/アクセントの3色）----

    private val customThemeState = MutableStateFlow(CustomThemePrefs.DEFAULT)
    /** カスタムテーマの3色（設定 > 表示）。ThemeMode.CUSTOM のときに適用される。 */
    fun customThemeFlow(): StateFlow<CustomThemePrefs> = customThemeState

    fun setCustomTheme(prefs: CustomThemePrefs) {
        customThemeState.value = prefs
        putSettingAsync(THEME_CUSTOM_BG, CustomThemePrefs.toHex(prefs.bg))
        putSettingAsync(THEME_CUSTOM_TEXT, CustomThemePrefs.toHex(prefs.text))
        putSettingAsync(THEME_CUSTOM_ACCENT, CustomThemePrefs.toHex(prefs.accent))
    }

    /** KV から復元（未設定/不正値は既定）。start() から呼ぶ。 */
    private fun loadCustomTheme() {
        customThemeState.value = CustomThemePrefs.from(
            q.getSetting(THEME_CUSTOM_BG).executeAsOneOrNull(),
            q.getSetting(THEME_CUSTOM_TEXT).executeAsOneOrNull(),
            q.getSetting(THEME_CUSTOM_ACCENT).executeAsOneOrNull(),
        )
    }

    // ---- [#264] テーマの配布（NIP-78 kind:30078 + t タグで一覧取得）----

    /**
     * 配布テーマの一覧。イベント表の kind:30078（t=nostrism-theme）を ThemeEntry へ復元する。
     * 同一 (pubkey, d) は最新1件だけ採用（addressable の重複除去）。
     */
    fun themeEntriesFlow(): Flow<List<ThemeEntry>> =
        q.themeEvents(ThemeEntry.DISCOVERY_TAG).asFlow().mapToList(Dispatchers.Default)
            .map { rows ->
                val seen = mutableSetOf<Pair<String, String>>()
                rows.mapNotNull { row ->
                    val tags = parseTags(row.tags_json)
                    val d = tags.firstOrNull { it.size >= 2 && it[0] == "d" }?.get(1) ?: return@mapNotNull null
                    // created_at 降順で来るので、最初に見た (pubkey,d) が最新。
                    if (!seen.add(row.pubkey to d)) return@mapNotNull null
                    parseThemeContent(row.content)?.copy(author = row.pubkey, dTag = d)
                }
            }
            .flowOn(Dispatchers.Default)

    /** 30078 の content(JSON) を ThemeEntry へ。想定外の形は null（壊れた配布物で落ちない）。 */
    private fun parseThemeContent(content: String): ThemeEntry? = runCatching {
        val o = json.parseToJsonElement(content).jsonObject
        // 他アプリの 30078 を誤って読まないよう app を確認する。
        val app = o["app"]?.jsonPrimitive?.contentOrNull
        if (app != null && app != ThemeEntry.APP) return null
        val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val colors = o["colors"]?.jsonObject ?: return null
        fun col(key: String): Int? =
            CustomThemePrefs.parseHex(colors[key]?.jsonPrimitive?.contentOrNull)
        val bg = col("bg") ?: return null
        val text = col("text") ?: return null
        val accent = col("accent") ?: return null
        ThemeEntry(
            name = name,
            colors = CustomThemePrefs(bg, text, accent),
            minAppVersion = o["minAppVersion"]?.jsonPrimitive?.contentOrNull ?: "0.3.0",
            schema = o["schema"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: ThemeEntry.SCHEMA,
        )
    }.getOrNull()

    /**
     * [#319] 自分が公開したテーマに削除リクエストを出す。
     *
     * addressable なので id と座標の両方を [requestDelete] が付ける。手元の一覧からは
     * 即座に消えるが、既に適用した人の配色はその人の端末に残る（配色は取り込み済みのため）。
     */
    suspend fun requestDeleteTheme(entry: ThemeEntry): Boolean {
        val author = entry.author ?: return false
        val d = entry.dTag ?: return false
        val row = q.themeEvents(ThemeEntry.DISCOVERY_TAG).executeAsList().firstOrNull { r ->
            r.pubkey == author && parseTags(r.tags_json).any { it.size >= 2 && it[0] == "d" && it[1] == d }
        } ?: return false
        return requestDelete(
            NostrEvent(row.id, row.pubkey, row.kind.toInt(), row.created_at, row.content, parseTags(row.tags_json), row.sig),
        )
    }

    /** 他ユーザーの配布テーマを取得する（t タグで検索。#d は完全一致しか引けないため）。 */
    fun requestThemes() {
        val subId = "themes"
        subscribeAll(subId, Filter(kinds = listOf(30078), hashtags = listOf(ThemeEntry.DISCOVERY_TAG), limit = 200))
        scope.launch { delay(10_000); unsubscribeAll(subId) }
    }

    /**
     * 自分のテーマを配布イベントとして発行する（同名は d が同じなので上書き＝更新）。
     * content は他クライアントからも読める素直な JSON。
     */
    suspend fun publishTheme(entry: ThemeEntry): Boolean = runCatching {
        val content = buildJsonObject {
            put("app", ThemeEntry.APP)
            put("schema", entry.schema)
            put("name", entry.name)
            put("minAppVersion", entry.minAppVersion)
            putJsonObject("colors") {
                put("bg", CustomThemePrefs.toHex(entry.colors.bg))
                put("text", CustomThemePrefs.toHex(entry.colors.text))
                put("accent", CustomThemePrefs.toHex(entry.colors.accent))
            }
        }.toString()
        publishSigned(
            UnsignedEvent(
                kind = 30078,
                content = content,
                tags = listOf(
                    listOf("d", ThemeEntry.D_PREFIX + entry.slug()),
                    listOf("t", ThemeEntry.DISCOVERY_TAG),   // 一覧取得用
                    listOf("title", entry.name),
                ),
            ),
        )
        true
    }.getOrElse {
        println("Nostrism publishTheme failed: $it")
        false
    }

    // ---- [#appearance] 表示サイズ（標準/大きめ/最大。標準=従来）----

    private val uiScaleState = MutableStateFlow(UiScale.SMALL)
    /** 表示サイズ設定（設定 > 表示）。density への乗算係数として App ルートで適用する。 */
    fun uiScaleFlow(): StateFlow<UiScale> = uiScaleState

    fun setUiScale(scale: UiScale) {
        uiScaleState.value = scale
        putSettingAsync(UI_SCALE_KEY, scale.id)
    }

    /** KV から表示サイズを復元（未設定は標準=従来サイズ）。start() から呼ぶ。 */
    // ---- [#327] 文字を太くする（既定OFF） ----
    private val boldTextState = MutableStateFlow(false)
    fun boldTextFlow(): StateFlow<Boolean> = boldTextState
    fun setBoldText(enabled: Boolean) {
        boldTextState.value = enabled
        putSettingAsync(BOLD_TEXT_KEY, if (enabled) "1" else "0")
    }
    private fun loadBoldText() {
        boldTextState.value = q.getSetting(BOLD_TEXT_KEY).executeAsOneOrNull() == "1"
    }

    // ---- [#378] にゃにゃにゃウイルス（オフ/自分のみ/全員。既定オフ）----
    // お遊びの猫化モード。**この端末の表示だけ**の演出で、発行イベントには一切影響しない。
    // 端末ローカル設定（#374 の SettingsSync ホワイトリストには入れない）。
    private val nyanModeState = MutableStateFlow(NyanMode.OFF)
    fun nyanModeFlow(): StateFlow<NyanMode> = nyanModeState
    fun setNyanMode(mode: NyanMode) {
        nyanModeState.value = mode
        putSettingAsync(NYAN_MODE_KEY, mode.id)
    }
    private fun loadNyanMode() {
        nyanModeState.value = NyanMode.fromId(q.getSetting(NYAN_MODE_KEY).executeAsOneOrNull())
    }

    // ---- [#351] 開発者モード（既定OFF）----
    // ON にすると投稿/チャットの ⋯ メニューに「イベントJSONを表示」が出る。
    private val developerModeState = MutableStateFlow(false)
    fun developerModeFlow(): StateFlow<Boolean> = developerModeState
    fun setDeveloperMode(enabled: Boolean) {
        developerModeState.value = enabled
        putSettingAsync(DEVELOPER_MODE_KEY, if (enabled) "1" else "0")
    }
    private fun loadDeveloperMode() {
        developerModeState.value = q.getSetting(DEVELOPER_MODE_KEY).executeAsOneOrNull() == "1"
    }

    private fun loadUiScale() {
        uiScaleState.value = UiScale.fromId(q.getSetting(UI_SCALE_KEY).executeAsOneOrNull())
    }

    // ---- [#152] テーマ（OSに合わせる/ライト/ダーク。既定=ダーク）----

    private val themeModeState = MutableStateFlow(ThemeMode.DARK)
    /** テーマ設定（設定 > 表示）。App ルートで DeckTheme に渡す。 */
    fun themeModeFlow(): StateFlow<ThemeMode> = themeModeState

    fun setThemeMode(mode: ThemeMode) {
        themeModeState.value = mode
        putSettingAsync(THEME_MODE_KEY, mode.id)
    }

    /** KV からテーマを復元（未設定はダーク=従来）。start() から呼ぶ。 */
    private fun loadThemeMode() {
        themeModeState.value = ThemeMode.fromId(q.getSetting(THEME_MODE_KEY).executeAsOneOrNull())
    }

    private val ogpCache = mutableMapOf<String, OgpData?>()
    private val ogpMutex = Mutex()

    // [#136] YouTube 動画情報（タイトル/チャンネル名）。oEmbed は API キー不要・軽量 JSON。
    private val ytInfoCache = mutableMapOf<String, Pair<String, String>?>()

    /**
     * YouTube 動画のタイトルとチャンネル名を oEmbed から取得する（videoId 単位でキャッシュ・失敗も記憶）。
     * 埋め込みカードに YouTube 標準風のタイトル帯を出すために使う。
     */
    suspend fun fetchYouTubeInfo(videoId: String): Pair<String, String>? {
        ogpMutex.withLock { if (ytInfoCache.containsKey(videoId)) return ytInfoCache[videoId] }
        val info = runCatching {
            withContext(Dispatchers.Default) {
                val body = uploadHttp.get(
                    "https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$videoId&format=json",
                ).bodyAsText()
                val o = json.parseToJsonElement(body).jsonObject
                val title = o["title"]?.jsonPrimitive?.contentOrNull
                title?.let { it to (o["author_name"]?.jsonPrimitive?.contentOrNull ?: "") }
            }
        }.getOrNull()
        ogpMutex.withLock {
            ytInfoCache[videoId] = info
            if (ytInfoCache.size > 256) ytInfoCache.remove(ytInfoCache.keys.first())
        }
        return info
    }

    /**
     * URL の OGP(OpenGraph) メタを取得する。成功/失敗ともメモリキャッシュ（null もキャッシュ）。
     * HTML 先頭のみを走査して og:title/og:description/og:image/og:site_name を拾う簡易実装。
     * [#368] ボディは先頭のみ読んで打ち切り（通常200KB / Amazonは画像JSONが後半のため512KB）、
     * 結果は DB にも TTL 付きで永続化する（成功7日・失敗1日。再起動時の全URL再取得を防ぐ）。
     */
    suspend fun fetchOgp(url: String): OgpData? {
        ogpMutex.withLock { if (ogpCache.containsKey(url)) return ogpCache[url] }
        // [#368] DB キャッシュ。TTL 内ならネットワークへ行かない。
        val cached = withContext(Dispatchers.Default) { q.getOgpCache(url).executeAsOneOrNull() }
        if (cached != null) {
            val ttl = if (cached.ok != 0L) OGP_TTL_OK_SEC else OGP_TTL_NG_SEC
            if (currentUnixTime() - cached.fetched_at < ttl) {
                val data = if (cached.ok == 0L) null else OgpData(
                    url, title = cached.title, description = cached.description,
                    image = cached.image, siteName = cached.site_name,
                )
                ogpMutex.withLock { putOgpMemory(url, data) }
                return data
            }
        }
        val data = runCatching {
            withContext(Dispatchers.Default) {
                // [#368] 全量ダウンロードをやめ、先頭 cap バイトで打ち切る（数MBのページ対策）。
                // OG タグは <head> にあるため通常は 200KB で足りる。Amazon の商品画像 JSON は
                // ページ後半に来る構成があるため 512KB まで緩和（それでも無ければ画像なしに劣化）。
                val cap = if (isAmazonUrl(url)) 512_000 else 200_000
                // [#137] ブラウザ風 UA を名乗らないと Amazon 等がボット扱いで 404/簡易ページを返す。
                val html = uploadHttp.prepareGet(url) {
                    header(HttpHeaders.UserAgent, OGP_UA)
                    header(HttpHeaders.AcceptLanguage, "ja-JP,ja;q=0.9,en;q=0.5")
                }.execute { resp ->
                    val ch = resp.bodyAsChannel()
                    val buf = ByteArray(cap)
                    var read = 0
                    while (read < cap) {
                        val n = ch.readAvailable(buf, read, cap - read)
                        if (n == -1) break
                        read += n
                    }
                    runCatching { ch.cancel(null) }   // 残りは読まない（execute を抜けると接続ごと破棄される）
                    // 途中で切れた多バイト文字は decodeToString が置換文字にするだけで走査には影響しない。
                    buf.decodeToString(0, read)
                }
                val head = html.take(200_000)  // <head> を含む先頭のみ
                fun meta(prop: String): String? {
                    // property="og:x" content="..." と content="..." property="og:x" の両順序に対応。
                    val a = Regex(
                        """<meta[^>]+(?:property|name)=["']$prop["'][^>]*content=["']([^"']*)["']""",
                        RegexOption.IGNORE_CASE,
                    ).find(head)?.groupValues?.get(1)
                    val b = Regex(
                        """<meta[^>]+content=["']([^"']*)["'][^>]*(?:property|name)=["']$prop["']""",
                        RegexOption.IGNORE_CASE,
                    ).find(head)?.groupValues?.get(1)
                    return (a ?: b)?.let { decodeHtmlEntities(it) }?.ifBlank { null }
                }
                val title = meta("og:title")
                    ?: Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
                        ?.let { decodeHtmlEntities(it.trim()) }?.ifBlank { null }
                // [#137] Amazon は OG タグを載せないため、商品ページ特有の画像フィールドから補完する。
                // 画像 JSON はページ後半に来る構成もあるので head ではなく全文を走査する。
                val image = meta("og:image") ?: if (isAmazonUrl(url)) {
                    Regex(""""hiRes"\s*:\s*"(https:[^"]+)"""").find(html)?.groupValues?.get(1)
                        ?: Regex("""id="landingImage"[^>]*\bsrc="(https:[^"]+)"""").find(html)?.groupValues?.get(1)
                        ?: Regex(""""large"\s*:\s*"(https:[^"]+)"""").find(html)?.groupValues?.get(1)
                } else null
                if (title == null && image == null) null
                else OgpData(url, title = title, description = meta("og:description"), image = image, siteName = meta("og:site_name"))
            }
        }.getOrNull()
        ogpMutex.withLock { putOgpMemory(url, data) }
        // [#368] DB へも保存。失敗も記録して、取れない URL の再試行を TTL(1日)に抑える。
        scope.launch(relayDispatcher) {
            q.putOgpCache(
                url, currentUnixTime(), if (data != null) 1L else 0L,
                data?.title, data?.description, data?.image, data?.siteName,
            )
        }
        return data
    }

    /** [#80] メモリ側 OGP キャッシュへの追加（呼び出し側で ogpMutex を取ること）。上限256件。 */
    private fun putOgpMemory(url: String, data: OgpData?) {
        ogpCache[url] = data
        if (ogpCache.size > 256) ogpCache.remove(ogpCache.keys.first())
    }

    private fun decodeHtmlEntities(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'").replace("&nbsp;", " ")

    // ---- [M13] NIP-57 Zap（LNURL-pay → invoice → 外部ウォレット起動） ----

    /** LNURL-pay の payRequest メタ（NIP-57 の allowsNostr/nostrPubkey を含む）。 */
    data class LnurlPay(
        val callback: String, val minSats: Long, val maxSats: Long,
        val commentAllowed: Int, val allowsNostr: Boolean, val nostrPubkey: String?,
    )

    /** lud16(name@domain) から LNURL-pay メタを取得。取得/解析失敗は null。 */
    suspend fun fetchLnurlPay(lud16: String): LnurlPay? = runCatching {
        withContext(Dispatchers.Default) {
            val at = lud16.indexOf('@'); if (at <= 0) return@withContext null
            val url = "https://${lud16.substring(at + 1)}/.well-known/lnurlp/${lud16.substring(0, at)}"
            val o = json.parseToJsonElement(uploadHttp.get(url).bodyAsText()).jsonObject
            if (o["tag"]?.jsonPrimitive?.contentOrNull != "payRequest") return@withContext null
            LnurlPay(
                callback = o["callback"]?.jsonPrimitive?.contentOrNull ?: return@withContext null,
                minSats = (o["minSendable"]?.jsonPrimitive?.long ?: 1000L) / 1000,
                maxSats = (o["maxSendable"]?.jsonPrimitive?.long ?: 100_000_000L) / 1000,
                commentAllowed = o["commentAllowed"]?.jsonPrimitive?.intOrNull ?: 0,
                allowsNostr = o["allowsNostr"]?.jsonPrimitive?.booleanOrNull ?: false,
                nostrPubkey = o["nostrPubkey"]?.jsonPrimitive?.contentOrNull,
            )
        }
    }.getOrNull()

    /**
     * Zap invoice(bolt11) を取得する。allowsNostr のサーバには NIP-57 zap request(kind:9734)を
     * 署名して `nostr` パラメータで添付する。返り値の invoice を `lightning:` URI で外部ウォレットへ渡す。
     * [eventId] を渡すとノートへの Zap（e タグ付き）、null ならプロフィール Zap。失敗は null。
     */
    suspend fun requestZapInvoice(
        recipientPubkey: String, lud16: String, amountSats: Long, comment: String,
        eventId: String?, targetKind: Int? = null,
    ): String? = runCatching {
        val pay = fetchLnurlPay(lud16) ?: return null
        val msat = amountSats * 1000
        val lnurl = lnurlEncode(lud16)
        val sep = if ('?' in pay.callback) '&' else '?'
        val sb = StringBuilder(pay.callback).append(sep).append("amount=").append(msat)
        if (pay.allowsNostr && !pay.nostrPubkey.isNullOrBlank()) {
            // NIP-57 zap request(kind:9734)。e タグ付きなら「投稿への Zap」、無ければプロフィール Zap。
            val relays = q.allRelays().executeAsList().filter { it.read != 0L }.map { it.url }.take(6)
            val tags = buildList {
                add(listOf("relays") + relays)
                add(listOf("amount", msat.toString()))
                if (lnurl != null) add(listOf("lnurl", lnurl))
                add(listOf("p", recipientPubkey))
                if (eventId != null) add(listOf("e", eventId))
                if (eventId != null && targetKind != null) add(listOf("k", targetKind.toString()))
            }
            val zapReq = SignerProvider.current().sign(UnsignedEvent(kind = 9734, content = comment, tags = tags))
            sb.append("&nostr=").append(RelayProtocol.eventJson(zapReq).encodeURLParameter())
            if (lnurl != null) sb.append("&lnurl=").append(lnurl)
        } else if (comment.isNotBlank() && pay.commentAllowed > 0) {
            sb.append("&comment=").append(comment.take(pay.commentAllowed).encodeURLParameter())
        }
        val resp = json.parseToJsonElement(uploadHttp.get(sb.toString()).bodyAsText()).jsonObject
        resp["pr"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /** lud16 を LNURL(bech32, hrp=lnurl) に符号化する（NIP-57 zap request の `lnurl` タグ/パラメータ用）。 */
    private fun lnurlEncode(lud16: String): String? = runCatching {
        val at = lud16.indexOf('@'); if (at <= 0) return null
        val url = "https://${lud16.substring(at + 1)}/.well-known/lnurlp/${lud16.substring(0, at)}"
        Bech32.encode("lnurl", Bech32.convertBits(url.encodeToByteArray(), 8, 5, true))
    }.getOrNull()

    // ---- [M13] NIP-57 Zap 受領(kind:9735) 集計（投稿ごとの合計 sats を表示する） ----

    private fun ingestZapReceipt(e: NostrEvent) {
        q.insertEvent(e.id, e.pubkey, 9735, e.createdAt, e.content, tagsToJson(e.tags), e.sig)
        indexTags(e)  // e/p タグを索引化（#e 集計・受信 Zap 通知に使う）
    }

    /**
     * 投稿ごとの Zap 合計 sats。kind:9735 の `description`(=zap request JSON)の amount タグ(msats)を
     * 合算する。amount が無ければ 0 として無視（bolt11 解析は行わない簡易実装）。
     */
    private val zapTotals: StateFlow<Map<String, Long>> by lazy {
        q.zapReceiptsForTargets().asFlow().mapToList(Dispatchers.Default).map { rows ->
            val totals = HashMap<String, Long>()
            rows.forEach { row ->
                val sats = zapAmountSats(parseTags(row.tags_json))
                if (sats > 0) totals[row.note_id] = (totals[row.note_id] ?: 0) + sats
            }
            totals as Map<String, Long>
        }.flowOn(Dispatchers.Default).stateIn(scope, feedSharing, emptyMap())
    }
    fun zapTotalsFlow(): StateFlow<Map<String, Long>> = zapTotals

    /** 9735 のタグ群から zap 額(sats)を取り出す（純関数 [Nip57] に委譲・単体テスト可能）。 */
    private fun zapAmountSats(tags: List<List<String>>): Long = Nip57.zapAmountSats(tags)

    /** 9735 のタグから Zap 送信者/コメントを取り出して ZapUi を組み立てる。 */
    private fun toZapUi(row: Event, byPk: Map<String, app.nostrdeck.db.Profile>): app.nostrdeck.model.ZapUi? {
        val tags = parseTags(row.tags_json)
        val sats = zapAmountSats(tags)
        val zapper = (tags.firstOrNull { it.size >= 2 && it[0] == "P" }?.get(1) ?: zapSenderFrom(tags)) ?: return null
        val comment = runCatching {
            tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
                ?.let { json.parseToJsonElement(it).jsonObject["content"]?.jsonPrimitive?.contentOrNull }
        }.getOrNull().orEmpty()
        val prof = byPk[zapper]
        return app.nostrdeck.model.ZapUi(
            id = row.id, sats = sats, comment = comment, createdAt = row.created_at,
            zapper = Profile(zapper, prof?.name?.takeIf { it.isNotBlank() } ?: zapper.take(10),
                prof?.handle ?: "", prof?.picture_url, lud16 = prof?.lud16),
        )
    }

    /** 指定ノートへの Zap（受領 9735）をリプライ風に列挙する（スレッド表示用）。 */
    fun zapsForNote(noteId: String): Flow<List<app.nostrdeck.model.ZapUi>> =
        combine(
            q.zapReceiptsForNote(noteId).asFlow().mapToList(Dispatchers.Default), profilesFlow,
        ) { rows, profiles ->
            val byPk = profiles.associateBy { it.pubkey }
            rows.mapNotNull { toZapUi(it, byPk) }
        }.flowOn(Dispatchers.Default)

    /** 9735 の description(zap request) から Zap 送信者 pubkey を取り出す（P タグが無い時のフォールバック）。 */
    private fun zapSenderFrom(tags: List<List<String>>): String? {
        val desc = tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1) ?: return null
        return runCatching { json.parseToJsonElement(desc).jsonObject["pubkey"]?.jsonPrimitive?.contentOrNull }.getOrNull()
    }

    /** 表示中ノート群の Zap 受領(kind:9735)を購読する（#e 集計のため）。 */
    fun subscribeZaps(subId: String, noteIds: List<String>) {
        if (noteIds.isEmpty()) return
        openColumns.add(subId)
        subscribeAll(subId, Filter(kinds = listOf(9735), eTags = noteIds.take(300), limit = 500))
    }

    // ---- [#270] 投稿詳細の反応集計（リアクション内訳 / リプライ・リポスト数） ----

    /** 対象ノートのリアクション内訳（絵文字ごとの数。カスタム絵文字は画像URL付き）。多い順。 */
    fun noteReactionsFlow(noteId: String): Flow<List<ReactionUi>> =
        q.reactionsForNote(noteId).asFlow().mapToList(Dispatchers.Default)
            .map { rows ->
                rows.groupBy {
                    val r = normalizeReaction(it.content, parseTags(it.tags_json))
                    r.display to r.imageUrl
                }.map { (k, list) -> ReactionUi(k.first, k.first, list.size, k.second) }
                    .sortedByDescending { it.count }
            }
            .flowOn(Dispatchers.Default)

    /**
     * [#254] 対象ノートのリアクション内訳＋した人（新しい順・pubkey 重複なし）。多い順。
     * 名前未取得の人は kind:0 を要求しつつ npub 短縮で出す（届き次第 DB Flow で差し替わる）。
     */
    fun noteReactionPeopleFlow(noteId: String): Flow<List<app.nostrdeck.model.ReactionGroupUi>> =
        q.reactionsForNoteWithAuthors(noteId).asFlow().mapToList(Dispatchers.Default)
            .map { rows ->
                rows.filter { it.pname.isNullOrBlank() }.map { it.pubkey }.distinct().forEach { requestProfile(it) }
                rows.groupBy {
                    val r = normalizeReaction(it.content, parseTags(it.tags_json))
                    r.display to r.imageUrl
                }.map { (k, list) ->
                    app.nostrdeck.model.ReactionGroupUi(
                        display = k.first, imageUrl = k.second,
                        people = list.distinctBy { it.pubkey }.map { row ->
                            Profile(
                                pubkey = row.pubkey,
                                name = row.pname?.takeIf { it.isNotBlank() } ?: shortNpub(row.pubkey),
                                handle = "", pictureUrl = row.ppicture,
                            )
                        },
                    )
                }.sortedByDescending { it.people.size }
            }
            .flowOn(Dispatchers.Default)

    /** [#254] 対象ノートをリポストした人（新しい順・重複なし）。 */
    fun noteRepostersFlow(noteId: String): Flow<List<Profile>> =
        q.repostersForNote(noteId).asFlow().mapToList(Dispatchers.Default)
            .map { rows ->
                rows.filter { it.pname.isNullOrBlank() }.forEach { requestProfile(it.pubkey) }
                rows.map { row ->
                    Profile(
                        pubkey = row.pubkey,
                        name = row.pname?.takeIf { it.isNotBlank() } ?: shortNpub(row.pubkey),
                        handle = "", pictureUrl = row.ppicture,
                    )
                }
            }
            .flowOn(Dispatchers.Default)

    private fun shortNpub(pubkey: String): String =
        runCatching { Nip19.hexToNpub(pubkey).take(12) + "…" }.getOrDefault(pubkey.take(12))

    /** 対象ノートへのリプライ数・リポスト数（kind:1 / kind:6,16 を kind 別に集計）。 */
    fun noteEngagementFlow(noteId: String): Flow<app.nostrdeck.model.NoteEngagement> =
        q.engagementCountsForNote(noteId).asFlow().mapToList(Dispatchers.Default)
            .map { rows ->
                var replies = 0
                var reposts = 0
                rows.forEach { r ->
                    when (r.kind) {
                        1L, 1111L -> replies += r.cnt.toInt()   // [#380] NIP-22 コメントもリプライに合算
                        6L, 16L -> reposts += r.cnt.toInt()
                    }
                }
                app.nostrdeck.model.NoteEngagement(replies = replies, reposts = reposts)
            }
            .flowOn(Dispatchers.Default)

    /** 対象ノートへの反応（kind:7/6/16）を購読する。リプライ(kind:1)は subscribeThread が担う。 */
    fun subscribeNoteEngagement(subId: String, noteId: String) {
        if (!openColumns.add(subId)) return
        subscribeAll(subId, Filter(kinds = listOf(7, 6, 16), eTags = listOf(noteId), limit = 500))
    }

    // ---- [M12] NIP-17 プライベートDM（gift wrap kind:1059 → seal kind:13 → rumor kind:14） ----

    private val processedWraps = mutableSetOf<String>()

    /** 受信 gift wrap(1059) を復号し、DM本体(kind:14)としてローカル保存。重複/失敗は無視。 */
    private fun ingestGiftWrap(e: NostrEvent) {
        if (!processedWraps.add(e.id)) return
        scope.launch {
            val rumor = Nip17.unwrap(SignerProvider.current(), e) ?: return@launch
            requestProfile(rumor.sender); rumor.recipient?.let { requestProfile(it) }
            // DM相手のアイコン/名前は接続中リレーに無いことが多いのでインデクサからも確実に取る。
            requestProfileFromIndexers(listOfNotNull(rumor.sender, rumor.recipient))
            storeDm(rumor.id, rumor.sender, rumor.recipient, rumor.content, rumor.createdAt)
        }
    }

    private val processedLegacyDm = mutableSetOf<String>()

    /**
     * NIP-04 旧型DM（kind:4）を復号して NIP-17 と同じ kind:14 として保存し、DM 画面に統合表示する。
     * content は「自分↔相手」の ECDH 共有鍵で AES-CBC 暗号（"?iv=" 形式）。相手＝
     * 自分が送信者なら p タグ(受信者)、そうでなければ送信者。復号失敗は無視。
     */
    private fun ingestLegacyDm(e: NostrEvent) {
        if (!processedLegacyDm.add(e.id)) return
        scope.launch {
            val me = myPubkey ?: SignerProvider.current().publicKeyHex().also { myPubkey = it; myPubkeyFlow.value = it }
            val recipient = e.tags.firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
            val peer = if (e.pubkey == me) recipient else e.pubkey
            if (peer.isNullOrBlank()) return@launch
            val plain = runCatching { SignerProvider.current().nip04Decrypt(peer, e.content) }.getOrNull() ?: return@launch
            requestProfile(e.pubkey); recipient?.let { requestProfile(it) }
            requestProfileFromIndexers(listOfNotNull(e.pubkey, recipient))
            storeDm(e.id, e.pubkey, recipient ?: me, plain, e.createdAt)
        }
    }

    private fun storeDm(id: String, sender: String, recipient: String?, content: String, createdAt: Long) {
        val tags = recipient?.let { listOf(listOf("p", it)) } ?: emptyList()
        q.insertEvent(id, sender, 14, createdAt, content, tagsToJson(tags), "")
        indexTags(NostrEvent(id, sender, 14, createdAt, content, tags, ""))
    }

    /**
     * DM を送る（NIP-17）。受信者宛＋自分宛の2通を gift wrap する。
     * NIP-17 仕様に従い、gift wrap は**受信者の kind:10050 リレー**へ（自分宛は自分の 10050 へ）配信。
     * 相手/自分の 10050 が未取得なら接続中の read リレーへフォールバックする。
     */
    suspend fun sendDm(peerPubkey: String, text: String) {
        if (text.isBlank()) return
        val me = myPubkey ?: SignerProvider.current().publicKeyHex().also { myPubkey = it; myPubkeyFlow.value = it }
        val signer = SignerProvider.current()
        val now = currentUnixTime()
        val rumorTags = listOf(listOf("p", peerPubkey))
        val rumorId = Nip01.eventId(me, now, 14, rumorTags, text)
        val rumorJson = buildJsonObject {
            put("id", rumorId); put("pubkey", me); put("created_at", now); put("kind", 14)
            putJsonArray("tags") { rumorTags.forEach { t -> add(buildJsonArray { t.forEach { add(it) } }) } }
            put("content", text)
        }.toString()
        // メタデータ曖昧化のため seal/wrap の created_at を直近2日内でランダム化（NIP-17）。
        fun rnd() = now - Random.nextLong(0, 2 * 24 * 3600)
        val toPeer = Nip17.wrap(signer, rumorJson, peerPubkey, rnd(), rnd())
        val toSelf = Nip17.wrap(signer, rumorJson, me, rnd(), rnd())
        storeDm(rumorId, me, peerPubkey, text, now)   // 楽観反映
        processedWraps.add(toPeer.id); processedWraps.add(toSelf.id)
        // 配信先を DM リレーへ限定（NIP-17）。無ければ接続 read リレーへ。
        val fallback = connectedReadRelays()
        val peerRelays = fetchDmRelaysFor(peerPubkey).ifEmpty { fallback }
        val myRelays = myDmRelaysOrSeed().ifEmpty { fallback }
        publishToRelays(RelayProtocol.event(toPeer), peerRelays)
        publishToRelays(RelayProtocol.event(toSelf), myRelays)
    }

    // ---- NIP-17 DM リレーリスト（kind:10050） ----

    private val dmRelaysByAuthor = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    private val dmRelaysAtByAuthor = mutableMapOf<String, Long>()

    private fun updateDmRelayList(e: NostrEvent) {
        if ((dmRelaysAtByAuthor[e.pubkey] ?: 0L) >= e.createdAt) return
        dmRelaysAtByAuthor[e.pubkey] = e.createdAt
        val urls = e.tags.filter { it.size >= 2 && it[0] == "relay" }.map { normalizeRelayUrl(it[1]) }
            .filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()
        dmRelaysByAuthor.value = dmRelaysByAuthor.value + (e.pubkey to urls)
        // 自分の DM リレーが判明したら、そこへも接続して追加購読する（broad な dm_inbox は維持）。
        if (e.pubkey == myPubkey && urls.isNotEmpty()) {
            subscribeTargeted("dm_inbox_relays", urls.toSet(), Filter(kinds = listOf(1059), pTags = listOf(e.pubkey)))
            subscribeTargeted("dm4_relays", urls.toSet(), Filter(kinds = listOf(4), pTags = listOf(e.pubkey)))
        }
    }

    private fun connectedReadRelays(): List<String> =
        q.allRelays().executeAsList().filter { it.read != 0L }.map { it.url }

    /** 相手の DM リレー(kind:10050)を取得。既知なら即返し、未知ならインデクサ等へ問い合わせて短時間待つ。 */
    private suspend fun fetchDmRelaysFor(pubkey: String): List<String> {
        dmRelaysByAuthor.value[pubkey]?.let { if (it.isNotEmpty()) return it }
        val idxSub = "dmrl_${pubkey.take(6)}"
        val broadSub = "dmrl2_${pubkey.take(6)}"
        subscribeTargeted(idxSub, INDEXER_RELAYS.toSet(),
            Filter(kinds = listOf(10050), authors = listOf(pubkey), limit = 1))
        subscribeAll(broadSub, Filter(kinds = listOf(10050), authors = listOf(pubkey), limit = 1))
        withTimeoutOrNull(2500) {
            while (dmRelaysByAuthor.value[pubkey].isNullOrEmpty()) delay(150)
        }
        // [#50] DMリレー問い合わせ用の一時 REQ／接続を後始末（リストに無いインデクサを常駐させない）。
        scheduleTransientCleanup(idxSub)
        scheduleTransientCleanup(broadSub)
        return dmRelaysByAuthor.value[pubkey].orEmpty()
    }

    /** 自分の DM リレー。未設定なら初回 DM 時に read リレーからシードして kind:10050 を発行する。 */
    private suspend fun myDmRelaysOrSeed(): List<String> {
        val me = myPubkey ?: return emptyList()
        dmRelaysByAuthor.value[me]?.let { if (it.isNotEmpty()) return it }
        val reads = connectedReadRelays().take(4)
        if (reads.isNotEmpty()) publishDmRelays(reads)   // 初回のみ自動シード
        return reads
    }

    /** 自分の DM リレー一覧（設定 UI 用）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun myDmRelaysFlow(): Flow<List<String>> = myPubkeyFlow.flatMapLatest { me ->
        if (me == null) flowOf(emptyList()) else dmRelaysByAuthor.map { it[me].orEmpty() }
    }.distinctUntilChanged()

    /** DM リレー(kind:10050)を発行して自分の一覧を更新する。 */
    suspend fun publishDmRelays(urls: List<String>) {
        val clean = urls.map { normalizeRelayUrl(it) }.filter { it.startsWith("wss://") || it.startsWith("ws://") }.distinct()
        val tags = clean.map { listOf("relay", it) }
        val signed = publishSigned(UnsignedEvent(kind = 10050, content = "", tags = tags))
        myPubkey?.let {
            dmRelaysAtByAuthor[it] = signed.createdAt
            dmRelaysByAuthor.value = dmRelaysByAuthor.value + (it to clean)
        }
    }

    /** 指定ユーザーの kind:0 を通常＋インデクサから取得（DM 画面表示時など、確実に欲しい場面用）。 */
    fun fetchProfilesNow(pubkeys: List<String>) {
        pubkeys.filter { it.isNotBlank() }.forEach { requestProfile(it) }
        requestProfileFromIndexers(pubkeys)
    }

    /** DM 会話一覧（相手ごとに最新1件）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun dmConversationsFlow(): Flow<List<DmConversation>> = myPubkeyFlow.flatMapLatest { me ->
        if (me == null) flowOf(emptyList())
        else combine(q.dmAllForMe(me).asFlow().mapToList(Dispatchers.Default), profilesFlow) { rows, profiles ->
            val byPk = profiles.associateBy { it.pubkey }
            val seen = LinkedHashSet<String>()
            rows.mapNotNull { row ->
                val other = if (row.pubkey == me)
                    parseTags(row.tags_json).firstOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
                else row.pubkey
                if (other == null || !seen.add(other)) return@mapNotNull null
                val p = byPk[other]
                DmConversation(
                    pubkey = other,
                    name = p?.name?.takeIf { it.isNotBlank() } ?: other.take(10),
                    handle = p?.handle.orEmpty(),
                    lastMessage = row.content,
                    pictureUrl = p?.picture_url,
                )
            }
        }.flowOn(Dispatchers.Default)
    }

    /** 指定相手との DM メッセージ（時系列昇順・連投まとめ）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun dmMessagesFlow(peer: String): Flow<List<ChannelMessage>> = myPubkeyFlow.flatMapLatest { me ->
        if (me == null) flowOf(emptyList())
        else combine(q.dmMessagesWith(me, peer).asFlow().mapToList(Dispatchers.Default), profilesFlow) { rows, profiles ->
            val byPk = profiles.associateBy { it.pubkey }
            rows.mapIndexed { i, row ->
                val prev = rows.getOrNull(i - 1)
                val prof = byPk[row.pubkey]
                ChannelMessage(
                    event = NostrEvent(row.id, row.pubkey, 14, row.created_at, row.content, parseTags(row.tags_json), row.sig),
                    author = Profile(
                        row.pubkey, prof?.name?.takeIf { it.isNotBlank() } ?: row.pubkey.take(10),
                        prof?.handle ?: "", prof?.picture_url, lud16 = prof?.lud16,
                    ),
                    isMine = row.pubkey == me,
                    continuation = prev != null && prev.pubkey == row.pubkey && row.created_at - prev.created_at < 300,
                )
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * [M11] 画像をアップロードして表示用 URL を返す。
     * 有効なメディアサーバ(NIP-96)を順に試し、最初に成功した URL を返す。全滅なら null。
     */
    suspend fun uploadImage(bytes: ByteArray, mime: String, filename: String = "image"): String? =
        withContext(Dispatchers.Default) {
            for (s in q.enabledMediaServers().executeAsList()) {
                val url = runCatching { uploadToServer(s.url, bytes, mime, filename) }.getOrNull()
                if (!url.isNullOrBlank()) return@withContext url
            }
            null
        }

    /**
     * [M11] NIP-96 サーバへ1ファイルをアップロードする。
     *  1. `<server>/.well-known/nostr/nip96.json` を引いて api_url を解決（失敗時は既定パス）。
     *  2. api_url へ multipart/form-data（part 名 `file`）を POST。Authorization は NIP-98。
     *  3. レスポンス JSON から URL を抽出（nip94_event.tags の "url" / トップレベル "url"）。
     */
    private suspend fun uploadToServer(server: String, bytes: ByteArray, mime: String, filename: String): String? {
        val base = server.trim().trimEnd('/')
        val apiUrl = discoverApiUrl(base) ?: "$base/api/v1/media"
        val parts = formData {
            append(
                "file", bytes,
                Headers.build {
                    append(HttpHeaders.ContentType, mime)
                    append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                },
            )
        }
        val resp = uploadHttp.post(apiUrl) {
            header(HttpHeaders.Authorization, nip98Header(apiUrl, "POST"))
            setBody(MultiPartFormDataContent(parts))
        }
        return parseUploadResponse(resp.bodyAsText())
    }

    /** [M11] NIP-96 ディスカバリ。api_url（絶対/相対）を返す。失敗時 null。 */
    private suspend fun discoverApiUrl(base: String): String? = runCatching {
        val body = uploadHttp.get("$base/.well-known/nostr/nip96.json").bodyAsText()
        val api = json.parseToJsonElement(body).jsonObject["api_url"]?.jsonPrimitive?.contentOrNull
        when {
            api.isNullOrBlank() -> null
            api.startsWith("http") -> api
            else -> base + (if (api.startsWith("/")) api else "/$api")
        }
    }.getOrNull()

    /** [M11] アップロード成功レスポンスから表示用 URL を取り出す（NIP-96 / 簡易形の両対応）。 */
    private fun parseUploadResponse(body: String): String? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val fromNip94 = o["nip94_event"]?.jsonObject?.get("tags")?.let { tags ->
            (tags as? JsonArray)?.firstOrNull {
                val a = it.jsonArray
                a.size >= 2 && a[0].jsonPrimitive.contentOrNull == "url"
            }?.jsonArray?.get(1)?.jsonPrimitive?.contentOrNull
        }
        fromNip94 ?: o["url"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    /**
     * [M11] NIP-98 Authorization ヘッダ値（"Nostr <base64(署名済み kind:27235)>"）。
     * content="", tags=[["u",url],["method",method]] の kind:27235 を署名し JSON を base64 化。
     */
    private suspend fun nip98Header(url: String, method: String): String {
        val signed = SignerProvider.current().sign(
            UnsignedEvent(kind = 27235, content = "", tags = listOf(listOf("u", url), listOf("method", method))),
        )
        return "Nostr " + eventToJson(signed).encodeToByteArray().encodeBase64()
    }

    /** [M11] 署名済みイベントを NIP-01 の単体イベント JSON 文字列にする。 */
    private fun eventToJson(e: NostrEvent): String = buildJsonObject {
        put("id", e.id)
        put("pubkey", e.pubkey)
        put("created_at", e.createdAt)
        put("kind", e.kind)
        putJsonArray("tags") { e.tags.forEach { tag -> add(buildJsonArray { tag.forEach { add(it) } }) } }
        put("content", e.content)
        put("sig", e.sig)
    }.toString()

    // [#374] SettingsSync（ホワイトリスト定義）と単体テストから KV キー定数を参照するため internal。
    internal companion object {
        // [M8-repost] "q"=NIP-18 引用参照を索引。
        // [#380] "E"/"A"=NIP-22 コメントのルート参照（スレッド/記事コメント欄のローカル検索用）。
        val TAG_KEYS = setOf("t", "e", "p", "q", "E", "A")

        /**
         * [#389] kind 別にタグ索引を絞る。NIP-51 セット（30000/30003）の `p` はメンバー列挙で
         * 数百件になり得るうえ「この人を含むリスト」の逆引きは使っていないので索引しない。
         * 30003 の `e` は件数が少なく、将来「誰がブックマークしたか」に使えるので残す。
         */
        fun indexableTagKeys(kind: Int): Set<String> = when (kind) {
            30000, 30003 -> TAG_KEYS - "p"
            // [#393] ピン留め(30015)の t は一覧そのもので、「このタグをピン留めした人」の逆引きは使わない。
            30015 -> TAG_KEYS - "t"
            else -> TAG_KEYS
        }

        /** [M11] 既定のメディアサーバ(NIP-96)。start() で insert-if-absent して投入する。 */
        val DEFAULT_MEDIA_SERVERS = listOf("https://nostrcheck.me", "https://nostr.build")

        /** 引用/返信ヒント + インデクサで一時接続するリレーの上限（接続数の暴発防止）。 */
        const val HINT_RELAY_CAP = 16

        /** [#386] メモリに保持する他人の NIP-65 リレーリストの著者数上限。 */
        const val NIP65_PREFS_AUTHOR_CAP = 128

        /** 取り込みループが1トランザクションでまとめる最大イベント数。 */
        const val INGEST_BATCH = 400

        /**
         * kind:0/10002 を確実に引くためのインデクサ系リレー。DM相手のアイコン/名前が
         * 接続中リレーに無い場合の取りこぼし対策として一時接続して問い合わせる。
         */
        val INDEXER_RELAYS = listOf(
            "wss://purplepag.es",
            "wss://relay.nostr.band",
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
        )

        /**
         * [#8/#23] NIP-50 検索対応リレー。検索カラムはここへ問い合わせる（接続中リレーが未対応でも動くように）。
         * 到達性のばらつきに備えて複数へ投げ、どれか通れば結果が出るようにする。
         */
        val SEARCH_RELAYS = listOf(
            "wss://relay.nostr.band",
            "wss://relay.noswhere.sh",
            "wss://search.nos.today",
        )
        // [#210] 検索の取得上限。ローカル LIKE 表示（feedBySearch, LIMIT 300）に十分載るよう多めに取る。
        const val SEARCH_FETCH_LIMIT = 300
        // [#358] バックグラウンドでリレーを一時停止するまでの猶予（5分）。
        const val BG_PAUSE_DELAY_MS = 5 * 60 * 1000L
        // [#368] OGP の DB キャッシュ TTL（秒）。成功は7日、失敗は1日で取り直す。
        const val OGP_TTL_OK_SEC = 7L * 24 * 3600
        const val OGP_TTL_NG_SEC = 24L * 3600
        // [#368] 起動時に消す古い OGP キャッシュのしきい値（14日）。
        const val OGP_PURGE_SEC = 14L * 24 * 3600
        // [#259] キーワード検索のローカル読み出し上限。1条件あたり / 合成後の総数。
        const val SEARCH_ROWS_PER_QUERY = 300L
        const val SEARCH_ROWS_TOTAL = 300

        /** NIP-28 チャンネル一覧の取得元（運用中のインデクサ。latest 順・上限つきを返す）。 */
        const val CHANNELS_ENDPOINT = "https://thread.nchan.vip/channels"

        /** NIP-89 client タグに載せるアプリ名。 */
        const val CLIENT_NAME = "Nostrism"

        /** [#137] OGP 取得時に名乗るブラウザ風 UA（Amazon 等のボット拒否を避ける）。 */
        const val OGP_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"

        /** [#137] Amazon の商品 URL か（画像フォールバックの対象判定）。 */
        fun isAmazonUrl(url: String): Boolean =
            Regex("""^https?://(www\.)?amazon\.[a-z.]+/""", RegexOption.IGNORE_CASE).containsMatchIn(url) ||
                Regex("""^https?://amzn\.(to|asia)/""", RegexOption.IGNORE_CASE).containsMatchIn(url)
        /** client タグを付与する公開コンテンツ kind（投稿/リポスト/リアクション/パブリックチャット/
         *  NIP-22 コメント[#380]）。 */
        val CLIENT_TAG_KINDS = setOf(1, 6, 16, 7, 42, 1111)

        /** カラム別「ミュートを表示」設定の KV キー接頭辞（app_setting）。 */
        const val REVEAL_MUTED_PREFIX = "col_reveal_muted:"

        /** フォロー中カラム別「自分への反応を隠す」設定の KV キー接頭辞。 */
        const val HIDE_SELF_NOTICES_PREFIX = "col_hide_self_notices:"

        /** [M18] フォロー中カラム別「非表示にする通知系カテゴリ」の KV キー接頭辞（カンマ区切り）。 */
        const val FEED_CAT_HIDDEN_PREFIX = "col_feedcat_hidden:"

        /** [#10] カラム別の幅（"S"/"M"/"L"）の KV キー接頭辞。 */
        const val COL_WIDTH_PREFIX = "col_width:"

        /** [#27] 検索履歴（改行区切り・新しい順）の KV キー。 */
        const val SEARCH_HISTORY = "search_history"

        /** [#13] 投稿の下書き（未送信テキスト）の KV キー。 */
        const val COMPOSE_DRAFT = "compose_draft"
        const val COMPOSE_THREAD_DRAFT = "compose_thread_draft"   // [#316] 連投で積んだセグメント

        /** リンク埋め込み設定の KV キー接頭辞。 */
        const val EMBED_PREFIX = "embed:"
        const val TEXT_SCALE_KEY = "ui:text_scale"   // [#appearance] 文字サイズ（s/m/l）
        const val UI_SCALE_KEY = "ui:ui_scale"
        const val BOLD_TEXT_KEY = "appearance_bold_text"   // [#327]       // [#appearance] 表示サイズ（s/m/l）
        const val DEVELOPER_MODE_KEY = "developer_mode"   // [#351]
        const val NYAN_MODE_KEY = "ui:nyan_mode"   // [#378] にゃにゃにゃウイルス（off/self/all）
        const val NOTE_ACCENT_STYLE_KEY = "ui:note_accent"  // [#256][#257] 種別の視覚表示（none/line/bg）
        const val THEME_CUSTOM_BG = "ui:theme_custom_bg"         // [#258] カスタムテーマ 背景色
        const val THEME_CUSTOM_TEXT = "ui:theme_custom_text"     // [#258] カスタムテーマ 文字色
        const val THEME_CUSTOM_ACCENT = "ui:theme_custom_accent" // [#258] カスタムテーマ アクセント
        const val IMG_LOW_DIM_KEY = "media:img_low_dim"   // [#247] 画像圧縮「低」長辺px
        const val IMG_MID_DIM_KEY = "media:img_mid_dim"   // [#247] 画像圧縮「中」長辺px
        const val IMG_QUALITY_KEY = "media:img_quality"   // [#247] 画像圧縮 品質%（30-100）
        const val VIDEO_LOW_H_KEY = "media:video_low_h"   // [#248] 動画圧縮「低」縦解像度p
        const val VIDEO_MID_H_KEY = "media:video_mid_h"   // [#248] 動画圧縮「中」縦解像度p
        const val THEME_MODE_KEY = "ui:theme"        // [#152] テーマ（system/light/dark）

        /** デフォルトリアクション（♡ボタンの送信内容）の KV キー。 */
        const val DEFAULT_REACTION_CONTENT = "default_reaction:content"
        const val DEFAULT_REACTION_IMAGE = "default_reaction:image"


        const val EMOJI_LIST_TAGS_KEY = "emoji_list_tags"
        const val PINNED_HASHTAGS_KEY = "pinned_hashtags"   // [#393] kind:30015(d=pinned) の t タグ順キャッシュ
        /** [#122][#374] 30078 の d タグ（このアプリのカラム構成を示す識別子）。#122 発行分と互換。 */
        const val DECK_COLUMNS_D = "app.nostrdeck:deck-columns"
        /** [#374] 30078 の d タグ（設定スナップショット）。 */
        const val SETTINGS_SYNC_D = "nostrism-settings"

        /** 自分の最新 kind:0 生JSON（プロフィール編集の未知フィールド温存・purge 耐性用）。 */
        const val MY_PROFILE_JSON = "my_profile_json"

        /** [NIP-42] AUTH 応答ポリシーの KV キー（"off"/"dm"/"always"）。 */
        const val AUTH_POLICY = "nip42_auth_policy"

        /** [#9] 通知/DM の最終閲覧時刻（未読件数算出用）の KV キー。 */
        const val NOTIF_LAST_SEEN = "notif_last_seen"
        const val DM_LAST_SEEN = "dm_last_seen"
    }
}
