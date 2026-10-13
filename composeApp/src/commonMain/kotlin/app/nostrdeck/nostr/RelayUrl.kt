package app.nostrdeck.nostr

/**
 * [#822] ユーザーが入れるリレー URL（設定 → リレー / DM リレーの追加欄、bunker:// の relay=）の
 * 正規化と判定。純関数だけを置く。
 *
 * スキームは `wss://` と `ws://` の両方を受ける。`ws://` は暗号化されない接続だが、端末内（Citrine 等）や
 * LAN のリレーのために**ホストを問わず**許す（#822 の決定。代わりに画面で注意書きを出す）。
 * 平文の接続そのものは Android の network security config と iOS の ATS 側で許可している。
 */
object RelayUrl {

    /**
     * 前後の空白と末尾の `/` を落とし、`ws` / `wss` のスキームだけ小文字に揃える。
     * スキームは**保つ**（`ws://` を `wss://` に書き換えない）。ホスト以降の大小文字はそのまま。
     */
    fun normalize(url: String): String {
        val s = url.trim()
        val i = s.indexOf("://")
        if (i <= 0) return s.trimEnd('/')
        // 末尾の `/` は `://` より後ろだけ落とす（入力途中の "ws://" を "ws:" にしない）。
        val rest = s.substring(i + 3).trimEnd('/')
        val scheme = s.substring(0, i)
        val lower = scheme.lowercase()
        return (if (lower == "ws" || lower == "wss") lower else scheme) + "://" + rest
    }

    /** `ws://` か `wss://` で、ホスト（と任意のポート）が読めるか。正規化してから判定する。 */
    fun isValid(url: String): Boolean {
        val s = normalize(url)
        val rest = when {
            s.startsWith("wss://") -> s.removePrefix("wss://")
            s.startsWith("ws://") -> s.removePrefix("ws://")
            else -> return false
        }
        if (rest.any { it.isWhitespace() }) return false
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty() || '@' in authority) return false
        val host: String
        val port: String?
        if (authority.startsWith("[")) {
            // IPv6 リテラル（[::1]:4869 など）
            val close = authority.indexOf(']')
            if (close < 0) return false
            host = authority.substring(1, close)
            val after = authority.substring(close + 1)
            port = when {
                after.isEmpty() -> null
                after.startsWith(":") -> after.substring(1)
                else -> return false
            }
            if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) return false
        } else {
            host = authority.substringBefore(':')
            port = if (':' in authority) authority.substringAfter(':') else null
            if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '-' || it == '.' || it == '_' }) return false
        }
        if (port != null && (!port.all(Char::isDigit) || port.toIntOrNull() !in 1..65535)) return false
        return true
    }

    /** 暗号化されない `ws://` か（大小文字・前後の空白は問わない）。画面の注意書きを出す判定に使う。 */
    fun isInsecure(url: String): Boolean = normalize(url).startsWith("ws://")
}
