package app.nostrdeck.crypto

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [NIP-57] Zap 額の解析テスト。secp256k1 に依存しない純関数なので JVM 単体テストで検証できる。
 * bolt11 の乗数(m/u/n/p)と、zap receipt(kind:9735) の amount/bolt11 解決を確認する。
 */
class Nip57Test {

    @Test
    fun bolt11_multipliers() {
        // BOLT11 仕様例: 2500u = 250,000 sats（micro-BTC ×100）
        assertEquals(250_000L, Nip57.bolt11Sats("lnbc2500u1pvjluezpp5..."))
        // 20m = 2,000,000 sats（milli-BTC ×100,000）
        assertEquals(2_000_000L, Nip57.bolt11Sats("lnbc20m1pvjluezpp5..."))
        // nano: 210n = 21 sats（÷10）
        assertEquals(21L, Nip57.bolt11Sats("lnbc210n1pxxxxxx"))
        // pico: 9678785340p ≒ 967,878 sats（÷10,000, 切り捨て）
        assertEquals(967_878L, Nip57.bolt11Sats("lnbc9678785340p1pxxxxxx"))
        // 乗数なし = BTC 単位（1 BTC = 100,000,000 sats）
        assertEquals(100_000_000L, Nip57.bolt11Sats("lnbc1"))
    }

    @Test
    fun bolt11_invalid_returns_zero() {
        assertEquals(0L, Nip57.bolt11Sats(""))
        assertEquals(0L, Nip57.bolt11Sats("not-an-invoice"))
    }

    @Test
    fun zap_amount_from_description() {
        // zap receipt の description は kind:9734(zap request)の JSON。amount(msats) を優先。
        val desc = """{"kind":9734,"content":"","tags":[["amount","21000"],["p","abcd"]]}"""
        val tags = listOf(
            listOf("description", desc),
            listOf("bolt11", "lnbc999u1pxxxxxx"),  // description があるので bolt11 は使わない
        )
        assertEquals(21L, Nip57.zapAmountSats(tags))  // 21000 msats = 21 sats
    }

    @Test
    fun zap_amount_falls_back_to_bolt11() {
        // amount が無い(または description 無し)なら bolt11 から算出。
        val tags = listOf(
            listOf("description", """{"kind":9734,"tags":[["p","abcd"]]}"""),
            listOf("bolt11", "lnbc10u1pxxxxxx"),  // 10u = 1000 sats
        )
        assertEquals(1000L, Nip57.zapAmountSats(tags))
    }

    @Test
    fun zap_amount_none_returns_zero() {
        assertEquals(0L, Nip57.zapAmountSats(listOf(listOf("p", "abcd"))))
    }
}
