package app.nostrdeck.model

/**
 * [#772] NIP-38 ユーザーステータス（kind:30315。addressable で d が種類）の表示条件と並び。
 * Web の `web/src/features/status/statusModel.ts`（#767）と同じ規則。Web を変えたらここも揃えること。
 */
object UserStatuses {
    const val KIND = 30315

    /** 期限の無いステータスを出す上限（30 日）。取得の since にも使う。 */
    const val MAX_AGE_SEC = 30L * 86400

    /** 取得の上限（置き換え可能で 1 人 2 件までなので、過去読みせず 1 回で取り切る）。 */
    const val FETCH_LIMIT = 500

    /** 表示する種類（NIP-38 で定義済みの 2 つ）。ほかの d（presence 等）は扱わない。 */
    enum class Type(val d: String) { GENERAL("general"), MUSIC("music") }

    /** d タグの種類。完全一致だけ（`general,expiration=…` のような壊れた値は null）。 */
    fun typeOf(e: NostrEvent): Type? {
        val d = e.tags.firstOrNull { it.isNotEmpty() && it[0] == "d" }?.getOrNull(1)
        return Type.entries.firstOrNull { it.d == d }
    }

    /** NIP-40 の期限（unix 秒）。無い・数値でなければ null（期限無し扱い）。 */
    fun expirationOf(e: NostrEvent): Long? =
        e.tags.firstOrNull { it.size >= 2 && it[0] == "expiration" }?.get(1)?.trim()?.toLongOrNull()

    /**
     * カラムに出すステータスか。[filter] が null なら両方の種類。
     * 空（空白だけを含む）は「クリア」、期限が過ぎたものは出さない。期限が無いものは [MAX_AGE_SEC] より古ければ出さない
     * （期限が未来なら古くても出す）。
     */
    fun isVisible(e: NostrEvent, filter: Type?, now: Long): Boolean {
        if (e.kind != KIND) return false
        val type = typeOf(e) ?: return false
        if (filter != null && type != filter) return false
        if (e.content.isBlank()) return false
        val expiration = expirationOf(e)
        if (expiration != null) return expiration > now
        return e.createdAt >= now - MAX_AGE_SEC
    }

    /** 新しい順。同時刻は id の昇順（並びを安定させる）。 */
    fun sort(events: List<NostrEvent>): List<NostrEvent> =
        events.sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })

    /**
     * 同じ人・同じ種類の版のうち、どちらを残すか（NIP-01 の置き換え可能イベントの規則: 新しい方、同時刻なら id の小さい方）。
     * [next] を採るなら true。
     */
    fun replaces(current: NostrEvent?, next: NostrEvent): Boolean =
        current == null || next.createdAt > current.createdAt || (next.createdAt == current.createdAt && next.id < current.id)

    /** 参照先（Web は applesauce の getUserStatusPointer。r / p / e / a のうち最初の 1 つ）。 */
    sealed interface Pointer {
        data class Url(val url: String) : Pointer
        data class Profile(val pubkey: String) : Pointer
        data class Event(val id: String) : Pointer
        data class Address(val kind: Int, val pubkey: String, val dTag: String) : Pointer
    }

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun pointerOf(e: NostrEvent): Pointer? {
        val tag = e.tags.firstOrNull { it.size >= 2 && it[0] in POINTER_TAGS && it[1].isNotEmpty() } ?: return null
        val v = tag[1]
        return when (tag[0]) {
            "r" -> Pointer.Url(v)
            "p" -> v.lowercase().takeIf { HEX64.matches(it) }?.let { Pointer.Profile(it) }
            "e" -> v.lowercase().takeIf { HEX64.matches(it) }?.let { Pointer.Event(it) }
            else -> {
                val parts = v.split(":", limit = 3)
                val kind = parts.getOrNull(0)?.toIntOrNull()
                val pubkey = parts.getOrNull(1)?.lowercase()
                if (parts.size == 3 && kind != null && pubkey != null && HEX64.matches(pubkey)) Pointer.Address(kind, pubkey, parts[2]) else null
            }
        }
    }

    private val POINTER_TAGS = setOf("r", "p", "e", "a")

    /** ホスト名 → サービス名（固有名詞なので翻訳しない）。 */
    private val SERVICE_BY_HOST = mapOf(
        "open.spotify.com" to "Spotify",
        "music.apple.com" to "Apple Music",
        "music.youtube.com" to "YouTube Music",
        "youtube.com" to "YouTube",
        "www.youtube.com" to "YouTube",
        "m.youtube.com" to "YouTube",
        "youtu.be" to "YouTube",
        "soundcloud.com" to "SoundCloud",
    )

    private val HTTP_URL = Regex("^https?://([^/?#]*)", RegexOption.IGNORE_CASE)

    /**
     * リンク行に出すサービス名。http(s) 以外（`spotify:…`・壊れた `hittps://…` 等）は null（リンクにしない）。
     * 知らないホストはホスト名（先頭の www. を外す）。
     */
    fun serviceLabelOf(url: String): String? {
        val authority = HTTP_URL.find(url)?.groupValues?.get(1) ?: return null
        val host = authority.substringAfterLast('@').substringBefore(':').lowercase()
        if (host.isEmpty()) return null
        SERVICE_BY_HOST[host]?.let { return it }
        if (host == "bandcamp.com" || host.endsWith(".bandcamp.com")) return "Bandcamp"
        return host.removePrefix("www.")
    }

    /** 期限の表示の種類（文言は UI が付ける）。 */
    sealed interface Expiry {
        /** 60 秒未満「まもなく終了」 */
        data object Soon : Expiry
        /** 60 分未満「残り N 分」（切り上げ） */
        data class Minutes(val n: Long) : Expiry
        /** 24 時間未満「残り N 時間」（切り捨て） */
        data class Hours(val n: Long) : Expiry
        /** それ以上は「M/D HH:mm まで」（端末のタイムゾーン） */
        data class At(val epochSec: Long) : Expiry
    }

    fun expiryOf(expiration: Long, now: Long): Expiry {
        val left = expiration - now
        return when {
            left < 60 -> Expiry.Soon
            left < 3600 -> Expiry.Minutes((left + 59) / 60)
            left < 86400 -> Expiry.Hours(left / 3600)
            else -> Expiry.At(expiration)
        }
    }
}
