package app.nostrdeck.wallet

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#993] Zap の請求書を開くウォレット（iOS の設定で選ぶ。[#1011] 既定は毎回選ぶ）。 */
class ZapWalletTest {

    @Test
    fun saved_ids_round_trip_and_the_rest_ask_every_time() {
        for (w in ZapWallet.entries) assertEquals(w, ZapWallet.fromSetting(w.id))
        // [#1011] 未設定・「毎回選ぶ」・知らない id は毎回選ぶ（null）。v0.6.33 で選んだ端末の既定はそのまま
        assertNull(ZapWallet.fromSetting(null))
        assertNull(ZapWallet.fromSetting(ZapWallet.ASK_ID))
        assertNull(ZapWallet.fromSetting("lnlink"))
        assertEquals(ZapWallet.SYSTEM, ZapWallet.fromSetting("system"))
        assertEquals(ZapWallet.entries.size, ZapWallet.entries.map { it.id }.toSet().size, "id は重ならない")
        assertTrue(ZapWallet.entries.none { it.id == ZapWallet.ASK_ID }, "「毎回選ぶ」の値とウォレットの id は重ならない")
    }

    @Test
    fun the_invoice_follows_the_prefix_as_is() {
        val invoice = "lnbc10n1pexample"
        assertEquals("lightning:$invoice", ZapWallet.SYSTEM.uriFor(invoice))
        assertEquals("walletofsatoshi:lightning:$invoice", ZapWallet.WALLET_OF_SATOSHI.uriFor(invoice))
        assertEquals("phoenix://$invoice", ZapWallet.PHOENIX.uriFor(invoice))
        assertEquals("https://cash.app/launch/lightning/$invoice", ZapWallet.CASH_APP.uriFor(invoice))
    }

    /** iOS は LSApplicationQueriesSchemes に並べたスキームしか canOpenURL で確かめられない（並べ忘れると常に「入っていない」）。 */
    @Test
    fun every_scheme_is_declared_for_ios() {
        val yml = File("../iosApp/project.yml").readText()
        val declared = yml.substringAfter("LSApplicationQueriesSchemes:").lineSequence().drop(1)
            .map { it.trim() }.takeWhile { it.startsWith("- ") }.map { it.removePrefix("- ") }.toSet()
        val needed = ZapWallet.entries.map { it.scheme }.filter { it != "https" }.toSet()
        assertEquals(needed, declared)
        val plist = File("../iosApp/iosApp/Info.plist").readText()
        for (scheme in needed) assertTrue("<string>$scheme</string>" in plist, "Info.plist に $scheme が無い（xcodegen generate で作り直す）")
    }
}
