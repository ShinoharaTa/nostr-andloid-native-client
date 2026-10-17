package app.nostrdeck.data

/**
 * [#887] NIP-42 AUTH の、リレーごとの状態。純粋なデータで、コルーチンもソケットも触らない
 * （EventRepository が relayDispatcher 上でだけ触る。判断の規則はここに閉じ、テストで固定する）。
 *
 * 規則:
 * - チャレンジは接続ごとに 1 回しか来ないので、応答できなかったもの（署名できない・ポリシーで見送った）も
 *   捨てずに持ち、条件が変わったら（ログイン・ポリシー変更・自分のリレー / DM リレーが分かった）応答する。
 * - 「応答済み」は kind:22242 を送った時点ではなく **OK true を受けてから**。署名に失敗したら未送信に戻す。
 *   OK false（invalid / restricted）は同じチャレンジに再送しない（同じ署名を送り直しても通らない）。
 * - auth-required で弾かれた EVENT・閉じられた REQ は覚えておき、成立後に送り直す。
 * - 切断したら全部忘れる（チャレンジは接続に紐付く）。
 */
class RelayAuthTracker {

    data class Status(
        /** この接続で受けた最新のチャレンジ。まだ無ければ null */
        val challenge: String? = null,
        /** 署名を待っている（二重に署名しない） */
        val signing: Boolean = false,
        /** 送った kind:22242 の id。未送信なら null */
        val sentId: String? = null,
        /** 成立した（OK true を受けた） */
        val authed: Boolean = false,
        /** ポリシーで見送った（条件が変われば応答する） */
        val skipped: Boolean = false,
        /** 直近の失敗（署名できない・OK false の理由・OK が来ない） */
        val failure: String? = null,
        /** auth-required で弾かれた EVENT の id（成立後に送り直す） */
        val deferredPublishes: Set<String> = emptySet(),
        /** auth-required で閉じられた REQ の subId（成立後の張り直しは 1 回だけ） */
        val closedSubs: Set<String> = emptySet(),
    )

    private val byRelay = HashMap<String, Status>()

    fun status(url: String): Status? = byRelay[url]

    private inline fun update(url: String, f: (Status) -> Status) {
        byRelay[url] = f(byRelay[url] ?: Status())
    }

    /** チャレンジを受けた。新しいチャレンジなら true（応答を試みる）。同じものの再送なら false。 */
    fun onChallenge(url: String, challenge: String): Boolean {
        val prev = byRelay[url]
        if (prev?.challenge == challenge) return false
        // 保留中の再送は持ち越す（チャレンジが変わっても、弾かれた EVENT は成立後に送りたい）
        byRelay[url] = Status(
            challenge = challenge,
            deferredPublishes = prev?.deferredPublishes.orEmpty(),
            closedSubs = prev?.closedSubs.orEmpty(),
        )
        return true
    }

    /** いま応答すべきチャレンジ。受けていない・署名中・送信済み・成立済みなら null。 */
    fun challengeToAnswer(url: String): String? {
        val s = byRelay[url] ?: return null
        if (s.signing || s.sentId != null || s.authed) return null
        return s.challenge
    }

    /** ポリシーで見送った（チャレンジは保持する）。 */
    fun onSkipped(url: String) = update(url) { it.copy(skipped = true) }

    /** 署名を始めた。 */
    fun onSigning(url: String) = update(url) { it.copy(signing = true, skipped = false) }

    /** 署名できず送れなかった。未送信に戻す（次の契機で同じチャレンジに応答する）。 */
    fun onSignFailed(url: String, reason: String) = update(url) { it.copy(signing = false, sentId = null, failure = reason) }

    /** kind:22242 を送った（OK 待ち）。 */
    fun onSent(url: String, eventId: String) = update(url) { it.copy(signing = false, sentId = eventId, skipped = false, failure = null) }

    /** OK が返ってこなかった。送信済みのままにする（同じチャレンジへ自動では再送しない）。 */
    fun onNoOk(url: String) = update(url) { it.copy(failure = "no OK") }

    /**
     * OK を受けた。送った kind:22242 への OK なら状態を更新して true（受理確認の経路には流さない）。
     * ほかの EVENT への OK なら false。
     */
    fun onOk(url: String, eventId: String, accepted: Boolean, message: String): Boolean {
        val s = byRelay[url] ?: return false
        if (s.sentId != eventId) return false
        byRelay[url] = if (accepted) s.copy(authed = true, failure = null) else s.copy(failure = message.ifBlank { "rejected" })
        return true
    }

    /** EVENT が auth-required で弾かれた。成立後に送り直す。 */
    fun onPublishAuthRequired(url: String, eventId: String) =
        update(url) { it.copy(deferredPublishes = it.deferredPublishes + eventId) }

    /** 成立後に送り直す EVENT の id を取り出す（取り出したら空にする）。 */
    fun takeDeferredPublishes(url: String): Set<String> {
        val s = byRelay[url] ?: return emptySet()
        byRelay[url] = s.copy(deferredPublishes = emptySet())
        return s.deferredPublishes
    }

    /** REQ が auth-required で閉じられた。この接続で初めてなら true（成立後に 1 回だけ張り直す）。 */
    fun onClosedAuthRequired(url: String, subId: String): Boolean {
        val s = byRelay[url] ?: Status()
        val first = subId !in s.closedSubs
        byRelay[url] = s.copy(closedSubs = s.closedSubs + subId)
        return first
    }

    /** 切断した。チャレンジは接続に紐付くので全部忘れる。 */
    fun onDisconnected(url: String) {
        byRelay.remove(url)
    }

    /** 応答すべきチャレンジを持つリレー（見送り・署名失敗を含む）。条件が変わったときに回す。 */
    fun pending(): List<String> = byRelay.keys.filter { challengeToAnswer(it) != null }

    /** 接続モニタ用の 1 語。チャレンジを受けていなければ null。 */
    fun describe(url: String): String? {
        val s = byRelay[url] ?: return null
        return when {
            s.authed -> "ok"
            s.failure != null -> "failed: ${s.failure}"
            s.sentId != null -> "waiting OK"
            s.signing -> "signing"
            s.skipped -> "skipped (policy)"
            s.challenge != null -> "challenged"
            else -> null
        }
    }

    companion object {
        /** NIP-42 の機械可読の接頭辞（OK false / CLOSED の理由）。 */
        fun isAuthRequired(message: String): Boolean = message.trimStart().startsWith("auth-required:")
    }
}
