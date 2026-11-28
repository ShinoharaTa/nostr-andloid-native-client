package app.nostrdeck.model

/**
 * [#1021] 聴いている曲。端末で再生中の曲を読んだもの（Android / iOS）か、音楽アプリの「共有」で受け取ったもの。
 * 読むのは利用者がボタンを押したときだけで、出すのも利用者が選んだときだけ（自動では出さない）。
 */
data class NowPlayingTrack(
    val title: String,
    val artist: String? = null,
    /** 曲の長さ（秒）。取れなければ null（ラジオなど）。 */
    val durationSec: Long? = null,
    /** いまの再生位置（秒）。取れなければ null。 */
    val positionSec: Long? = null,
    /** 聴いているアプリ（検索のリンクを作るのに使う。知らないアプリは null）。 */
    val app: NowPlaying.App? = null,
    /** 曲そのもののリンク（共有で受け取ったとき）。 */
    val url: String? = null,
)

/**
 * [#1021] Now Playing: 聴いている曲を、自分のステータス（NIP-38 / kind:30315 の `d=music`）か投稿にする。
 * 手で打つ 1 行のステータス（`d=general`。[UserStatuses.generalEvent]）とは別のもので、互いに上書きしない。
 */
object NowPlaying {
    /** 本文（曲名 - アーティスト）の上限。 */
    const val MAX_LENGTH = 200

    /** 期限は「曲が終わる時刻 + [EXPIRY_MARGIN_SEC]」。ただし [MIN_EXPIRY_SEC] 〜 [MAX_EXPIRY_SEC] に収める。 */
    const val EXPIRY_MARGIN_SEC = 5L
    const val MIN_EXPIRY_SEC = 60L
    const val MAX_EXPIRY_SEC = 15 * 60L

    /** 長さが取れない曲（ラジオ・共有で受け取った曲）の期限。 */
    const val UNKNOWN_LENGTH_EXPIRY_SEC = 10 * 60L

    /** 投稿に付けるハッシュタグ。 */
    const val HASHTAG = "#NowPlaying"

    /** 検索のリンクを作れるアプリ。 */
    enum class App { SPOTIFY, YOUTUBE_MUSIC, APPLE_MUSIC }

    /** Android のパッケージ名 → アプリ。知らないアプリは null（リンクを付けない）。 */
    fun appOfPackage(packageName: String): App? = when (packageName) {
        "com.spotify.music" -> App.SPOTIFY
        "com.google.android.apps.youtube.music" -> App.YOUTUBE_MUSIC
        "com.apple.android.music" -> App.APPLE_MUSIC
        else -> null
    }

    /** 「曲名 - アーティスト」（NIP-38 の例と同じ並び）。アーティストが無ければ曲名だけ。1 行にして [MAX_LENGTH] で切る。 */
    fun label(track: NowPlayingTrack): String {
        val title = UserStatuses.oneLine(track.title)
        val artist = track.artist?.let { UserStatuses.oneLine(it) }.orEmpty()
        return cut(if (artist.isEmpty()) title else "$title - $artist")
    }

    /** [MAX_LENGTH] で切る（絵文字などのサロゲートペアを途中で割らない）。 */
    private fun cut(text: String): String {
        if (text.length <= MAX_LENGTH) return text
        val end = if (text[MAX_LENGTH - 1].isHighSurrogate()) MAX_LENGTH - 1 else MAX_LENGTH
        return text.substring(0, end).trimEnd()
    }

    /**
     * ステータスの期限（NIP-40。unix 秒）。曲が終わる時刻（今 + 残りの時間 + [EXPIRY_MARGIN_SEC]）。
     * 短すぎ・長すぎにならないよう [MIN_EXPIRY_SEC] 〜 [MAX_EXPIRY_SEC] に収める（曲の終わり際に押してもすぐ消えない。
     * 長い曲・止めたままの曲がいつまでも残らない）。長さが取れなければ [UNKNOWN_LENGTH_EXPIRY_SEC]。
     */
    fun expiration(track: NowPlayingTrack, now: Long): Long {
        val duration = track.durationSec?.takeIf { it > 0 } ?: return now + UNKNOWN_LENGTH_EXPIRY_SEC
        val position = (track.positionSec ?: 0L).coerceIn(0L, duration)
        return now + (duration - position + EXPIRY_MARGIN_SEC).coerceIn(MIN_EXPIRY_SEC, MAX_EXPIRY_SEC)
    }

    /**
     * ステータスに付けるリンク（`r`）。曲そのもののリンクがあればそれ。無ければ、聴いているアプリの検索のリンク。
     * 知らないアプリは付けない。
     */
    fun link(track: NowPlayingTrack): String? =
        track.url?.takeIf { isHttpUrl(it) } ?: track.app?.let { searchLink(it, label(track)) }

