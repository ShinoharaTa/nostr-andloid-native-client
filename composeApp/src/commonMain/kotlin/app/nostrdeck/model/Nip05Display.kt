package app.nostrdeck.model

/**
 * [#931] NIP-05 の表示。ローカル部が `_` の識別子（`_@domain`）はそのドメインの「ルート」の識別子で、
 * ドメインだけを表示してよい（NIP-05）。表示だけを変え、検証（照会）とプロフィールの値は元のまま使う。
 */
object Nip05Display {
    fun label(nip05: String): String {
        val value = nip05.trim()
        val at = value.indexOf('@')
        if (at < 0) return value
        val local = value.substring(0, at)
        val domain = value.substring(at + 1)
        return if (local == "_" && domain.isNotEmpty() && '@' !in domain) domain.lowercase() else value
    }
}
