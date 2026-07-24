package app.nostrdeck.ui

/**
 * [#224] 実行プラットフォームが iOS かどうか。
 * ピッカー復帰後のキーボード復帰戦略（iOS はフォーカスサイクル / Android は show() リトライ）の
 * 分岐に使う。UI 挙動の分岐以外には使わないこと（機能分岐は expect/actual で行う）。
 */
expect val isIosPlatform: Boolean
