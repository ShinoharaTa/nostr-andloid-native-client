package app.nostrdeck.wallet

/**
 * [#993] Zap の請求書を開くウォレット（iOS の設定 → ウォレットで選ぶ）。
 * iOS にはアプリを選ぶ画面が無く、`lightning:` はどれか 1 つのアプリが開くだけなので、ウォレットごとの URL で開く。
 * 一覧と URL の作り方（[prefix] の後ろに請求書をそのまま付ける）は Damus（damus/Features/Wallet/Models/Wallet.swift）と同じ。
 * TestFlight でしか配っていないもの（LNLink・Blixt）は入れていない。
 * iOS で入っているかを確かめるため、[scheme] は iosApp/project.yml の LSApplicationQueriesSchemes にも並べる。
 * Web の一覧（web/src/features/zap/zapWallets.ts。#994）も同じ並び・同じ URL にする。
 */
enum class ZapWallet(val id: String, val displayName: String, val prefix: String) {
    /** 端末の既定（`lightning:` を受け付けるアプリ）。表示名は画面側で訳す。 */
    SYSTEM("system", "", "lightning:"),
    STRIKE("strike", "Strike", "strike:"),
    CASH_APP("cashapp", "Cash App", "https://cash.app/launch/lightning/"),
    MUUN("muun", "Muun", "muun:"),
    BLUE_WALLET("bluewallet", "BlueWallet", "bluewallet:lightning:"),
    WALLET_OF_SATOSHI("walletofsatoshi", "Wallet of Satoshi", "walletofsatoshi:lightning:"),
    ZEBEDEE("zebedee", "ZEBEDEE", "zebedee:lightning:"),
    ZEUS("zeusln", "ZEUS", "zeusln:lightning:"),
    PHOENIX("phoenix", "Phoenix", "phoenix://"),
    BREEZ("breez", "Breez", "breez:"),
    BLINK("blink", "Blink", "blink://"),
    RIVER("river", "River", "river://"),
    ALBY_GO("albygo", "Alby Go", "alby:");

    /** 請求書（BOLT11）を開く URL。 */
    fun uriFor(invoice: String): String = prefix + invoice

    /** URL スキーム（`https` の Cash App はブラウザでも開けるので確かめなくてよい）。 */
    val scheme: String get() = prefix.substringBefore(':')

    companion object {
        /** 保存した id から。未設定・知らない id は端末の既定。 */
        fun fromId(id: String?): ZapWallet = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}
