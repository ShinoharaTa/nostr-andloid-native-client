package app.nostrdeck.data

/**
 * [#411] 発行するイベントに載せるリレーヒントの選定と充填。
 *
 * ヒントは `e`/`p`/`q`/`E`/`P` タグの3要素目（NIP-01/NIP-10/NIP-18/NIP-22）と NIP-19 の
 * relay TLV に入る「そのイベント/人が見つかりやすいリレー」。保証ではなく手がかりなので、
 * 読み手にとって最も普遍的に当たる場所を1本だけ指す。
 *
 * Nip10 / Nip27 と同じ方針で純関数だけを置く。受信元の記録や NIP-65 キャッシュの参照は
 * Repository 側の仕事で、ここは「候補の列 → 1本」の規則と「タグ列への充填」だけを持つ。
 */
object RelayHints {

    /** ヒントを充填する対象のタグキー。3要素目がリレー URL のもの。 */
    private val EVENT_KEYS = setOf("e", "q", "E")
    private val PUBKEY_KEYS = setOf("p", "P")

    /**
     * 他人に教えてよい公開リレーか。
     * `ws://`（平文＝ローカル開発リレー）、localhost / ループバック / プライベートアドレス /
     * mDNS(.local) を弾く。自分の手元でしか繋がらない場所を指しても無意味で、環境が漏れるだけ。
     */
    fun isPublic(url: String): Boolean {
        if (!url.startsWith("wss://")) return false
        val hostPort = url.removePrefix("wss://").substringBefore('/')
        val host = hostPort.removePrefix("[").substringBefore(']').substringBefore(':').lowercase()
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".local") || host.endsWith(".localhost")) return false
        if (host == "::1" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return false
        val oct = host.split('.')
        if (oct.size == 4 && oct.all { it.toIntOrNull() in 0..255 }) {
            val a = oct[0].toInt(); val b = oct[1].toInt()
            if (a == 127 || a == 10 || a == 0) return false
            if (a == 192 && b == 168) return false
            if (a == 172 && b in 16..31) return false
            if (a == 169 && b == 254) return false
        }
        return true
    }

    /**
     * ヒントを1本選ぶ。無ければ空文字（= 現状どおり「ヒント無し」）。
     *
     * 受信元をそのまま入れない。自分の購読先は「自分がたまたま繋いでいる場所」であって、
     * 読み手にとって最良とは限らない。NIP-65 の考え方に沿い、**著者の write リレー**を軸にする。
     *  1. [seenOn] ∩ [authorWrite] … 確かにそこにあり、かつ著者が宣言している
     *  2. [authorWrite] の先頭
     *  3. [seenOn] のうち公開リレー
     * [excluded]（AUTH を要求されたリレー・検索専用リレー等）と非公開リレーはどの段でも除く。
     */
    fun pick(
        seenOn: Collection<String>,
        authorWrite: Collection<String>,
        excluded: Set<String> = emptySet(),
    ): String {
        fun ok(u: String) = u.isNotBlank() && u !in excluded && isPublic(u)
        val write = authorWrite.filter(::ok)
        val seen = seenOn.filter(::ok)
        val seenSet = seen.toHashSet()
        return write.firstOrNull { it in seenSet } ?: write.firstOrNull() ?: seen.firstOrNull() ?: ""
    }

    /**
     * タグ列の空いているヒント枠を埋める。
     *  - `e`/`q`/`E` は [eventHint]（イベント id → ヒント）、`p`/`P` は [pubkeyHint]（pubkey → ヒント）
     *  - 3要素目が既に入っているタグ（NIP-22 で親から継承したルートタグ等）は触らない
     *  - 2要素のタグは3要素に伸ばす（`["e", id]` → `["e", id, hint]`）。kind:6 の e タグは NIP-18 で
     *    relay URL が MUST なので、ヒントが取れなくても `""` を置いて形だけは揃える
     *  - 上記以外のキー（`t` / `emoji` / `k` / `a` …）はそのまま
     * 「kind:1984 通報の `["e", id, <type>]`」のように3要素目がヒントでないイベントには使わないこと。
     */
    fun fill(
        tags: List<List<String>>,
        eventHint: (String) -> String,
        pubkeyHint: (String) -> String,
    ): List<List<String>> = tags.map { tag ->
        if (tag.size < 2) return@map tag
        val lookup: ((String) -> String)? = when (tag[0]) {
            in EVENT_KEYS -> eventHint
            in PUBKEY_KEYS -> pubkeyHint
            else -> null
        }
        lookup ?: return@map tag
        if (tag.size >= 3 && tag[2].isNotEmpty()) return@map tag
        val hint = lookup(tag[1])
        if (tag.size >= 3) tag.toMutableList().also { it[2] = hint } else tag + hint
    }
}
