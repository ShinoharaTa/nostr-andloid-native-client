package app.nostrdeck

import androidx.compose.ui.window.ComposeUIViewController
import app.nostrdeck.data.EventRepository
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import app.nostrdeck.signer.KeychainKeyVault
import app.nostrdeck.signer.SignerProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 既定リレー（Android MainActivity と揃える）。設定変更対応は別途。 */
private val DEFAULT_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
)

// [#78] 未捕捉の非同期例外でプロセスを落とさない底。SupervisorJob は兄弟キャンセルを防ぐだけ
// なので、CoroutineExceptionHandler で握ってログに留める。アプリ全体で1つ。
private val appScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, t -> println("Nostrism uncaught in appScope: $t") }
)

// リポジトリはアプリで1つ。UIViewController が作り直されても再構築しないよう lazy シングルトン。
// 鍵保管は iOS Keychain（KeychainKeyVault）。鍵未設定なら App() のログインゲートで生成/取り込み。
private val repository: EventRepository by lazy {
    SignerProvider.useVault(KeychainKeyVault())
    val db = createDatabase(DriverFactory())
    EventRepository(db, appScope, DEFAULT_RELAYS).apply { start() }
}

/**
 * iOS の入口。Swift 側（iosApp）から `MainViewControllerKt.MainViewController()` を呼び出し、
 * SwiftUI の UIViewControllerRepresentable でホストする。
 * 実データ（リレー/DB/署名）配線済み: 未ログインなら App() がログインゲートを出す。
 */
fun MainViewController() = ComposeUIViewController { App(repository) }
