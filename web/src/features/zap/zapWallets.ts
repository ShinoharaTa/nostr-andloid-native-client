/**
 * [#994] スマホのブラウザで、請求書をウォレットを指定して開くための一覧（ネイティブ ZapWallet.kt。#993）。
 * スマホのブラウザの `lightning:` は登録されたアプリが 1 つ開くだけで選べない（iOS）ので、ウォレットごとの URL で開く。
 * URL の作り方（prefix の後ろに請求書をそのまま付ける）と並びは Damus・ネイティブと同じ。端末の既定（`lightning:`）は
 * 「外部ウォレットで開く」のほうで開くので、ここには入れない。入っているかは調べられないので、全部並べる。
 */
export type ZapWalletLink = { id: string; name: string; prefix: string };

export const ZAP_WALLETS: readonly ZapWalletLink[] = [
  { id: "strike", name: "Strike", prefix: "strike:" },
  { id: "cashapp", name: "Cash App", prefix: "https://cash.app/launch/lightning/" },
  { id: "muun", name: "Muun", prefix: "muun:" },
  { id: "bluewallet", name: "BlueWallet", prefix: "bluewallet:lightning:" },
  { id: "walletofsatoshi", name: "Wallet of Satoshi", prefix: "walletofsatoshi:lightning:" },
  { id: "zebedee", name: "ZEBEDEE", prefix: "zebedee:lightning:" },
  { id: "zeusln", name: "ZEUS", prefix: "zeusln:lightning:" },
  { id: "phoenix", name: "Phoenix", prefix: "phoenix://" },
  { id: "breez", name: "Breez", prefix: "breez:" },
  { id: "blink", name: "Blink", prefix: "blink://" },
  { id: "river", name: "River", prefix: "river://" },
  { id: "albygo", name: "Alby Go", prefix: "alby:" },
];

/** スマホ・タブレット（ホバーできず指で触る端末）か。一覧はそこでだけ出す（PC は QR を読んでもらう） */
export function isTouchDevice(): boolean {
  return (
    typeof window.matchMedia === "function" &&
    window.matchMedia("(hover: none) and (pointer: coarse)").matches
  );
}
