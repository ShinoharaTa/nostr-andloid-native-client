package app.nostrdeck.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [NIP-57] Zap 額の解析（純関数・単体テスト可能）。
 * secp256k1 に依存しないため JVM 単体テストで検証できる（zap request/receipt の額計算）。
 */
object Nip57 {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * bolt11 invoice の金額(sats)を解析。`lnbc<amount><multiplier>` 形式（m/u/n/p）。
     * 金額指定が無い/解析不能なら 0。
     */
    fun bolt11Sats(invoice: String): Long {
        val m = Regex("""^ln(?:bc|tb|bcrt)(\d+)([munp]?)""", RegexOption.IGNORE_CASE).find(invoice.trim()) ?: return 0
        val num = m.groupValues[1].toLongOrNull() ?: return 0
        return when (m.groupValues[2].lowercase()) {
            "m" -> num * 100_000        // milli-BTC
            "u" -> num * 100            // micro-BTC
            "n" -> num / 10             // nano-BTC
            "p" -> num / 10_000         // pico-BTC
            else -> num * 100_000_000   // BTC
        }
    }

    /**
     * kind:9735(zap receipt) のタグ群から zap 額(sats)を取り出す。
     * description(=kind:9734 の JSON) 内の amount(msats) を優先し、無ければ bolt11 から算出する。
     */
    fun zapAmountSats(tags: List<List<String>>): Long {
        val desc = tags.firstOrNull { it.size >= 2 && it[0] == "description" }?.get(1)
        val msat = runCatching {
            desc?.let {
                json.parseToJsonElement(it).jsonObject["tags"]?.jsonArray
                    ?.map { t -> t.jsonArray.map { s -> s.jsonPrimitive.content } }
                    ?.firstOrNull { t -> t.size >= 2 && t[0] == "amount" }?.get(1)?.toLongOrNull()
            }
        }.getOrNull() ?: 0
        if (msat > 0) return msat / 1000
        val bolt11 = tags.firstOrNull { it.size >= 2 && it[0] == "bolt11" }?.get(1) ?: return 0
        return bolt11Sats(bolt11)
    }
}
