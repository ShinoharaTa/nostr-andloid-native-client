package app.nostrdeck

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.nostrdeck.data.EventRepository
import app.nostrdeck.state.DeckState
import app.nostrdeck.ui.handleDeckKey
import app.nostrdeck.data.defaultRelaysFor
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import app.nostrdeck.signer.DesktopKeyVault
import app.nostrdeck.signer.KeyVault
import app.nostrdeck.signer.LinuxSecretKeyVault
import app.nostrdeck.signer.MacKeychainKeyVault
import app.nostrdeck.signer.SignerProvider
import app.nostrdeck.signer.WindowsCredentialKeyVault
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.Locale

// [#218] Desktop エントリ（Windows/Linux/macOS）。Android MainActivity / iOS MainViewController と同じ骨格:
// 鍵保管(KeyVault) → DB(DriverFactory) → EventRepository を1つ組み立てて App() に渡す。
private val appScope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, t -> println("Nostrism uncaught in appScope: $t") },
)

// アプリで1つ。データは ~/.nostrism/ 配下（DB と鍵）。
private val appDir: File = File(System.getProperty("user.home"), ".nostrism").apply { mkdirs() }

private fun isCommandAvailable(cmd: String): Boolean = runCatching {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val isWindows = osName.contains("win")
    val checkCmd = if (isWindows) listOf("where", cmd) else listOf("which", cmd)
    ProcessBuilder(*checkCmd.toTypedArray()).redirectErrorStream(true).start().waitFor() == 0
}.getOrDefault(false)

private fun isSecretToolAvailable(): Boolean = isCommandAvailable("secret-tool") && runCatching {
    ProcessBuilder("secret-tool", "--help").redirectErrorStream(true).start().waitFor() == 0
}.getOrDefault(false)

/**
 * [#221] 鍵保管の選択。macOS は Keychain（security CLI）、Windows は Credential Manager（ネイティブ API）、
 * Linux は libsecret（secret-tool）。それ以外の OS は従来のファイル保管。
 * secret-tool / security が無い・動いていない環境では自動でファイル保管へフォールバック。
 */
private fun buildKeyVault(): KeyVault {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val isMac = osName.contains("mac")
    val isWindows = osName.contains("win")
    val isLinux = osName.contains("linux") || osName.contains("nix")
    val legacy = File(appDir, "key.bin")

    val vault: KeyVault = when {
        isMac -> if (isCommandAvailable("security")) MacKeychainKeyVault() else {
            println("Nostrism [#221] security CLI not found, using file vault"); DesktopKeyVault(legacy)
        }
        isWindows -> {
            println("Nostrism [#221] using Windows Credential Manager (native API)")
            WindowsCredentialKeyVault()
        }
        // hasKey() は「鍵が無い」ことにしか反応しないので健全性判定に使えない。
        // Secret Service に到達できるかどうかは isAvailable() で見る（キーリングロック中/D-Bus 不通も落ちる）。
        isLinux -> if (isSecretToolAvailable()) {
            val candidate = LinuxSecretKeyVault()
            if (candidate.isAvailable()) {
                println("Nostrism [#221] using Linux secret-tool")
                candidate
            } else {
                println("Nostrism [#221] secret service not reachable, using file vault"); DesktopKeyVault(legacy)
            }
        } else {
            println("Nostrism [#221] secret-tool not found, using file vault (sudo apt install libsecret-tools)"); DesktopKeyVault(legacy)
        }
        else -> DesktopKeyVault(legacy)
    }

    // 旧・平文ファイル(key.bin)からの移行。
    // DesktopKeyVault は key.bin そのものなので、ここでは絶対に消さない（唯一の鍵が消える）。
    if (vault !is DesktopKeyVault && legacy.exists() && legacy.length() == 32L) {
        runCatching {
            if (!vault.hasKey()) vault.importPrivateKey(legacy.readBytes())
            if (vault.privateKey().contentEquals(legacy.readBytes())) {
                legacy.delete()
                println("Nostrism [#221] key migrated to platform keystore; plaintext key.bin removed")
            } else {
                println("Nostrism [#221] keystore already holds a different key; key.bin left in place")
            }
        }.onFailure { println("Nostrism [#221] keystore migration failed: $it") }
    }
    return vault
}

private val repository: EventRepository by lazy {
    SignerProvider.useVault(buildKeyVault())
    // [#7] NWC（ウォレット接続）。保存済み接続があれば復元する。
    app.nostrdeck.wallet.NwcManager.init(appScope, app.nostrdeck.wallet.DesktopNwcStore(appDir))
    app.nostrdeck.wallet.NwcManager.restore()
    // [#879] 文字から作る絵文字のフォント（初回に取ってきたものを置く）。
    app.nostrdeck.data.EmojiFontFiles.dir = File(appDir, "emoji-fonts")
    app.nostrdeck.data.TtsModelFiles.dir = File(appDir, "zen-tts-models")   // [#948] デスクトップは読み上げに対応しないが、置き場だけは決めておく
    val db = createDatabase(DriverFactory(File(appDir, "nostr.db")))
    val relays = defaultRelaysFor(Locale.getDefault().language)
    EventRepository(db, appScope, relays).apply { start() }
}

fun main() = application {
    val repo = remember { repository }
    // [#14] Desktop はウィンドウレベルでキーを拾う（フォーカス非依存＝マウス操作後も確実）。
    // DeckState は App() 生成後にコールバックで受け取る。
    var deck by remember { mutableStateOf<DeckState?>(null) }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Nostrism",
        state = rememberWindowState(width = 1280.dp, height = 860.dp),
        onPreviewKeyEvent = { e -> deck?.let { handleDeckKey(it, e) { repo.reconnectAll() } } ?: false },
    ) {
        App(repo, onDeckState = { deck = it })
    }
}
