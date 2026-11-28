package app.nostrdeck.model

/**
 * [#1024] nostr-yo の「Yo」。相手に「Yo」とだけ送る遊び。
 * 決まりは https://github.com/kojira/nostr-yo/blob/main/SPEC.md（2026-11-27 版。NIP ではなく nostr-yo の提案）。
 *
 * Yo は相手のプロフィール（kind:0）へのリアクション（NIP-25）で、絵文字は NIP-30 の `:yo:`:
 * ```
 * kind 7 / content ":yo:"
 * ["e", <相手の最新の kind:0 の id>, <リレーヒント>, <相手の公開鍵>]
 * ["p", <相手の公開鍵>, <リレーヒント>]
 * ["k", "0"]
 * ["emoji", "yo", <https の画像 URL>]
 * ["t", "yo"]
 * ```
 * ここは純関数だけ（判定・送るイベントの形・通知の束ね）。取得と発行は EventRepository が持つ。Web（#1025）と同じ規則。
 */
object Yo {
    const val KIND = 7
    const val CONTENT = ":yo:"
    const val SHORTCODE = "yo"

    /** 画面に出す印（文字。送り主が指定した画像は取りに行かない） */
    const val LABEL = "Yo"

    /**
     * 送る Yo の `emoji` の URL。nostr-yo と同じにする（nostr-yo の受信箱で「別の画像」と注記されない。
     * ほかのクライアントでも同じ絵で出る）。URL として載せるだけで、アプリには画像を入れない（リポジトリにライセンスが無い）。
     */
    const val IMAGE_URL = "https://kojira.github.io/nostr-yo/yo.png"

    /** `emoji` の URL の長さの上限（SPEC の条件 2） */
    const val MAX_URL_LENGTH = 2048

    /** これより先の未来の `created_at` は Yo とみなさない（SPEC の条件 6。10 分） */
    const val MAX_FUTURE_SEC = 600L

    /** 送る前に相手の最新の kind:0 を取りに行く待ち時間。全リレーの EOSE が揃えばそこで打ち切る */
    const val FETCH_TIMEOUT_MS = 5_000L

    /** 1 件届いてから、ほかのリレーにもっと新しい版が無いか待つ時間（応答しないリレーのために毎回上限まで待たせない） */
    const val FETCH_SETTLE_MS = 1_200L

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    /**
     * [event] が Yo なら、受け取る人の公開鍵。Yo でなければ null。
     * SPEC の「receiver が Yo とみなす条件」を全部見る（id と署名は取り込みのときに確かめてある）。nostr-yo の実装と同じ判定:
     *  1. kind:7 で、本文がちょうど `:yo:`
     *  2. `emoji` タグがちょうど 1 つ。名前は `yo`、URL は [isAcceptableEmojiUrl]
     *  3. `p` タグがちょうど 1 つ（それが受け取る人）
     *  4. `e` タグがちょうど 1 つで、64 桁の 16 進数。公開鍵のヒント（4 つ目）があれば、受け取る人
     *  5. `k` タグがちょうど 1 つで `0`。`a` タグは無い
     *  6. `created_at` が [now] の 10 分より先ではない
     * `t=yo` は見ない（付いていなくても Yo。付いているだけでは Yo ではない）。
     */
    fun recipientOf(event: NostrEvent, now: Long): String? {
        if (event.kind != KIND || event.content != CONTENT) return null
        if (event.createdAt > now + MAX_FUTURE_SEC) return null
        fun named(name: String) = event.tags.filter { it.firstOrNull() == name }
        val emoji = named("emoji").singleOrNull() ?: return null
        if (emoji.getOrNull(1) != SHORTCODE || !isAcceptableEmojiUrl(emoji.getOrNull(2).orEmpty())) return null
        val recipient = named("p").singleOrNull()?.getOrNull(1)?.takeIf { HEX64.matches(it) } ?: return null
        if (named("k").singleOrNull()?.getOrNull(1) != "0") return null
        val target = named("e").singleOrNull() ?: return null
        if (target.getOrNull(1)?.let { HEX64.matches(it) } != true) return null
        val authorHint = target.getOrNull(3).orEmpty()
        if (authorHint.isNotEmpty() && authorHint != recipient) return null
        if (named("a").isNotEmpty()) return null
        return recipient
    }

