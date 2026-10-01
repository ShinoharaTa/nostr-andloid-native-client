package app.nostrdeck.data

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.dTag

/**
 * [#475][#478] 自分の置換可能イベント（kind:0 / 3 / 10000 番台 / 30000 番台）を書き換える直前の「取り直し」の規則。
 *
 * 手元の版は、起動直後・リレー未接続・キャッシュ消去後などはまだ受け取っていない（空）か古いことがある。
 * その版に 1 件足して発行すると、リレー上の最新のリストを上書きして中身を消してしまう。そこで発行の直前に
 * read ∪ write ∪ インデクサへ取りに行き（Web の refetchOwnReplaceable と同じ規則・待ち時間）、
 *  - どのリレーからも応答が無ければ [Result.Unreachable]（呼び出し側は発行しない）
 *  - 応答があれば [Result.Reached]（届いた最新版。どこにも無ければ null = まだリストを作っていない）
 * とする。ここは純関数だけを置き、REQ の発行と待ち合わせは EventRepository が持つ。
 */
internal object OwnRefetch {
    /** 取り直しの待ち時間（Web の OWN_REPLACEABLE_REFETCH_MS と同じ）。全リレーの EOSE が揃えばそこで打ち切る */
    const val TIMEOUT_MS = 5_000L

    /** 取り直した版が通常の取り込み経路で手元の状態に反映されるまで待つ上限 */
    const val CATCH_UP_MS = 3_000L

    sealed interface Result {
        /** どのリレーからも応答（EOSE / イベント）が無かった。手元の版が古い可能性があるので発行しない */
        data object Unreachable : Result

        /** 少なくとも 1 つのリレーが応答した。[latest] は届いた最新版（無ければ null） */
        data class Reached(val latest: NostrEvent?) : Result
    }

    /**
     * 届いた候補のうち、自分の・その kind の・（addressable なら）d が一致する・署名の通る最新版。
     * 同じ created_at なら先に届いた方（NIP-01 の「同時刻は id の小さい方」は手元の版の比較で扱わない）。
     */
    fun latest(
        candidates: List<NostrEvent>,
        me: String,
        kind: Int,
        dTag: String?,
        verify: (NostrEvent) -> Boolean,
    ): NostrEvent? = candidates
        .filter { it.pubkey == me && it.kind == kind && (dTag == null || it.dTag() == dTag) }
        .sortedByDescending { it.createdAt }
        .firstOrNull(verify)

    /** EOSE を返したリレー数と届いた最新版から結果を決める。イベントが届いていれば応答があったとみなす */
    fun outcome(eoseRelays: Int, latest: NostrEvent?): Result =
        if (eoseRelays == 0 && latest == null) Result.Unreachable else Result.Reached(latest)

    /**
     * 丸ごと置き換える編集（エディタで作った一覧で発行するもの）で、編集の土台にした版 [basedOnAt] より新しい版が
     * リレーにあるか。あれば発行すると他の端末・クライアントでの変更を消すので止める（Web の "stale"）。
     */
    fun isStale(latest: NostrEvent?, basedOnAt: Long): Boolean = latest != null && latest.createdAt > basedOnAt
}

/**
 * [#475] フォローリスト（kind:3）の 1 件の追加・削除。p タグの追加・削除だけを行い、
 * 他のタグ（t 等）・既存 p タグの 3〜4 要素目（リレーヒント・ペットネーム）・並び順はそのまま残す。
 */
internal object ContactListEdit {
    /** p タグのフォロー先（重複は1つに） */
    fun follows(tags: List<List<String>>): List<String> =
        tags.filter { it.size >= 2 && it[0] == "p" }.map { it[1] }.distinct()

    /** [pubkey] を末尾に足したタグ。既にフォロー済みなら null（発行しない） */
    fun follow(tags: List<List<String>>, pubkey: String): List<List<String>>? =
        if (tags.any { it.size >= 2 && it[0] == "p" && it[1] == pubkey }) null
        else tags + listOf(listOf("p", pubkey))

    /** [pubkey] の p タグをすべて外したタグ。フォローしていなければ null（発行しない） */
    fun unfollow(tags: List<List<String>>, pubkey: String): List<List<String>>? {
        val next = tags.filterNot { it.size >= 2 && it[0] == "p" && it[1] == pubkey }
        return if (next.size == tags.size) null else next
    }
}
