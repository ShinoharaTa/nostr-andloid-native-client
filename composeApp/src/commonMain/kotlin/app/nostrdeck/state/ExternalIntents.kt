package app.nostrdeck.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * [#100][#101] OS から届いた外部 Intent（共有テキスト / nostr: ディープリンク）の共通表現。
 * プラットフォーム層（Android の MainActivity 等）が解析して post し、
 * Compose 側（App）がログイン成立後に消費する。
 */
sealed interface ExternalIntent {
    /** 他アプリの共有シートから受け取ったテキスト → コンポーザーを初期値付きで開く。 */
    data class ShareText(val text: String) : ExternalIntent

    /** nostr:npub1… / nostr:nprofile1… → プロフィールを開く（hex 公開鍵）。 */
    data class OpenProfile(val pubkeyHex: String) : ExternalIntent

    /** nostr:note1… / nostr:nevent1… → スレッドを開く（event id + リレーヒント）。 */
    data class OpenEvent(val id: String, val relays: List<String>) : ExternalIntent
}

/**
 * 外部 Intent の受け渡しシングルトン。
 * 未ログイン中に届いた要求も値として保持し、ログイン成立後に App 側が処理して consume する。
 * 後着優先（連続で届いたら最後の1件だけ処理する）。
 */
object ExternalIntents {
    private val _pending = MutableStateFlow<ExternalIntent?>(null)
    val pending: StateFlow<ExternalIntent?> = _pending

    /** プラットフォーム層から外部 Intent を積む（onCreate / onNewIntent の両方から）。 */
    fun post(intent: ExternalIntent) { _pending.value = intent }

    /** 処理済みの要求をクリアする（App 側が消費した後に呼ぶ）。 */
    fun consume() { _pending.value = null }
}
