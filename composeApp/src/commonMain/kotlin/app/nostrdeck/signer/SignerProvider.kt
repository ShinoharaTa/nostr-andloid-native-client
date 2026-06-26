package app.nostrdeck.signer

/**
 * 現在アクティブな署名者を保持する。設定の「ログイン方法」に応じて差し替える。
 * 既定は開発用の LocalSigner(InMemoryKeyVault)。本番は保存済みのログイン状態から復元する。
 *
 * TODO: アカウント切替（複数 npub）に拡張。鍵は secure storage / Nosskey 実装へ。
 */
object SignerProvider {

    private var current: Signer = LocalSigner(InMemoryKeyVault().apply { generate() })

    fun current(): Signer = current

    /** ログイン方法の切替（LOCAL / NOSSKEY / NIP55 / NIP46 / NIP07）。 */
    fun use(signer: Signer) { current = signer }
}
