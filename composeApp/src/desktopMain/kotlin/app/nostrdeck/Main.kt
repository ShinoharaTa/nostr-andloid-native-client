package app.nostrdeck

import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.nostrdeck.data.EventRepository
import app.nostrdeck.data.defaultRelaysFor
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import app.nostrdeck.signer.DesktopKeyVault
import app.nostrdeck.signer.SignerProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Locale

// [#218] Desktop(Mac) エントリ。Android MainActivity / iOS MainViewController と同じ骨格:
// 鍵保管(KeyVault) → DB(DriverFactory) → EventRepository を1つ組み立てて App() に渡す。
private val appScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, t -> println("Nostrism uncaught in appScope: $t") },
)

// アプリで1つ。データは ~/.nostrism/ 配下（DB と鍵）。
private val appDir: File = File(System.getProperty("user.home"), ".nostrism").apply { mkdirs() }

private val repository: EventRepository by lazy {
    // 注意(spike): DesktopKeyVault は当面 nsec を平文ファイル保管。Phase2 で macOS Keychain 等へ。
    SignerProvider.useVault(DesktopKeyVault(File(appDir, "key.bin")))
    val db = createDatabase(DriverFactory(File(appDir, "nostr.db")))
    val relays = defaultRelaysFor(Locale.getDefault().language)
    EventRepository(db, appScope, relays).apply { start() }
}

fun main() = application {
    val repo = remember { repository }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Nostrism",
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
    ) {
        App(repo)
    }
}
