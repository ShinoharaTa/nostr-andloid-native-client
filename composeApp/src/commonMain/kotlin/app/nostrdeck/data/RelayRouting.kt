package app.nostrdeck.data

/**
 * REQ の配信先の判定（EventRepository の購読の振り分けに使う純関数）。
 *
 * 購読には「配信先を限定しない」（[targets] が null。自分のリスト・プロフィール取得など）と、
 * 「限定する」（読み込みリレーだけ・検索用リレー・チャンネルのリレー等）がある。
 * 限定した購読は、後から繋いだリレーや張り直しのときも対象のリレーにだけ送る。
 */
internal object RelayRouting {
    /** その購読をこのリレーへ送るか。 */
    fun sendsTo(relay: String, targets: Set<String>?): Boolean = targets == null || relay in targets

    /** 読み込みリレーが [old] → [new] に変わったとき、CLOSE するリレー（first）と REQ を送るリレー（second）。 */
    fun retarget(old: Set<String>, new: Set<String>): Pair<Set<String>, Set<String>> = (old - new) to (new - old)
}
