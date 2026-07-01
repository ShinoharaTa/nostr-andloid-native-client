package app.nostrdeck

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.nostrdeck.data.EventRepository
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import app.nostrdeck.signer.KeystoreKeyVault
import app.nostrdeck.signer.SignerProvider
import android.os.Build
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
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

    // フォアグラウンド中にネットワークが復帰したら、バックオフ待機中のリレーを即再接続させる。
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (::repository.isInitialized) repository.onForeground()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 画像: メモリ + ディスクキャッシュ(上限256MB)。URL はプロキシで圧縮済み。
        SingletonImageLoader.setSafe { ctx ->
            ImageLoader.Builder(ctx)
                .components {
                    add(KtorNetworkFetcherFactory())
                    // アニメ GIF / WebP を動かす。API28+ は ImageDecoder ベース、
                    // それ未満(26/27)は giflib ベースの GifDecoder にフォールバック。
                    if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory())
                    else add(GifDecoder.Factory())
                }
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

    override fun onStart() {
        super.onStart()
        // フォアグラウンド復帰: バックグラウンドで切れたリレーを即再接続させる（バックオフ短縮）。
        if (::repository.isInitialized) repository.onForeground()
        // 復帰中はネットワーク復帰も監視して即再接続（フォアグラウンド中のみ登録）。
        runCatching { connectivityManager.registerDefaultNetworkCallback(netCallback) }
    }

    override fun onStop() {
        super.onStop()
        runCatching { connectivityManager.unregisterNetworkCallback(netCallback) }
    }

    override fun onDestroy() {
        appScope.cancel()
        super.onDestroy()
    }
}
