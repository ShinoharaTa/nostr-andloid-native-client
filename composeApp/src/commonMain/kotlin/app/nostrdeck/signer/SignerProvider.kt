package app.nostrdeck.signer

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 現在アクティブな署名者を保持する。設定の「ログイン方法」に応じて差し替える。
 *
 * 重要（#login）: **鍵を勝手に生成しない**。未ログイン時は [NoSigner]（method=NONE）を保持し、
 * UI 側は [session] を見てログイン画面（ゲート）を表示する。鍵の新規生成は
 * ユーザーが明示的に選んだとき（[generateNewKey]）だけ行う。
 *
 * 鍵保管(KeyVault)も保持し、Settings/ログイン画面からの import/generate はこの vault に作用する。
 */
object SignerProvider {

    // 起動時は空の vault（自動生成しない）。Keystore/Keychain 実装は useVault で注入する。
    private var vault: KeyVault = InMemoryKeyVault()
    private var current: Signer = NoSigner

    /** ログイン中かどうか（UI のゲート表示に使う）。 */
    private val _session = MutableStateFlow(false)
    val session: StateFlow<Boolean> get() = _session

    fun current(): Signer = current

    /** ログイン済みか（署名者がある＝method != NONE）。 */
    fun hasSession(): Boolean = current.method != SignerMethod.NONE

    /** 現在アクティブな鍵保管。import/generate はこれに作用する。 */
    fun vault(): KeyVault = vault

    private fun activate(signer: Signer) {
        current = signer
        _session.value = signer.method != SignerMethod.NONE
    }

    /**
     * 鍵保管を差し替える（起動時に Keystore/Keychain 実装へ）。
     * **既存の鍵があればローカル署名として復元する。無ければ未ログインのまま（自動生成しない）。**
     */
    fun useVault(v: KeyVault) {
        vault = v
        if (v.hasKey()) activate(LocalSigner(v))
    }

    /** ログイン方法の切替（LOCAL / NOSSKEY / NIP55 / NIP46 / NIP07）。 */
    fun use(signer: Signer) { activate(signer) }

    /** 外部署名(NIP-55/46)から、保持している vault のローカル署名へ戻す。 */
    fun useLocal() { activate(LocalSigner(vault)) }

    /** 現在の vault に秘密鍵を取り込み、ローカル署名へ切替える（nsec ログイン）。 */
    fun importPrivateKey(privateKey: ByteArray) {
        vault.importPrivateKey(privateKey)
        activate(LocalSigner(vault))
    }

    /** ユーザーが明示的に選んだときだけ新規鍵を生成し、ローカル署名へ切替える。 */
    fun generateNewKey(): ByteArray {
        val k = vault.generate()
        activate(LocalSigner(vault))
        return k
    }

    /** ログアウト（未ログイン状態へ戻す）。 */
    fun logout() { activate(NoSigner) }
}

/** 未ログインを表す署名者。identity/署名の使用は例外（UI は method==NONE で判定して使わない）。 */
private object NoSigner : Signer {
    override val method = SignerMethod.NONE
    override val capabilities = emptySet<SignerCap>()
    override suspend fun publicKeyHex(): String = error("未ログインです")
    override suspend fun sign(unsigned: UnsignedEvent): NostrEvent = error("未ログインです")
    override suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String = error("未ログインです")
    override suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String = error("未ログインです")
}
