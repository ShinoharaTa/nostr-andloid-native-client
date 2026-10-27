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
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [#948] フォロー中フィードの読み上げ。ON にした後に届いた投稿を、古い順に 1 件ずつ読む。
 *  - ON にした時点でフィードにあった投稿は読まない（[NewPostPicker]）
 *  - リポスト・自分の投稿・ミュート対象は読まない
 *  - 読み上げ待ちは [MAX_QUEUE] 件まで。あふれたら古いものから捨てる（追いつけないときは新しいものを優先）
 *  - ON / OFF は端末に覚えない（起動したら OFF）
 */
class TimelineReader(
    private val scope: CoroutineScope,
    /** その声のモデルをダウンロード済みか（[TtsModels.isReady]）。 */
    private val ready: (TtsVoice) -> Boolean,
    private val voice: () -> TtsVoice,
    private val posts: Flow<List<NoteUi>>,
    private val mutes: Flow<MuteList?>,
    private val names: Flow<Map<String, String>>,
    private val me: () -> String?,
    private val engineFactory: () -> SpeechEngine? = ::createSpeechEngine,
    private val now: () -> Long = ::currentUnixTime,
) {
    enum class Status { OFF, PREPARING, ON }

    /** ON にできなかった理由（画面にトーストで出す）。 */
    enum class Failure { NOT_DOWNLOADED, UNSUPPORTED, ENGINE }

    private val statusState = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = statusState.asStateFlow()

    private val failureEvents = MutableSharedFlow<Failure>(extraBufferCapacity = 4)
    val failures: SharedFlow<Failure> = failureEvents.asSharedFlow()

    private var job: Job? = null
    private var engine: SpeechEngine? = null

    private val previewState = MutableStateFlow<TtsVoice?>(null)
    /** 試聴中の声（設定画面）。 */
    val previewing: StateFlow<TtsVoice?> = previewState.asStateFlow()
    private var previewJob: Job? = null

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
        job = scope.launch {
            try {
                run()
            } finally {
                engine?.close()
                engine = null
                statusState.value = Status.OFF
            }
        }
    }

    /** 読み上げをやめる（読んでいる途中でも止める）。 */
    fun stop() {
        engine?.stop()
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        statusState.value = Status.PREPARING
        val v = voice()
        if (!ready(v)) { failureEvents.tryEmit(Failure.NOT_DOWNLOADED); return }
        val e = engineFactory() ?: run { failureEvents.tryEmit(Failure.UNSUPPORTED); return }
        engine = e
        try {
            e.load(v)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            failureEvents.tryEmit(Failure.ENGINE)
            return
        }
        val queue = Channel<String>(MAX_QUEUE, BufferOverflow.DROP_OLDEST)
        val picker = NewPostPicker(since = now())
        val primed = CompletableDeferred<Unit>()
        coroutineScope {
            launch {
                combine(posts, mutes, names) { p, m, n -> Triple(p, MuteMatcher.from(m), n) }.collect { (feed, matcher, nameMap) ->
                    val mine = me()
                    picker.pick(feed)
                        .filterNot { it.event.pubkey == mine || matcher.muted(it) }
                        .forEach { note -> textOf(note, nameMap)?.let { queue.trySend(it) } }
                    primed.complete(Unit)
                }
            }
            // いまのフィードを覚え終えてから ON にする（ON の直後に届いた投稿を「前からある投稿」として読み飛ばさない）
            primed.await()
            statusState.value = Status.ON
            for (text in queue) {
                try {
                    e.speak(text)
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    // 1 件読めなくても続ける（次の投稿へ）
                }
            }
        }
    }

    private fun textOf(note: NoteUi, nameMap: Map<String, String>): String? = SpeechText.of(
        name = note.author.name,
        body = note.text ?: note.event.content,
        hasMedia = note.images.isNotEmpty(),
        personName = { bech32 -> runCatching { Nip19.mentionBechToHex(bech32) }.getOrNull()?.let { nameMap[it] } },
    )

    companion object {
        const val MAX_QUEUE = 5
    }
}

/**
 * [#948] フィードから「読み上げる新しい投稿」を選ぶ（純粋な状態機械。テストする）。
 * 最初に渡されたフィードは読まずに覚えるだけ。以後に現れた投稿のうち、作成時刻が [since] − [GRACE_SEC] 以降のものを
 * 古い順に返す（リレーの遅れで少し前の時刻の投稿が後から届くのは読む。過去の投稿を読み足したものは読まない）。
 * リポストは読まない。
 */
class NewPostPicker(private val since: Long) {
    private val seen = LinkedHashSet<String>()
    private var primed = false

    fun pick(feed: List<NoteUi>): List<NoteUi> {
        val fresh = feed.filter { keyOf(it) !in seen }
        fresh.forEach { seen.add(keyOf(it)) }
        while (seen.size > MAX_SEEN) seen.remove(seen.first())
        if (!primed) {
            primed = true
            return emptyList()
        }
        return fresh
            .filter { it.repostedBy == null && it.event.createdAt >= since - GRACE_SEC }
            .sortedBy { it.event.createdAt }
    }

    private fun keyOf(note: NoteUi) = note.repostId ?: note.event.id

    companion object {
        const val GRACE_SEC = 60L
        const val MAX_SEEN = 5_000
    }
}
