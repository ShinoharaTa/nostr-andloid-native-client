package app.nostrdeck.data

/**
 * [#955] 1 本のカラムについて、リレーごとに「どの時刻まで遡って取れたか」を覚え、
 * 画面に並べてよい下限（[floor]）と、次に遡って聞くリレー（[olderRequests]）を決める。
 *
 * リレーごとに同じ件数（limit）で聞くと、投稿の多いリレーは 1 時間分、少ないリレーは 5 時間分、のように
 * 取れる幅が違う。いちばん古い投稿の時刻から全リレーへ続きを聞くと、多いリレーの 1〜5 時間前が抜ける。
 * そこで、まだ先があるリレーのうちいちばん浅いところで止まっているリレーの境界より古い投稿は出さず、
 * 遡るときは、リレーごとに自分の境界から聞く。
 *
 * 状態を持つだけで通信はしない（呼ぶ側が直列に呼ぶこと）。
 */
class TimelineCoverage {

    private class Relay {
        /** このリレーから届いた中でいちばん古い created_at。 */
        var oldest: Long? = null
        /** 最初の購読（EOSE まで）で届いた件数。EOSE のときに 0 なら、このリレーには何も無い。 */
        var initialReceived = 0
        var initialDone = false
        /** 遡りの要求を出して返事を待っている。 */
        var olderInFlight = false
        /** 出した遡りの要求の番号。前の要求の遅れた返事（EOSE・時間切れ）を、今の要求のものと取り違えないため。 */
        var olderSeq = 0
        /** 遡りの要求を出したときの [oldest]。返事でこれより古くならなければ、もう先が無い。 */
        var oldestAtRequest: Long? = null
        /** もう遡れない（何も無い・遡っても古くならなかった）。下限の計算から外す。 */
        var exhausted = false
        /** 遡りの要求に何も返さなかった。止まらないよう下限の計算から外す（届いたら戻す）。 */
        var unresponsive = false
    }

    private val relays = mutableMapOf<String, Relay>()

    /** イベントが届いた。[older] は遡りの要求への返事か（false = カラムの購読そのもの）。 */
    fun onEvent(relay: String, createdAt: Long, older: Boolean) {
        val r = relays.getOrPut(relay) { Relay() }
        r.oldest = r.oldest?.let { minOf(it, createdAt) } ?: createdAt
        if (older) r.unresponsive = false
        else if (!r.initialDone) r.initialReceived++
    }

    /** EOSE が届いた。[olderSeq] は遡りの要求への返事ならその番号（[olderRequests] が返したもの）、カラムの購読なら null。 */
    fun onEose(relay: String, olderSeq: Int?) {
        val r = relays.getOrPut(relay) { Relay() }
        if (olderSeq != null) {
            if (!r.olderInFlight || olderSeq != r.olderSeq) return
            r.olderInFlight = false
            val before = r.oldestAtRequest
            val after = r.oldest
            if (before == null || after == null || after >= before) r.exhausted = true
        } else if (!r.initialDone) {
            r.initialDone = true
            if (r.initialReceived == 0) r.exhausted = true
        }
    }

    /** 遡りの要求（[olderSeq]）に、待つ時間内に EOSE が来なかった。何も届いていなければ応えないリレーとして外す。 */
    fun onOlderTimeout(relay: String, olderSeq: Int) {
        val r = relays[relay] ?: return
        if (!r.olderInFlight || olderSeq != r.olderSeq) return
        r.olderInFlight = false
        if (r.oldest == r.oldestAtRequest) r.unresponsive = true
    }

    /**
     * 画面に並べてよい下限（これより古い投稿は、どこかのリレーの分が抜けているかもしれない）。
     * まだ先があるリレーの境界のうち、いちばん新しいもの。そういうリレーが無ければ null（下限なし）。
     */
    fun floor(): Long? = relays.values
        .filter { !it.exhausted && !it.unresponsive }
        .mapNotNull { it.oldest }
        .maxOrNull()

    /** 遡りの要求。[until] はそのリレーの境界、[seq] は返事（[onEose] / [onOlderTimeout]）に添える番号。 */
    data class OlderRequest(val until: Long, val seq: Int)

    /**
     * 次に遡って聞くリレーと、その要求。聞くと決めたリレーは返事待ちにする。
     * 聞くのは、下限から「いまから下限までの幅」ぶん深いところまでに境界があるリレー（下限の 2 倍の深さまで）。
     * 下限で止まっているリレーは必ず入る。ずっと深くまで取れているリレーには、下限が追いつくまで聞かない（取りすぎない）。
     */
    fun olderRequests(now: Long): Map<String, OlderRequest> {
        val floor = floor() ?: return emptyMap()
        val reach = floor - (now - floor).coerceAtLeast(0)
        return relays.filter { (_, r) -> !r.exhausted && !r.olderInFlight && (r.oldest ?: Long.MIN_VALUE) >= reach }
            .mapValues { (_, r) ->
                r.olderInFlight = true
                r.oldestAtRequest = r.oldest
                OlderRequest(until = r.oldest!!, seq = ++r.olderSeq)
            }
    }

    /** 読み込み先から外れたリレー（もう聞かないので、下限の計算に残さない）。 */
    fun dropRelay(relay: String) {
        relays.remove(relay)
    }

    /** 返事を待っている遡りの要求があるか（表示の「読み込み中」用）。 */
    fun loadingOlder(): Boolean = relays.values.any { it.olderInFlight }
}
