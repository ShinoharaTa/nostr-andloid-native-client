package app.nostrdeck.signer

/**
 * 現在アクティブな署名者を保持する。設定の「ログイン方法」に応じて差し替える。
 * 既定は開発用の LocalSigner(InMemoryKeyVault)。本番は保存済みのログイン状態から復元する。
 *
 * 鍵保管(KeyVault)も保持し、Settings からの import/generate はこの vault に対して行う。
 * vault を差し替える or 鍵を入れ替えたら LocalSigner を作り直す（公開鍵が変わるため）。
 *
 * TODO: アカウント切替（複数 npub）に拡張。
 * TODO: Android 起動時に KeystoreKeyVault(context) を、iOS は KeychainKeyVault を
 *       useVault() で注入し、永続化済みの鍵から復元する（Context 注入が必要なため未配線）。
 */
object SignerProvider {

    private var vault: KeyVault = InMemoryKeyVault().apply { generate() }
    private var current: Signer = LocalSigner(vault)

    fun current(): Signer = current

    /** 現在アクティブな鍵保管。Settings の import/generate はこれに作用する。 */
    fun vault(): KeyVault = vault

    /**
     * 鍵保管を差し替える（例: 起動時に Keystore/Keychain 実装へ）。
     * 鍵が未設定なら生成し、LocalSigner を作り直す。
     */
    fun useVault(v: KeyVault) {
        if (!v.hasKey()) v.generate()
        vault = v
        current = LocalSigner(v)
    }

    /** ログイン方法の切替（LOCAL / NOSSKEY / NIP55 / NIP46 / NIP07）。 */
    fun use(signer: Signer) { current = signer }

    /**
     * 現在の vault に秘密鍵を取り込み、ローカル署名へ切替える（nsec ログイン）。
     * 公開鍵が変わるので LocalSigner を作り直す。
     */
    fun importPrivateKey(privateKey: ByteArray) {
        vault.importPrivateKey(privateKey)
        current = LocalSigner(vault)
    }

    /** 現在の vault で新規鍵を生成し、ローカル署名へ切替える。 */
    fun generateNewKey(): ByteArray {
        val k = vault.generate()
        current = LocalSigner(vault)
        return k
    }
}
