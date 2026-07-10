package app.nostrdeck.signer

import app.nostrdeck.model.NostrEvent
import app.nostrdeck.model.UnsignedEvent

/**
 * 署名の抽象（whiteboard「署名・鍵管理」）。
 * アプリ本体は「どう署名されるか」を知らない。実装を差し替えるだけで
 * ローカル鍵 / Nosskey(パスキー) / NIP-55(Amber) / NIP-46(リモート) / NIP-07(WebView) に対応する。
 *
 * NIP-07 はブラウザ拡張 API のためネイティブには直接来ない。等価の委譲は NIP-55/46 が担う。
 */
interface Signer {
    val method: SignerMethod
    val capabilities: Set<SignerCap>

    /** x-only 公開鍵（hex, 32byte/64文字）。 */
    suspend fun publicKeyHex(): String

    /** 署名済みイベントを返す（id/pubkey/sig/created_at を確定）。 */
    suspend fun sign(unsigned: UnsignedEvent): NostrEvent

    /** NIP-44 暗号化（DM 等）。未対応の実装は [SignerCap.NIP44] を持たない。 */
    suspend fun nip44Encrypt(peerPubkeyHex: String, plaintext: String): String

    /** NIP-44 復号。 */
    suspend fun nip44Decrypt(peerPubkeyHex: String, ciphertext: String): String

    /** NIP-04 復号（NIP-51 非公開リスト等のレガシー互換・読み出し専用）。対応実装のみ override。 */
    suspend fun nip04Decrypt(peerPubkeyHex: String, ciphertext: String): String =
        throw NotImplementedError("NIP-04 に未対応の署名方式です")
}

enum class SignerMethod {
    NONE,     // 未ログイン（署名者なし）。勝手に鍵を作らず、ログイン画面を出すための状態。
    LOCAL,    // nsec を端末に保管し secp256k1 で署名
    NOSSKEY,  // パスキー(WebAuthn PRF)で暗号化した nsec
    NIP55,    // Android の外部署名アプリ(Amber)へ Intent 委譲
    NIP46,    // リモート署名(Nostr Connect / bunker, リレー経由)
    NIP07,    // window.nostr（WebView / CMP Web・Desktop でのみ）
}

enum class SignerCap { SIGN, NIP44, NIP04 }

/** 署名者が NIP-44 をサポートするか。 */
fun Signer.canEncrypt(): Boolean = SignerCap.NIP44 in capabilities