    /** アプリごとの検索のリンク。 */
    fun searchLink(app: App, query: String): String = when (app) {
        App.SPOTIFY -> "https://open.spotify.com/search/${percentEncode(query)}"
        App.YOUTUBE_MUSIC -> "https://music.youtube.com/search?q=${percentEncode(query)}"
        App.APPLE_MUSIC -> "https://music.apple.com/search?term=${percentEncode(query)}"
    }

    /**
     * 聴いている曲のステータス（`d=music`）。[replacing] は、いま出している `music` の版の created_at（あれば）。
     * 置き換え可能イベントは新しい方が残るので、created_at はそれより後にする（[UserStatuses.generalEvent] と同じ）。
     */
    fun statusEvent(track: NowPlayingTrack, now: Long, replacing: Long? = null): UnsignedEvent = UnsignedEvent(
        kind = UserStatuses.KIND, content = label(track),
        tags = listOfNotNull(
            listOf("d", UserStatuses.Type.MUSIC.d),
            listOf("expiration", expiration(track, now).toString()),
            link(track)?.let { listOf("r", it) },
        ),
        createdAt = maxOf(now, (replacing ?: 0L) + 1),
    )

    /** `music` のステータスを消す（NIP-38: 空の本文でクリア。期限もリンクも付けない）。 */
    fun clearEvent(now: Long, replacing: Long? = null): UnsignedEvent = UnsignedEvent(
        kind = UserStatuses.KIND, content = "",
        tags = listOf(listOf("d", UserStatuses.Type.MUSIC.d)),
        createdAt = maxOf(now, (replacing ?: 0L) + 1),
    )

    /**
     * 投稿の文面「#NowPlaying 曲名 - アーティスト」。曲そのもののリンクがあれば次の行に付ける
     * （検索のリンクは付けない。投稿ではカードになって場所を取るだけなので）。
     */
    fun postText(track: NowPlayingTrack): String {
        val line = "$HASHTAG ${label(track)}"
        return track.url?.takeIf { isHttpUrl(it) }?.let { "$line\n$it" } ?: line
    }

    // ---- 音楽アプリの「共有」で受け取った文字 ----

    private val URL_IN_TEXT = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    private val HOST_OF = Regex("^https?://([^/?#]*)", RegexOption.IGNORE_CASE)

    private fun isHttpUrl(url: String): Boolean = hostOf(url) != null

    private fun hostOf(url: String): String? =
        HOST_OF.find(url)?.groupValues?.get(1)?.substringAfterLast('@')?.substringBefore(':')?.lowercase()?.takeIf { it.isNotEmpty() }

    /** 音楽サービスのホストか（動画の YouTube は含めない。動画の共有は投稿として扱う）。 */
    private fun isMusicHost(host: String): Boolean =
        host == "open.spotify.com" || host == "spotify.link" ||
            host == "music.youtube.com" ||
            host == "music.apple.com" ||
            host == "music.amazon.com" || host.startsWith("music.amazon.") ||
            host == "soundcloud.com" || host == "on.soundcloud.com" || host == "m.soundcloud.com" ||
            host == "bandcamp.com" || host.endsWith(".bandcamp.com")

    /** 共有された文字の中の、音楽サービスのリンク（最初の 1 つ）。無ければ null（ふつうの共有として投稿欄へ）。 */
    fun musicLinkIn(text: String): String? =
        URL_IN_TEXT.findAll(text).map { it.value.trimEnd('.', ',', ')', '」', '。', '、') }
            .firstOrNull { url -> hostOf(url)?.let { isMusicHost(it) } == true }

    /** リンクのホスト → アプリ（検索のリンク用ではなく、共有された曲がどのサービスのものかを覚えておくため）。 */
    fun appOfUrl(url: String): App? = when (hostOf(url)) {
        "open.spotify.com", "spotify.link" -> App.SPOTIFY
        "music.youtube.com" -> App.YOUTUBE_MUSIC
        "music.apple.com" -> App.APPLE_MUSIC
        else -> null
    }

    /**
     * 共有された文字から、曲名の候補（リンク以外の部分を 1 行にしたもの）。リンクだけが共有されたときは空
     * （呼ぶ側がリンク先のタイトルを取りに行く）。
     */
    fun sharedLabel(text: String, url: String): String = cut(UserStatuses.oneLine(text.replace(url, " ")))

    /**
     * 共有で受け取った曲。曲名は利用者が直した 1 行（[label]）をそのまま使う（アーティストは分けない）。
     * 長さは分からないので、期限は [UNKNOWN_LENGTH_EXPIRY_SEC]。
     */
    fun sharedTrack(label: String, url: String): NowPlayingTrack =
        NowPlayingTrack(title = label, url = url, app = appOfUrl(url))

    /** URL のパス・クエリに入れる文字列のエンコード（UTF-8。英数字と `-_.~` 以外は %XX）。 */
    private fun percentEncode(text: String): String = buildString {
        for (b in text.encodeToByteArray()) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                append(ch)
            } else {
                append('%')
                append("0123456789ABCDEF"[c shr 4])
                append("0123456789ABCDEF"[c and 0x0F])
            }
        }
    }
}
