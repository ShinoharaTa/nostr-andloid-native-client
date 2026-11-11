package app.nostrdeck.wallet

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [#993] Zap の請求書を開くウォレット（iOS の設定で選ぶ）。 */
class ZapWalletTest {

    @Test
    fun saved_ids_round_trip_and_unknown_falls_back_to_the_device_default() {
        for (w in ZapWallet.entries) assertEquals(w, ZapWallet.fromId(w.id))
        assertEquals(ZapWallet.SYSTEM, ZapWallet.fromId(null))
        assertEquals(ZapWallet.SYSTEM, ZapWallet.fromId("lnlink"))
        assertEquals(ZapWallet.entries.size, ZapWallet.entries.map { it.id }.toSet().size, "id は重ならない")
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
