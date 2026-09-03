package app.nostrdeck.model

import kotlinx.serialization.Serializable

/**
 * [#393] ピン留めハッシュタグ（NIP-51 Interest set, kind:30015, `d="pinned"`）。
 *
 * `t` タグの並びが表示順。content は空。replaceable なので保存のたびに丸ごと発行して置き換える。
 * 10015（Interests list）や他クライアントの興味セットとは `d` で用途を分ける。
 * ここは純関数だけ（組み立て/解釈/正規化）。発行・購読は EventRepository 側。
 */
object PinnedHashtags {
    const val KIND = 30015
    const val D_TAG = "pinned"

    /** ピン留めの上限。溢れても `used_hashtag` の履歴からサジェストされるので困らない。 */
    const val MAX = 15

    /**
     * 手入力/タグ値を `used_hashtag` と同じ形に正規化する（小文字・先頭 `#` 除去・前後空白除去）。
     * 空、または NIP-24 の `t` として使えない文字（空白や `#` 等）を含むなら null。
     * 許容文字は投稿本文からの抽出（letter/digit/`_`）と揃える。
     */
    fun normalize(raw: String): String? {
        val s = raw.trim().removePrefix("#").trim().lowercase()
        if (s.isEmpty()) return null
        if (!s.all { it.isLetterOrDigit() || it == '_' }) return null
        return s
    }

    /** 一覧を正規化し、重複を畳んで上限で切り詰める（順序は保持）。 */
    fun normalizeList(tags: List<String>): List<String> =
        tags.mapNotNull { normalize(it) }.distinct().take(MAX)

    /** 発行用のタグ配列。`d` を先頭に、`t` を表示順に並べる。 */
    fun toTags(tags: List<String>): List<List<String>> =
        listOf(listOf("d", D_TAG)) + normalizeList(tags).map { listOf("t", it) }

    /** このイベントが自分のピン留めセット（kind:30015 / d=pinned）か。 */
    fun isPinnedSet(event: NostrEvent): Boolean = event.kind == KIND && event.dTag() == D_TAG

    /**
     * kind:30015（d=pinned）からピン留め一覧を取り出す。`t` タグ順。他クライアントが上限を超えて
     * 書いていても表示は [MAX] 件までに切り詰める。対象外のイベントなら null。
     */
    fun parse(event: NostrEvent): List<String>? {
        if (!isPinnedSet(event)) return null
        return fromTags(event.tags)
    }

    /** タグ配列から `t` の値を表示順で取り出す（kind/d の検査はしない。キャッシュの生タグから導出する用）。 */
    fun fromTags(tags: List<List<String>>): List<String> =
        normalizeList(tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] })
}

/**
 * [#393] ピン留めのローカルキャッシュ（一覧 + その版の created_at）。KV に丸ごと保存する。
 * [at] は「この一覧が確定した時刻」。楽観更新では発行前に「今」へ進め、古いエコーが編集を上書きしないようにする。
 */
@Serializable
data class PinnedCache(val tags: List<String> = emptyList(), val at: Long = 0L)

/** 受信した 30015 をローカルキャッシュへどう反映するか（[PinnedHashtags.reconcile]）。 */
sealed class PinnedReconcile {
    /** 受信版が新しい（または同時刻）。キャッシュを置き換える。 */
    data class Accept(val cache: PinnedCache) : PinnedReconcile()
    /** 対象外（他人/別の d/古い版で内容も同じ）。何もしない。 */
    data object Ignore : PinnedReconcile()
    /** 受信版が古く内容も違う＝ローカルが正（未発行のまま終了した等）。自分のキャッシュを再発行する。 */
    data object Republish : PinnedReconcile()
}

/**
 * 受信した 30015 とローカルキャッシュを突き合わせる（純関数。呼び出し側が再発行の回数を抑える）。
 * [me] が null（未ログイン）なら常に [PinnedReconcile.Ignore]。
 */
fun PinnedHashtags.reconcile(cache: PinnedCache, event: NostrEvent, me: String?): PinnedReconcile {
    if (me == null || event.pubkey != me) return PinnedReconcile.Ignore
    val tags = parse(event) ?: return PinnedReconcile.Ignore
    return when {
        event.createdAt >= cache.at -> PinnedReconcile.Accept(PinnedCache(tags, event.createdAt))
        tags == cache.tags -> PinnedReconcile.Ignore
        else -> PinnedReconcile.Republish
    }
}

/** [#393] 使ったことのあるハッシュタグ（`used_hashtag` 行）。整理画面の一覧用。 */
data class UsedHashtag(val tag: String, val lastUsed: Long)
