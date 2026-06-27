package app.nostrdeck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.nostrdeck.data.EventRepository
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import app.nostrdeck.signer.KeystoreKeyVault
import app.nostrdeck.signer.SignerProvider
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.Path.Companion.toPath

/** M1: 既定リレー（公開リレー）。設定で変更可能にするのは M5/M7。 */
private val DEFAULT_RELAYS = listOf(
    "wss://relay.damus.io",
    "wss://nos.lol",
)

class MainActivity : ComponentActivity() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var repository: EventRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 画像: メモリ + ディスクキャッシュ(上限256MB)。URL はプロキシで圧縮済み。
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components { add(KtorNetworkFetcherFactory()) }
                .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.20).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory(cacheDir.resolve("image_cache").path.toPath())
                        .maxSizeBytes(256L * 1024 * 1024)
                        .build()
                }
                .crossfade(true)
                .build()
        }

        // 鍵保管を Android Keystore 実装に差し替える。nsec の取り込み/新規生成は
        // ここで注入した vault に永続化され、再起動後も同じ鍵が復元される。
        // 鍵未設定なら useVault が新規生成する（=毎インストールに永続 ID が付く）。
        SignerProvider.useVault(KeystoreKeyVault(applicationContext))

        val db = createDatabase(DriverFactory(applicationContext))
        // 各カラムが表示時に自分のフィルタで購読する（カラム=REQ ライフサイクル）。
        repository = EventRepository(db, appScope, DEFAULT_RELAYS).apply { start() }

        setContent { App(repository) }
    }

    override fun onDestroy() {
        appScope.cancel()
        super.onDestroy()
    }
}
