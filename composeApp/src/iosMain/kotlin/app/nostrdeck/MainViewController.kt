package app.nostrdeck

import androidx.compose.ui.window.ComposeUIViewController
import app.nostrdeck.data.EventRepository
import app.nostrdeck.data.defaultRelaysFor
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import app.nostrdeck.signer.KeychainKeyVault
import app.nostrdeck.signer.SignerProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** [#150] 既定リレー。端末の言語で切替（Android MainActivity と同じマッピング）。初回シードのみに使う。 */
private val DEFAULT_RELAYS = defaultRelaysFor(NSLocale.currentLocale.languageCode)

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
