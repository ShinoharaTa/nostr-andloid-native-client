package app.nostrdeck.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
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
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.util.encodeBase64
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import app.nostrdeck.model.ChannelMessage
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.CustomEmoji
import app.nostrdeck.model.DmConversation
import app.nostrdeck.model.MuteCategory
import app.nostrdeck.model.EmbedPrefs
import app.nostrdeck.model.MuteEntry
import app.nostrdeck.model.MuteList
import app.nostrdeck.model.OgpData
import app.nostrdeck.model.FeedEntry
import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.AuthPolicy
import app.nostrdeck.model.FeedNoticeCategory
import app.nostrdeck.model.NotificationKind
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.model.Profile
import app.nostrdeck.model.ReactionUi
import app.nostrdeck.model.RelayPref
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
import app.nostrdeck.signer.SignerProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * SSOT リポジトリ。リレー購読→検証→DB 書き込み、読みは DB の Flow。
 * 各カラムは [subscribeColumn]/[unsubscribeColumn] で自分のフィルタを REQ（= カラム=REQ ライフサイクル）。
 */
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

    /** 自分の kind:10002（NIP-65）由来のリレーリスト。Settings で編集・表示する。 */
    val relayList = MutableStateFlow<List<RelayPref>>(emptyList())
    private var relayListAt = 0L

    /** 自分の kind:10030（NIP-51 絵文字リスト）の最新 created_at（古い版を無視）。 */
    private var emojiListAt = 0L

    fun start() {
        // [M15] 過去タイムラインはキャッシュしない: 起動毎に DM 以外のイベントを解放し、
        // リレーから読み直す。コールド起動を軽く保ち、DB を溜め込まない。
        q.transaction { q.clearTimelineEvents(); q.clearOrphanTags() }
        // 末尾スラッシュ違い（例: nos.lol と nos.lol/）で二重登録された既存行を一度だけ統合する。
        dedupeRelayUrls()
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
        // デフォルトリアクション（♡ボタンの送信内容）を KV から復元。
        loadDefaultReaction()
        // 「古のSNS廃人モード」を KV から復元。
        loadRetroMode()
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
            val me = SignerProvider.current().publicKeyHex()
            myPubkey = me; myPubkeyFlow.value = me
            subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
            subscribeAll("relaylist", Filter(kinds = listOf(10002), authors = listOf(me)))
            subscribeAll("emojilist", Filter(kinds = listOf(10030), authors = listOf(me)))
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

    /**
     * アプリがフォアグラウンド復帰したときに呼ぶ。バックオフ待機中のリレーを即再接続させる。
     * （バックグラウンドで OS がソケットを切ると最大30秒のバックオフに入るため、復帰時に短縮する）
     */
    fun onForeground() {
        scope.launch(relayDispatcher) { relays.values.forEach { it.wake() } }
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
            relayListAt = signed.createdAt
            relayList.value = rows.filter { it.read != 0L || it.write != 0L }
                .map { RelayPref(it.url, it.read != 0L, it.write != 0L, it.source) }
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
            relayList.value = emptyList(); relayListAt = 0L
            emojiListAt = 0L

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

            // 新しい鍵でフォロー(kind:3)・リレーリスト(kind:10002)・絵文字リスト(kind:10030)を取り直す。
            subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
            subscribeAll("relaylist", Filter(kinds = listOf(10002), authors = listOf(me)))
            subscribeAll("emojilist", Filter(kinds = listOf(10030), authors = listOf(me)))
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
            // 自分のフォロー(kind:3)・リレーリスト(kind:10002)と開いているカラムを張り直して再構築。
            val me = myPubkey
            if (me != null) {
                subscribeAll("contacts", Filter(kinds = listOf(3), authors = listOf(me)))
                subscribeAll("relaylist", Filter(kinds = listOf(10002), authors = listOf(me)))
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
     */
    fun persistPinnedColumns(specs: List<ColumnSpec>) {
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

    // ---- カラム = REQ ライフサイクル ----
    private val openColumns = mutableSetOf<String>()

    /** カラム表示時に購読開始（subId = columnId）。filter.relays 指定時はそのリレーだけへ配信。 */
    fun subscribeColumn(columnId: String, filter: ReqFilter) {
        if (!openColumns.add(columnId)) return
        columnLoadedState.value = columnLoadedState.value - columnId  // [#17] 再購読でロード中に戻す
        // [#17] EOSE が来ない/遅いリレーでも無限ロードにしない安全網（8秒でロード済み扱い）。
        scope.launch {
            delay(8000)
            if (columnId in openColumns) columnLoadedState.value = columnLoadedState.value + columnId
        }
        val proto = filter.toProtocol(limit = 100)
        when {
            // [#8] 検索カラムは NIP-50 対応リレーへ（接続中リレーが未対応でも結果を取れるように）。
            !filter.search.isNullOrBlank() -> subscribeTargeted(columnId, SEARCH_RELAYS.toSet(), proto)
            filter.relays.isNotEmpty() -> subscribeTargeted(columnId, filter.relays.toSet(), proto)
            else -> subscribeAll(columnId, proto)
        }
    }

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
            !filter.search.isNullOrBlank() -> subscribeTargeted(subId, SEARCH_RELAYS.toSet(), proto)
            filter.relays.isNotEmpty() -> subscribeTargeted(subId, filter.relays.toSet(), proto)
            else -> subscribeAll(subId, proto)
        }
        scope.launch { delay(6000); unsubscribeAll(subId); openColumns.remove(subId) }
    }

    /** カラム除去/オフスクリーン時に CLOSE。 */
    fun unsubscribeColumn(columnId: String) {
        followingJobs.remove(columnId)?.cancel()
        notifJobs.remove(columnId)?.cancel()
        columnLoadedState.value = columnLoadedState.value - columnId  // [#17]
        if (openColumns.remove(columnId)) unsubscribeAll(columnId)
    }

    // ---- FOLLOWING（フォロー中）: 自分の kind:3 を authors にした購読/読み出し ----
    private val followingJobs = mutableMapOf<String, Job>()

    /**
     * フォロー中カラムの購読。フォロー集合が解決/更新されるたびに REQ を貼り直す
     * （authors = 自分のフォロー）。GLOBAL と違い「タイトル＝フォロー中」と中身が一致する。
     */
    fun subscribeFollowing(columnId: String) {
        if (!openColumns.add(columnId)) return
        followingJobs[columnId] = scope.launch {
            follows.collect { authors ->
                // [M10] 自分の投稿もフォロー中タイムラインに出す（authors に自分を含める）。
                val withMe = (authors + listOfNotNull(myPubkey)).distinct()
                if (withMe.isNotEmpty()) {
                    // kind:1 本文 + kind:6/16 リポスト[M8-repost]（リアクション数は出さないので kind:7 は購読しない）。
                    subscribeAll(columnId, Filter(kinds = listOf(1, 6, 16), authors = withMe, limit = 100))
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
            (notes.map { FeedEntry.Post(it) } + notices.map { FeedEntry.Notice(it) } + myReactions)
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

    /** カラムのフィルタに対応する DB フィードを NoteUi で返す（cache-first）。
     *  遷移で空に戻らないよう filter ごとに StateFlow をキャッシュ（[feedSharing]）。 */
    private val columnFeedCache = mutableMapOf<ReqFilter, StateFlow<List<NoteUi>>>()
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
                rows.map { row ->
                    applyMeta(withQuoteAndReply(toNoteUi(row, byPubkey[row.pubkey]), row, byPubkey), meta)
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
    private val channelFeedCache = mutableMapOf<String, StateFlow<List<ChannelMessage>>>()
    fun channelMessagesFeed(channelId: String): StateFlow<List<ChannelMessage>> =
        channelFeedCache.getOrPut(channelId) {
            combine(
                q.messagesByChannel(channelId, 300L).asFlow().mapToList(Dispatchers.Default),
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
     */
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
                    // 返信/メンション(1)・リポスト(6/16)・リアクション(7)・Zap受領(9735) を自分宛(#p)で購読。
                    subscribeAll(columnId, Filter(kinds = listOf(1, 6, 16, 7, 9735), pTags = listOf(me), limit = 200))
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
        val snippet = targetEvent?.let { (extractMedia(it.content).first ?: it.content).take(80) }
        // 対象が kind:42（パブリックチャット）なら、そのルート #e＝チャンネル id をリンク先に。
        val channelId = targetEvent
            ?.takeIf { it.kind.toInt() == 42 }
            ?.let { rootOf(parseTags(it.tags_json)) }
        return when (row.kind.toInt()) {
            9735 -> NotificationUi(
                row.id, NotificationKind.ZAP, actor, row.created_at,
                zapSats = zapAmountSats(tags), targetNoteId = target, targetSnippet = snippet,
                targetChannelId = channelId,
            )
            7 -> {
                // NIP-25/30: "+"/空→❤️、":shortcode:" は emoji タグから画像URLを解決。
                val rx = normalizeReaction(row.content, tags)
                NotificationUi(row.id, NotificationKind.REACTION, actor, row.created_at,
                    reaction = rx.display, reactionImageUrl = rx.imageUrl,
                    targetNoteId = target, targetSnippet = snippet, targetChannelId = channelId)
            }
            6, 16 -> NotificationUi(row.id, NotificationKind.REPOST, actor, row.created_at,
                targetNoteId = target, targetSnippet = snippet, targetChannelId = channelId)
            else -> {
                val isReply = tags.any { it.size >= 2 && it[0] == "e" }
                NotificationUi(
                    row.id, if (isReply) NotificationKind.REPLY else NotificationKind.MENTION,
                    actor, row.created_at,
                    text = extractMedia(row.content).first ?: row.content,
                    targetNoteId = target, targetSnippet = snippet, targetChannelId = channelId,
                )
            }
        }
    }

    // ---- [M9-thread] NIP-10 スレッド ----

    /** スレッド購読：起点ノートとその root を id 指定で取得し、root/起点宛の返信(#e)を購読する。 */
    fun subscribeThread(columnId: String, focusId: String) {
        if (!openColumns.add(columnId)) return
        val ids = threadAnchorIds(focusId)
        subscribeAll(
            columnId,
            Filter(ids = ids),
            Filter(kinds = listOf(1), eTags = ids, limit = 200),
        )
    }

    /** スレッド表示（深さ付きで root→返信を並べる）。DB の差分に追従する。 */
    fun threadFeed(focusId: String): Flow<List<ThreadEntry>> {
        val ids = threadAnchorIds(focusId)
        val rootId = ids.lastOrNull() ?: focusId
        return combine(
            q.threadEvents(ids).asFlow().mapToList(Dispatchers.Default),
            profilesFlow,
        ) { rows, profiles ->
            buildThread(rows, focusId, rootId, profiles.associateBy { it.pubkey })
        }.flowOn(Dispatchers.Default)
    }

    /** 起点 id とその root id（DB の focus イベントの e タグから解決。無ければ focus 自身）。 */
    private fun threadAnchorIds(focusId: String): List<String> {
        val focus = q.eventById(focusId).executeAsOneOrNull()
        val rootId = focus?.let { rootOf(parseTags(it.tags_json)) } ?: focusId
        return listOf(focusId, rootId).distinct()
    }

    /** 取得済みイベント群から深さ優先のスレッドを組む（NIP-10 の e マーカー/位置で親を決める）。 */
    private fun buildThread(
        rows: List<Event>, focusId: String, rootId: String,
        byPubkey: Map<String, app.nostrdeck.db.Profile>,
    ): List<ThreadEntry> {
        val byId = rows.associateBy { it.id }
        val parentOf = rows.associate { it.id to replyParentOf(parseTags(it.tags_json)) }
        val children = HashMap<String, MutableList<Event>>()
        rows.forEach { row ->
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
        rows.filter { parentOf[it.id] == null || parentOf[it.id] !in byId }
            .sortedBy { it.created_at }
            .forEach { visit(it, 0) }
        return out
    }

    /** NIP-10: 返信先（reply マーカー → root マーカー → 位置で末尾の e）。 */
    private fun replyParentOf(tags: List<List<String>>): String? {
        val es = tags.filter { it.size >= 2 && it[0] == "e" }
        if (es.isEmpty()) return null
        es.firstOrNull { it.size >= 4 && it[3] == "reply" }?.let { return it[1] }
        es.firstOrNull { it.size >= 4 && it[3] == "root" }?.let { return it[1] }
        return es.last()[1]
    }

    /** NIP-10: root（root マーカー → 位置で先頭の e）。 */
    private fun rootOf(tags: List<List<String>>): String? {
        val es = tags.filter { it.size >= 2 && it[0] == "e" }
        if (es.isEmpty()) return null
        es.firstOrNull { it.size >= 4 && it[3] == "root" }?.let { return it[1] }
        return es.first()[1]
    }

    /** [M10] 自分の♡/リポスト/リアクション状態を NoteUi に反映（ボタンのハイライト・絵文字表示用）。 */
    private fun applyMeta(ui: NoteUi, meta: NoteMeta): NoteUi = ui.copy(
        mineReacted = ui.event.id in meta.myReacted,
        mineReaction = meta.myReaction[ui.event.id],
        mineReposted = ui.event.id in meta.myReposted,
    )

    // ---- [M9-profile] プロフィール表示 / フォロー操作 ----

    /** 指定 pubkey の解決済みプロフィール（kind:0）を流す。未取得なら null。 */
    fun profileFlow(pubkey: String): Flow<Profile?> =
        q.profileByPubkey(pubkey).asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.firstOrNull()?.let {
                Profile(it.pubkey, it.name, it.handle, it.picture_url, it.updated_at, it.about, it.website, it.lud16, it.banner)
            }
        }

    /** プロフィール画面を開いたとき等に kind:0 の取得を促す（バッチ REQ に投入）。 */
    fun loadProfile(pubkey: String) = requestProfile(pubkey)

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
    fun searchProfiles(prefix: String, limit: Int = 8): List<Profile> {
        val p = prefix.trim().lowercase()
        if (p.isEmpty()) return emptyList()
        return q.allProfiles().executeAsList()
            .filter { it.name.lowercase().startsWith(p) || it.handle.lowercase().startsWith(p) }
            .sortedByDescending { it.name.isNotBlank() }
            .take(limit)
            .map { Profile(it.pubkey, it.name, it.handle, it.picture_url, it.updated_at) }
    }

    /** 自分がこの pubkey をフォロー中か（kind:3 の更新に追従）。 */
    fun isFollowingFlow(pubkey: String): Flow<Boolean> = follows.map { pubkey in it }

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

    private fun rowsFlow(filter: ReqFilter): Flow<List<Event>> = when {
        filter.hashtags.isNotEmpty() -> q.feedByHashtag(filter.hashtags.first().lowercase())
        filter.authors.isNotEmpty() -> q.feedByAuthors(filter.authors, 0L)
        !filter.search.isNullOrBlank() -> q.feedBySearch(filter.search)
        else -> q.recentNotes(300L)
    }.asFlow().mapToList(Dispatchers.Default)

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
        val tags = hashtagsIn(content).map { listOf("t", it) } + emojiTagsIn(content) +
            (if (contentWarning != null) listOf(listOf("content-warning", contentWarning)) else emptyList())
        val signed = publishSigned(UnsignedEvent(kind = 1, content = content, tags = tags))
        recordHashtags(content, signed.createdAt)
    }

    /**
     * [#13] 連投スレッド。[segments] を先頭から順に投稿し、2件目以降は NIP-10 で
     * root(先頭) と reply(直前) を e タグに付けて自己スレッド化する。
     */
    suspend fun publishThread(segments: List<String>) {
        val segs = segments.map { it.trim() }.filter { it.isNotEmpty() }
        if (segs.isEmpty()) return
        var rootId: String? = null
        var prevId: String? = null
        var myPk: String? = null
        for (seg in segs) {
            val tags = buildList {
                if (rootId != null) {
                    add(listOf("e", rootId!!, "", "root"))
                    if (prevId != null && prevId != rootId) add(listOf("e", prevId!!, "", "reply"))
                    myPk?.let { add(listOf("p", it)) }
                }
                addAll(hashtagsIn(seg).map { listOf("t", it) })
                addAll(emojiTagsIn(seg))
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
            publishSigned(UnsignedEvent(kind = 5, content = "", tags = listOf(listOf("e", mineId))))
            q.transaction { q.deleteEventById(mineId); q.deleteTagsForEvent(mineId) }
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

    /** [M17] 「古のSNS廃人モード」。ON でデッキが高密度・玄人寄りの見た目/挙動になる。既定OFF。 */
    private val retroModeState = MutableStateFlow(false)
    fun retroModeFlow(): StateFlow<Boolean> = retroModeState
    private fun loadRetroMode() { retroModeState.value = q.getSetting(RETRO_MODE).executeAsOneOrNull() == "1" }
    fun setRetroMode(on: Boolean) {
        retroModeState.value = on
        putSettingAsync(RETRO_MODE, if (on) "1" else "0")
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
    suspend fun publishQuote(target: NostrEvent, text: String) {
        val note = runCatching { Nip19.hexToNote(target.id) }.getOrNull()
        val body = if (note != null) (if (text.isBlank()) "nostr:$note" else "$text\nnostr:$note") else text
        val tags = listOf(listOf("q", target.id), listOf("p", target.pubkey)) +
            hashtagsIn(text).map { listOf("t", it) } + emojiTagsIn(body)
        val signed = publishSigned(UnsignedEvent(kind = 1, content = body, tags = tags))
        recordHashtags(text, signed.createdAt)
    }

    /** [M8] NIP-10 返信（kind:1）。e(reply マーカー) + p を付け、本文の #タグも 't' 化する。 */
    suspend fun publishReply(target: NostrEvent, text: String) {
        val tags = listOf(listOf("e", target.id, "", "reply"), listOf("p", target.pubkey)) +
            hashtagsIn(text).map { listOf("t", it) } + emojiTagsIn(text)
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
        q.insertEvent(signed.id, signed.pubkey, signed.kind.toLong(), signed.createdAt, storedContent, tagsToJson(signed.tags), signed.sig)
        indexTags(signed)
        q.enqueuePublish(signed.id, payload, signed.createdAt, 0)
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

    /** 投稿で使ったハッシュタグ（最近順）。ComposeSheet のレコメンド/最近5件に使う。 */
    fun usedHashtagsFlow(): Flow<List<String>> =
        q.usedHashtagsByRecency().asFlow().mapToList(Dispatchers.Default)

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

    /** 本文中の `nostr:npub1…`（接頭辞任意）を hex に復号し、表示名解決のため kind:0 を要求する。 */
    private fun requestMentionedProfiles(content: String) {
        for (m in NPUB_MENTION_REGEX.findAll(content)) {
            val bech = m.value.substringAfter("nostr:", m.value)
            runCatching { Nip19.npubToHex(bech) }.getOrNull()?.let { requestProfile(it) }
        }
    }

    // ---- [M10] イベント id バッチ解決（返信先の親ノートなど未キャッシュ分を取得） ----
    private val eventRequests = Channel<String>(Channel.UNLIMITED)

    /**
     * 引用/返信の解決のために一時接続したリレーヒント集合（NIP-19/NIP-10 の relay ヒント）。
     * 重複接続と接続数の暴発を防ぐため上限つき。ステータス表示からは除外する（設定リレーのみ表示）。
     */
    private val hintRelays = mutableSetOf<String>()

    /**
     * イベント id の取得を要求する。[hints] があれば、そのリレー（未接続なら上限内で一時接続）
     * にも REQ が届くようにする。接続済み/ヒント無しなら従来どおり接続中リレーへ問い合わせる。
     */
    private fun requestEvent(id: String, hints: List<String> = emptyList()) {
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
            requested.addAll(pending)
            pending.clear()
            subscribeAll("profiles", Filter(kinds = listOf(0), authors = requested.toList()))
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
        when (e.kind) {
            1 -> {
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
            3 -> updateFollows(e)
            10002 -> updateRelayList(e)
            10000 -> updateMuteList(e)    // NIP-51 ミュートリスト
            10001 -> updatePinnedList(e)  // NIP-51 固定投稿（プロフィール上部）
            10003 -> updateBookmarkList(e) // NIP-51 ブックマーク
            4 -> ingestLegacyDm(e)        // NIP-04 旧型DM（kind:4）を復号して kind:14 に統合保存
            1059 -> ingestGiftWrap(e)     // NIP-17 DM（gift wrap を復号して kind:14 保存）
            9735 -> ingestZapReceipt(e)   // NIP-57 Zap 受領（#e 集計・受信通知に使う）
            10050 -> updateDmRelayList(e) // NIP-17 DM リレーリスト
            10030 -> updateEmojiList(e)   // NIP-51 自分の絵文字リスト
            30030 -> updateEmojiSet(e)    // NIP-51 絵文字セット（10030 の a タグ参照先）
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
    private fun updateEmojiList(e: NostrEvent) {
        if (e.pubkey != myPubkey) return
        if (e.createdAt < emojiListAt) return
        emojiListAt = e.createdAt
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

    /**
     * 自分の kind:10002（NIP-65）から `r` タグを取り出してリレーリストへ。
     * マーカー無し=read+write、"read"=Inbox のみ、"write"=Outbox のみ。
     * DB に 'nip65' として保存し、read(Inbox) のものだけ購読接続する。古い版は無視。
     * write 専用(Outbox)は購読せず、配信時に一時接続する（NIP-65 outbox）。
     */
    private fun updateRelayList(e: NostrEvent) {
        if (e.pubkey != myPubkey) return
        if (e.createdAt < relayListAt) return
        relayListAt = e.createdAt
        val entries = e.tags.filter { it.size >= 2 && it[0] == "r" }.map { t ->
            val marker = t.getOrNull(2)
            RelayPref(normalizeRelayUrl(t[1]), read = marker != "write", write = marker != "read", source = "nip65")
        }
        entries.forEach {
            q.upsertRelay(it.url, if (it.read) 1 else 0, if (it.write) 1 else 0, "nip65")
            if (it.read) ensureRelay(it.url)
        }
        relayList.value = entries
    }

    /** #t/#e/#p をタグ索引へ（ハッシュタグ等のカラム検索用）。't' は小文字化。 */
    private fun indexTags(e: NostrEvent) {
        e.tags.forEach { tag ->
            if (tag.size >= 2 && tag[0] in TAG_KEYS) {
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
     */
    suspend fun publishProfile(fields: Map<String, String>) {
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
        val (text, images) = extractMedia(row.content)
        val tags = parseTags(row.tags_json)
        // NIP-10: kind:1 が #e を持てば返信（プロフィールの「投稿/リプライ」振り分け用）。
        val isReply = row.kind.toInt() == 1 && tags.any { it.size >= 2 && it[0] == "e" }
        // NIP-30: 本文中の :shortcode: → 画像URL のマップ。
        val emojis = tags.filter { it.size >= 3 && it[0] == "emoji" }.associate { it[1] to it[2] }
        // NIP-36: content-warning タグ（あれば表示前に折りたたむ）。2要素目が理由（任意）。
        val cw = tags.firstOrNull { it.isNotEmpty() && it[0] == "content-warning" }
            ?.let { if (it.size >= 2) it[1] else "" }
        return NoteUi(
            event = NostrEvent(row.id, row.pubkey, row.kind.toInt(), row.created_at, row.content, emptyList(), row.sig),
            author = Profile(row.pubkey, name, prof?.handle ?: "", prof?.picture_url, lud16 = prof?.lud16),
            text = text, images = images, isReply = isReply, customEmojis = emojis, contentWarning = cw,
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
                val original = origId?.let { resolveNoteUi(it, byPubkey) }
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
        return base.copy(text = newText, quoted = quoted, replyParent = resolveReplyParent(row, byPubkey))
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
     */
    private fun findEventRef(text: String): EventRef? {
        for (m in EVENT_REF_REGEX.findAll(text)) {
            val bech = m.value.removePrefix("nostr:")
            Nip19.eventBechToIdAndRelays(bech)?.let { (id, relays) ->
                return EventRef(m.range.first, m.range.last + 1, id, relays)
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
     */
    private fun resolveReplyParent(row: Event, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi? {
        if (row.kind.toInt() != 1) return null
        val tags = parseTags(row.tags_json)
        val parentId = replyParentOf(tags) ?: return null
        // 返信先 e タグの relay ヒント（3要素目）があれば取得に使う。
        val hints = tags.firstOrNull { it.size >= 3 && it[0] == "e" && it[1] == parentId }?.get(2)?.let { listOf(it) }.orEmpty()
        return resolveNoteUi(parentId, byPubkey) ?: run { requestEvent(parentId, hints); null }
    }

    /** [M8-repost] NostrEvent + 解決済み profile → NoteUi（content 埋め込みの元ノート用）。 */
    private fun noteUiFromEvent(ev: NostrEvent, byPubkey: Map<String, app.nostrdeck.db.Profile>): NoteUi {
        val prof = byPubkey[ev.pubkey]
        val name = prof?.name?.takeIf { it.isNotBlank() } ?: ev.pubkey.take(10)
        val (text, images) = extractMedia(ev.content)
        return NoteUi(
            event = ev,
            author = Profile(ev.pubkey, name, prof?.handle ?: "", prof?.picture_url),
            text = text, images = images,
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

    private val imageUrlRegex =
        Regex("""https?://\S+?\.(?:jpg|jpeg|png|gif|webp)(?:\?\S*)?""", RegexOption.IGNORE_CASE)

    /** content から画像URLを抽出し、本文からは除去した (表示本文, 画像URL一覧) を返す。 */
    private fun extractMedia(content: String): Pair<String?, List<String>> {
        val urls = imageUrlRegex.findAll(content).map { it.value }.toList()
        if (urls.isEmpty()) return null to emptyList()
        var text = content
        urls.forEach { text = text.replace(it, "") }
        // URL 除去で生じた余分な空白/空行を整理。
        text = text.replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        return text.ifBlank { null } to urls.distinct()
    }

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
        )
    }

    fun setEmbedPrefs(prefs: EmbedPrefs) {
        embedPrefsFlow.value = prefs
        putSettingAsync(EMBED_PREFIX + "youtube", if (prefs.youtube) "1" else "0")
        putSettingAsync(EMBED_PREFIX + "spotify", if (prefs.spotify) "1" else "0")
        putSettingAsync(EMBED_PREFIX + "ogp", if (prefs.ogp) "1" else "0")
        putSettingAsync(EMBED_PREFIX + "ogp_images", if (prefs.ogpImages) "1" else "0")
        putSettingAsync(EMBED_PREFIX + "video", if (prefs.video) "1" else "0")
    }

    private val ogpCache = mutableMapOf<String, OgpData?>()
    private val ogpMutex = Mutex()

    /**
     * URL の OGP(OpenGraph) メタを取得する。成功/失敗ともメモリキャッシュ（null もキャッシュ）。
     * HTML 先頭のみを走査して og:title/og:description/og:image/og:site_name を拾う簡易実装。
     */
    suspend fun fetchOgp(url: String): OgpData? {
        ogpMutex.withLock { if (ogpCache.containsKey(url)) return ogpCache[url] }
        val data = runCatching {
            withContext(Dispatchers.Default) {
                val html = uploadHttp.get(url).bodyAsText()
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
                val image = meta("og:image")
                if (title == null && image == null) null
                else OgpData(url, title = title, description = meta("og:description"), image = image, siteName = meta("og:site_name"))
            }
        }.getOrNull()
        ogpMutex.withLock { ogpCache[url] = data }
        return data
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

    private companion object {
        val TAG_KEYS = setOf("t", "e", "p", "q")  // [M8-repost] "q"=NIP-18 引用参照を索引

        /** [M11] 既定のメディアサーバ(NIP-96)。start() で insert-if-absent して投入する。 */
        val DEFAULT_MEDIA_SERVERS = listOf("https://nostrcheck.me", "https://nostr.build")

        /** 本文中の nevent1.../note1...（nostr: 接頭辞は任意）。直前が英数字の語中ヒットは除外。 */
        val EVENT_REF_REGEX = Regex("(?<![a-z0-9])(nostr:)?(nevent1|note1)[a-z0-9]+")

        /** 本文中の npub1…（nostr: 接頭辞は任意）。メンションの表示名解決に使う。 */
        val NPUB_MENTION_REGEX = Regex("(?<![a-z0-9])(nostr:)?npub1[a-z0-9]+")

        /** 引用/返信ヒント + インデクサで一時接続するリレーの上限（接続数の暴発防止）。 */
        const val HINT_RELAY_CAP = 16

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

        /** NIP-28 チャンネル一覧の取得元（運用中のインデクサ。latest 順・上限つきを返す）。 */
        const val CHANNELS_ENDPOINT = "https://thread.nchan.vip/channels"

        /** NIP-89 client タグに載せるアプリ名。 */
        const val CLIENT_NAME = "Nostrism"
        /** client タグを付与する公開コンテンツ kind（投稿/リポスト/リアクション/パブリックチャット）。 */
        val CLIENT_TAG_KINDS = setOf(1, 6, 16, 7, 42)

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

        /** リンク埋め込み設定の KV キー接頭辞。 */
        const val EMBED_PREFIX = "embed:"

        /** デフォルトリアクション（♡ボタンの送信内容）の KV キー。 */
        const val DEFAULT_REACTION_CONTENT = "default_reaction:content"
        const val DEFAULT_REACTION_IMAGE = "default_reaction:image"

        /** 「古のSNS廃人モード」の KV キー（"1"/"0"）。 */
        const val RETRO_MODE = "retro_haijin_mode"

        /** 自分の最新 kind:0 生JSON（プロフィール編集の未知フィールド温存・purge 耐性用）。 */
        const val MY_PROFILE_JSON = "my_profile_json"

        /** [NIP-42] AUTH 応答ポリシーの KV キー（"off"/"dm"/"always"）。 */
        const val AUTH_POLICY = "nip42_auth_policy"

        /** [#9] 通知/DM の最終閲覧時刻（未読件数算出用）の KV キー。 */
        const val NOTIF_LAST_SEEN = "notif_last_seen"
        const val DM_LAST_SEEN = "dm_last_seen"
    }
}
