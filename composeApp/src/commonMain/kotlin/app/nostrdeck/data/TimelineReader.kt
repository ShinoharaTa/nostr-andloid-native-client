package app.nostrdeck.data

import app.nostrdeck.crypto.Nip19
import app.nostrdeck.crypto.currentUnixTime
import app.nostrdeck.model.MuteList
import app.nostrdeck.model.MuteMatcher
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.SpeechText
import app.nostrdeck.model.TtsVoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * [#948] フォロー中フィードの読み上げ。ON にした後にカラムへ描かれた投稿を、再生用のスロットに入れて古い順に 1 件ずつ読む。
 *  - 読む投稿はフォロー中のカラムが描く一覧から拾う（[onFeedRendered]。画面に出たものと読むものが一致する）。
 *    ON にした時点で出ていた投稿は読まない（[NewPostPicker]）。エンジンを読み込んでいる間に出た投稿は読む
 *  - リポスト・自分の投稿・ミュート対象は読まない
 *  - スロットは待ちの上限を設けない（追いつけなくても捨てず、届いた順に全部読む。待ちの件数は [Stats.waiting]）
 *  - カラムが画面から消えた（別のページ・設定など）間に届いた投稿は読まない（戻ったときの一覧は「前からある投稿」）
 *  - ON / OFF は端末に覚えない（起動したら OFF）
 *  - 声のエンジン（見えない WebView。百数十 MB 使う）は ON の間だけ持つ。OFF にしたら捨てる。
 *    ON のままアプリが裏に [backgroundReleaseMs] いたら捨て、戻ってから次に読むときに読み込み直す
 *  - エンジンが落ちた（WebView のプロセスが止められた等）ら作り直す。続けて落ちたら OFF にして知らせる
 */
class TimelineReader(
    private val scope: CoroutineScope,
    /** その声のモデルをダウンロード済みか（[TtsModels.isReady]）。 */
    private val ready: (TtsVoice) -> Boolean,
    private val voice: () -> TtsVoice,
    private val mutes: Flow<MuteList?>,
    /** 公開鍵（hex）→ 表示名（本文の nostr:npub を名前で読むため。分からなければ null）。読む投稿にあるときだけ引く。 */
    private val personName: suspend (String) -> String?,
    private val me: () -> String?,
    private val engineFactory: () -> SpeechEngine? = ::createSpeechEngine,
    private val now: () -> Long = ::currentUnixTime,
    private val backgroundReleaseMs: Long = BACKGROUND_RELEASE_MS,
) {
    enum class Status { OFF, PREPARING, ON }

    /** ON にできなかった・続けられなかった理由（画面にトーストで出す）。 */
    enum class Failure { NOT_DOWNLOADED, UNSUPPORTED, ENGINE }

    /** いまの ON の間に読んだ・読めなかった投稿の数と、スロットで待っている数（設定画面に出す。読まれないときの手がかり）。 */
    data class Stats(val read: Int = 0, val failed: Int = 0, val waiting: Int = 0, val lastError: String? = null)

    private val statusState = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = statusState.asStateFlow()

    private val failureEvents = MutableSharedFlow<Failure>(extraBufferCapacity = 4)
    val failures: SharedFlow<Failure> = failureEvents.asSharedFlow()

    private val statsState = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = statsState.asStateFlow()

    private var job: Job? = null
    @Volatile private var engine: SpeechEngine? = null
    /** 再生用のスロット（読む順に並ぶ）。ON の間だけある。 */
    @Volatile private var slot: Channel<NoteUi>? = null
    /** 裏に回ってエンジンを捨てた後（前に戻るまでスロットに入れない）。 */
    @Volatile private var sleeping = false
    /** カラムが描く一覧から「新しく出た投稿」を選ぶ。OFF の間も覚え続ける（ON にした時点の一覧を「前からある投稿」にするため）。 */
    private val picker = NewPostPicker(now)
    /** 続けてエンジンが落ちた回数（読めたら 0 に戻す）。[MAX_LOST_IN_A_ROW] に届いたら作り直しをやめる。 */
    private var lostInARow = 0
    /** エンジンを読み込む・使う・捨てるのを 1 つずつにする。 */
    private val engineLock = Mutex()
    private val inBackground = MutableStateFlow(false)

    private val previewState = MutableStateFlow<TtsVoice?>(null)
    /** 試聴中の声（設定画面）。 */
    val previewing: StateFlow<TtsVoice?> = previewState.asStateFlow()
    private var previewJob: Job? = null

    /** アプリが前に出た・裏に回った（[EventRepository.onForeground] / [EventRepository.onBackground] から）。 */
    fun onAppForeground() { inBackground.value = false }
    fun onAppBackground() { inBackground.value = true }

    /**
     * フォロー中のカラムが描く一覧（上が新しい）。一覧が変わるたびに呼ぶ（メインスレッド）。
     * 前に無かった投稿を、読み上げ中ならスロットへ入れる。OFF の間は覚えるだけ。
     */
    fun onFeedRendered(posts: List<NoteUi>) {
        val fresh = picker.pick(posts)
        if (fresh.isEmpty() || sleeping) return
        val s = slot ?: return
        var added = 0
        for (note in fresh) if (s.trySend(note).isSuccess) added++
        if (added > 0) statsState.update { it.copy(waiting = it.waiting + added) }
    }

    /** フォロー中のカラムが画面から消えた。次に描かれる一覧は「前からある投稿」として覚え、読まない。 */
    fun onFeedDetached() {
        picker.rebase()
    }

    /**
     * 設定画面の試聴: [voice] で [text] を読む（読み上げ中・試聴中は何もしない）。読み終えたらエンジンを片付ける。
     */
    fun preview(voice: TtsVoice, text: String) {
        if (job?.isActive == true || previewJob?.isActive == true) return
        previewJob = scope.launch {
            previewState.value = voice
            val e = engineFactory()
            try {
                if (e == null) {
                    failureEvents.tryEmit(Failure.UNSUPPORTED)
                } else {
                    e.load(voice)
                    e.speak(text)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                failureEvents.tryEmit(Failure.ENGINE)
            } finally {
                e?.close()
                previewState.value = null
            }
        }
    }

    /** 読み上げを始める（確認のあと）。すでに始めていれば何もしない。 */
    fun start() {
        if (job?.isActive == true) return
        previewJob?.cancel()
        // 止めた直後に始めたとき、前のジョブの片付け（エンジンを捨てて OFF にする）が新しいジョブを壊さないよう、終わるのを待つ
        val previous = job
        job = scope.launch {
            previous?.join()
            statsState.value = Stats()
            lostInARow = 0
            try {
                run()
            } finally {
                slot = null
                sleeping = false
                releaseEngine()
                statusState.value = Status.OFF
            }
        }
    }

    /** 読み上げをやめる（読んでいる途中でも止める）。エンジンも捨てる。 */
    fun stop() {
        engine?.stop()
        job?.cancel()
    }

    private suspend fun run() {
        statusState.value = Status.PREPARING
        val v = voice()
        if (!ready(v)) { failureEvents.tryEmit(Failure.NOT_DOWNLOADED); return }
        // ここより前に作られた投稿は読まない（遅れて届く分の猶予は付ける）。覚えている一覧はそのまま「前からある投稿」
        picker.restart(now() - NewPostPicker.GRACE_SEC)
        val queue = Channel<NoteUi>(Channel.UNLIMITED)
        slot = queue
        sleeping = false
        val matcher = MutableStateFlow(MuteMatcher.from(null))
        val mutesReady = CompletableDeferred<Unit>()
        coroutineScope {
            launch { mutes.collect { matcher.value = MuteMatcher.from(it); mutesReady.complete(Unit) } }
            // 裏に回ってしばらくしたらエンジンを捨てる。戻ったら、裏にいる間に届いた投稿は読まない（画面で見られる）
            launch {
                var released = false
                inBackground.collectLatest { bg ->
                    if (bg) {
                        delay(backgroundReleaseMs)
                        sleeping = true
                        engine?.stop()
                        engineLock.withLock { releaseEngine() }
                        clear(queue)
                        released = true
                    } else if (released) {
                        clear(queue)
                        picker.restart(now())
                        sleeping = false
                        released = false
                    }
                }
            }
            if (engineLock.withLock { loadEngine(v) } == null) { coroutineContext.cancelChildren(); return@coroutineScope }
            mutesReady.await()
            statusState.value = Status.ON
            for (note in queue) {
                statsState.update { it.copy(waiting = (it.waiting - 1).coerceAtLeast(0)) }
                // 裏でエンジンを捨てた後は読み込み直さない（前に戻るまで読まない）
                if (sleeping || (inBackground.value && engine == null)) continue
                if (note.event.pubkey == me() || matcher.value.muted(note)) continue
                // 名前を DB から引くので失敗しうる。1 件の失敗で読み上げ全体を止めない
                val text = try {
                    textOf(note)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    statsState.update { it.copy(failed = it.failed + 1, lastError = t.message ?: t.toString()) }
                    null
                }
                if (text == null) continue
                if (!speak(v, text)) { coroutineContext.cancelChildren(); return@coroutineScope }
            }
        }
    }

    /** スロットを空にする（裏に回ったとき）。 */
    private fun clear(queue: Channel<NoteUi>) {
        while (queue.tryReceive().isSuccess) Unit
        statsState.update { it.copy(waiting = 0) }
    }

    /**
     * 1 件読む。読めなくても次へ進む（false はエンジンを作り直せない・続けて落ちる、で読み上げを続けられないとき）。
     * エンジンが落ちたら作り直して読み直すが、続けて [MAX_LOST_IN_A_ROW] 回落ちたら諦める
     * （メモリが足りず毎回止められる端末で、投稿ごとに重い読み込みを繰り返さないため）。
     */
    private suspend fun speak(v: TtsVoice, text: String): Boolean {
        while (true) {
            val result = engineLock.withLock {
                val e = engine ?: loadEngine(v) ?: return false
                try {
                    e.speak(text)
                    null
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    // 落ちたエンジンは捨てる（次の投稿・作り直しで読み込み直す）
                    if (t is SpeechEngineLost) releaseEngine()
                    t
                }
            }
            when {
                result == null -> {
                    lostInARow = 0
                    statsState.update { it.copy(read = it.read + 1) }
                    return true
                }
                result is SpeechEngineLost -> {
                    statsState.update { it.copy(lastError = result.message ?: result.toString()) }
                    if (++lostInARow >= MAX_LOST_IN_A_ROW) {
                        failureEvents.tryEmit(Failure.ENGINE)
                        return false
                    }
                    // 作り直して同じ投稿を読み直す
                }
                else -> {
                    statsState.update { it.copy(failed = it.failed + 1, lastError = result.message ?: result.toString()) }
                    return true
                }
            }
        }
    }

    /** エンジンを作って声を読み込む（[engineLock] の中で呼ぶ）。できなければ理由を知らせて null。 */
    private suspend fun loadEngine(v: TtsVoice): SpeechEngine? {
        engine?.let { return it }
        val e = engineFactory() ?: run { failureEvents.tryEmit(Failure.UNSUPPORTED); return null }
        return try {
            e.load(v)
            engine = e
            e
        } catch (c: CancellationException) {
            e.close()
            throw c
        } catch (t: Throwable) {
            e.close()
            statsState.update { it.copy(lastError = t.message ?: t.toString()) }
            failureEvents.tryEmit(Failure.ENGINE)
            null
        }
    }

    private fun releaseEngine() {
        engine?.close()
        engine = null
    }

    private suspend fun textOf(note: NoteUi): String? {
        val body = note.text ?: note.event.content
        val names = SpeechText.personRefs(body).associateWith { bech32 ->
            runCatching { Nip19.mentionBechToHex(bech32) }.getOrNull()?.let { personName(it) }
        }
        return SpeechText.of(
            name = note.author.name,
            body = body,
            hasMedia = note.images.isNotEmpty(),
            contentWarning = note.contentWarning,
            personName = { names[it] },
        )
    }

    companion object {
        /**
         * 裏に回ってからエンジンを捨てるまで（ちょっと別のアプリを見て戻るくらいでは捨てない）。
         * Android は画面を消した（ロックした）ときも裏に回った扱い。どちらにしても裏では OS に通信を止められ、
         * 新しい投稿が届かないので、エンジンを持ち続ける意味が無い。
         */
        const val BACKGROUND_RELEASE_MS = 30_000L
        /** 続けてエンジンが落ちたら諦める回数（1 回目は作り直して読み直す）。 */
        const val MAX_LOST_IN_A_ROW = 2
    }
}

/**
 * [#948] カラムが描く一覧から「新しく出た投稿」を選ぶ（純粋な状態機械。テストする）。
 * 最初に渡された空でない一覧は読まずに覚えるだけ（[rebase] の後も同じ）。以後に現れた投稿のうち、
 * 作成時刻が [restart] で決めた時刻以降のものを古い順に返す（リレーの遅れで少し前の時刻の投稿が後から届くのは読む。
 * 過去の投稿を読み足したものは読まない）。リポストは読まない。
 */
class NewPostPicker(private val now: () -> Long) {
    /** これより前に作られた投稿は読まない。 */
    @Volatile private var from = 0L
    private val seen = LinkedHashSet<String>()
    /** 次に渡された空でない一覧を「前からある投稿」として覚える（まだ何も覚えていない・[rebase] の後）。 */
    @Volatile private var rebasing = true

    fun pick(feed: List<NoteUi>): List<NoteUi> {
        if (rebasing) {
            // 空の一覧（読み込み前）は基準にしない: 直後に埋まった一覧を全部「新しい」と見ないため
            if (feed.isEmpty()) return emptyList()
            seen.clear()
            feed.forEach { seen.add(keyOf(it)) }
            from = now() - GRACE_SEC
            rebasing = false
            return emptyList()
        }
        val fresh = feed.filter { keyOf(it) !in seen }
        fresh.forEach { seen.add(keyOf(it)) }
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        val from = from
        return fresh
            .filter { it.repostedBy == null && it.event.createdAt >= from }
            .sortedBy { it.event.createdAt }
    }

    /** 次に渡された一覧を「前からある投稿」として覚え直す（カラムが画面から消えて、また描かれるとき）。 */
    fun rebase() {
        rebasing = true
    }

    /**
     * ここから後に作られた投稿だけを読む（ON にしたとき・裏から戻ったとき）。裏から戻るときは猶予を付けない:
     * 裏にいる間は通信が止められ、その間の投稿は戻ってからまとめて届く。それは画面で見られるので読まない。
     */
    fun restart(since: Long) {
        from = since
    }

    private fun keyOf(note: NoteUi) = note.repostId ?: note.event.id

    companion object {
        /** リレーの遅れを見込む幅。届くのが数分遅れる投稿もあるので、ON にした時刻より少し前まで読む。 */
        const val GRACE_SEC = 180L
        const val MAX_SEEN = 5_000
    }
}