    /**
     * `emoji` の URL として認めるか: `https://` で、ホストがあり、認証情報（`user:pass@`）を含まず、[MAX_URL_LENGTH] 文字まで。
     * 空白・制御文字を含むものは認めない。**この URL は取りに行かない**（判定に使うだけ）。
     */
    fun isAcceptableEmojiUrl(url: String): Boolean {
        if (url.length > MAX_URL_LENGTH) return false
        if (url.any { it <= ' ' || it == '\u007f' }) return false
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val authority = url.substring("https://".length).takeWhile { it != '/' && it != '?' && it != '#' }
        if ('@' in authority || '\\' in authority) return false
        // ホスト（IPv6 は [..]）。ポートを除いて空でないこと
        val host = if (authority.startsWith("[")) authority.substringBefore(']', "").drop(1) else authority.substringBefore(':')
        return host.isNotEmpty()
    }

    /**
     * 届いた候補のうち、[author] の・署名の通る・いちばん新しい kind:0（Yo の対象）。無ければ null。
     * 同じ `created_at` なら id の小さい方（NIP-01。nostr-yo と同じ）。
     */
    fun latestProfile(candidates: List<NostrEvent>, author: String, verify: (NostrEvent) -> Boolean): NostrEvent? =
        candidates.asSequence()
            .filter { it.kind == 0 && it.pubkey == author }
            .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
            .firstOrNull(verify)

    /**
     * 送る Yo（署名前）。[profileEventId] は相手の最新の kind:0 の id、[hint] はそれが見つかるリレー（無ければ空）。
     * [createdAt] は 0 なら署名のときに今の時刻が入る。
     */
    fun unsigned(recipient: String, profileEventId: String, hint: String = "", createdAt: Long = 0): UnsignedEvent {
        require(HEX64.matches(recipient) && HEX64.matches(profileEventId)) { "invalid Yo target" }
        return UnsignedEvent(
            kind = KIND,
            content = CONTENT,
            tags = listOf(
                listOf("e", profileEventId, hint, recipient),
                listOf("p", recipient, hint),
                listOf("k", "0"),
                listOf("emoji", SHORTCODE, IMAGE_URL),
                listOf("t", SHORTCODE),
            ),
            createdAt = createdAt,
        )
    }

    /**
     * 返す Yo の `created_at`。受け取った Yo（[receivedAt]。無ければ null）より前にしない。
     * 相手の時計が進んでいると、すぐ返した Yo が受け取った Yo より「前」になり、返したことにならないため。
     * 未来へ進めるのは [MAX_FUTURE_SEC] まで（それより先だと、こちらの Yo が Yo とみなされなくなる）。
     */
    fun replyCreatedAt(now: Long, receivedAt: Long?): Long =
        if (receivedAt == null) now else maxOf(now, minOf(receivedAt, now + MAX_FUTURE_SEC))

    /** リアクションの対象がプロフィール（kind:0）か（`k` タグで見る。Yo に限らない） */
    fun reactsToProfile(tags: List<List<String>>): Boolean =
        tags.any { it.size >= 2 && it[0] == "k" && it[1] == "0" }

    /**
     * 通知の束ね: 同じ人からの Yo を 1 行にする（Yo は続けて届きやすく、1 件ずつ出すと通知が埋まる）。
     * いちばん新しい Yo を残して件数を載せ、こちらがそれ以降に Yo を送っていれば「返した」にする。
     * [myLatestYoTo] = 相手の公開鍵 → 自分がその人へ送ったいちばん新しい Yo の時刻。Yo 以外の行はそのまま（順番は呼び出し側で並べる）。
     */
    fun collapse(items: List<NotificationUi>, myLatestYoTo: Map<String, Long>): List<NotificationUi> {
        val (yos, others) = items.partition { it.kind == NotificationKind.YO }
        if (yos.isEmpty()) return items
        val rows = yos.groupBy { it.actor.pubkey }.map { (actor, group) ->
            val newest = group.maxWith(compareBy<NotificationUi> { it.createdAt }.thenBy { it.id })
            newest.copy(yoCount = group.size, yoReplied = (myLatestYoTo[actor] ?: Long.MIN_VALUE) >= newest.createdAt)
        }
        return others + rows
    }

    /**
     * この Yo の行に、もう返したか。通知の組み直し（[NotificationUi.yoReplied]）を待つと送った直後に間が空くので、
     * このセッションで送った時刻（[sentAt]。相手 → 送った Yo の created_at）が受け取った Yo 以降なら、それも返したものとする。
     */
    fun isReplied(n: NotificationUi, sentAt: Map<String, Long>): Boolean =
        n.yoReplied || (sentAt[n.actor.pubkey] ?: Long.MIN_VALUE) >= n.createdAt

    /** まだ返していない Yo の行の数（相手の数。同じ人の Yo は 1 行にまとまっている）。通知の見出しに出す。 */
    fun unrepliedCount(items: List<NotificationUi>, sentAt: Map<String, Long>): Int =
        items.count { it.kind == NotificationKind.YO && !isReplied(it, sentAt) }
}
