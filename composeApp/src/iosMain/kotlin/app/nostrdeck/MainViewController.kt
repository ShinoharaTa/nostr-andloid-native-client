package app.nostrdeck

import androidx.compose.ui.window.ComposeUIViewController
import app.nostrdeck.data.EventRepository
import app.nostrdeck.data.defaultRelaysFor
import app.nostrdeck.ui.IosBackDispatcher
import platform.Foundation.NSLocale
import platform.Foundation.NSSelectorFromString
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import app.nostrdeck.signer.KeychainKeyVault
import app.nostrdeck.signer.SignerProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import platform.UIKit.UIGestureRecognizerStateEnded
import platform.UIKit.UIRectEdgeLeft
import platform.UIKit.UIScreenEdgePanGestureRecognizer
import platform.UIKit.UIViewController
import platform.darwin.NSObject

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

// 左端エッジスワイプ戻るのターゲット。ジェスチャは target を弱参照するため、
// GC されないようトップレベルで保持する（アプリ生存中ずっと有効）。
private val edgeBackTarget = EdgeBackTarget()

private class EdgeBackTarget : NSObject() {
    @ObjCAction
    fun handleEdge(sender: UIScreenEdgePanGestureRecognizer) {
        // スワイプ完了時に一度だけ戻る（有効時のみ IosBackDispatcher が処理）。
        if (sender.state == UIGestureRecognizerStateEnded) IosBackDispatcher.dispatch()
    }
}

/**
 * iOS の入口。Swift 側（iosApp）から `MainViewControllerKt.MainViewController()` を呼び出し、
 * SwiftUI の UIViewControllerRepresentable でホストする。
 * 実データ（リレー/DB/署名）配線済み: 未ログインなら App() がログインゲートを出す。
 *
 * 左端エッジパンジェスチャを付けて、iOS の「左端スワイプで戻る」を共通の戻る処理へ接続する。
 */
@OptIn(ExperimentalForeignApi::class)
fun MainViewController(): UIViewController {
    val vc = ComposeUIViewController { App(repository) }
    val edge = UIScreenEdgePanGestureRecognizer(edgeBackTarget, NSSelectorFromString("handleEdge:"))
    edge.edges = UIRectEdgeLeft
    vc.view.addGestureRecognizer(edge)
    return vc
}
