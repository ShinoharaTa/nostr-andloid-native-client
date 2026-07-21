package app.nostrdeck

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.data.EventRepository
import app.nostrdeck.state.ExternalIntent
import app.nostrdeck.state.ExternalIntents
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import app.nostrdeck.signer.AndroidExternalSigner
import app.nostrdeck.signer.AndroidNosskey
import app.nostrdeck.signer.ExternalSignerHost
import app.nostrdeck.signer.KeystoreKeyVault
import app.nostrdeck.signer.Nip46Manager
import app.nostrdeck.signer.Nip55Bridge
import app.nostrdeck.signer.NosskeyBridge
import app.nostrdeck.signer.NosskeyHost
import app.nostrdeck.signer.SharedPrefsNip46Store
import app.nostrdeck.signer.SignerProvider
import android.os.Build
import app.nostrdeck.ui.ImageProxy
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.HttpException
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.crossfade
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.cancellation.CancellationException
import okio.Path.Companion.toPath

/** [#150] 既定リレー。端末の言語で切替（日本語なら日本リレーを含む）。初回シードのみに使う。 */
private val DEFAULT_RELAYS = app.nostrdeck.data.defaultRelaysFor(java.util.Locale.getDefault().language)

class MainActivity : ComponentActivity() {

    // [#78] 未捕捉例外でアプリごと落とさない防御の底。リレーIO等の非同期例外はログに留める
    // （SupervisorJob は兄弟キャンセルを防ぐだけで、未捕捉例外はプロセスを殺すため必須）。
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.e("Nostrism", "uncaught in appScope", t) }
    )
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
                    // 圧縮プロキシが特定ドメイン/TLD を拒否した場合は元 URL で取り直す。
                    add(ProxyFallbackInterceptor)
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

        // [#39] NIP-55: 外部署名アプリ(Amber)連携を配線。ランチャー登録は STARTED 前(=onCreate)に行う。
        // 保存済みの NIP-55 セッションがあれば、リポジトリ生成前にローカル鍵から外部署名へ差し替える
        // （start() が SignerProvider から正しい公開鍵を読むように）。
        Nip55Bridge.register(this)
        ExternalSignerHost.provider = AndroidExternalSigner(applicationContext)
        // [#Nosskey] パスキー(WebAuthn PRF)保護。Credential Manager 用に Activity を渡す。
        NosskeyBridge.activity = this
        NosskeyHost.provider = AndroidNosskey(applicationContext)
        // [#41] NIP-46（bunker）マネージャを初期化（appScope + 永続化ストア注入）。
        Nip46Manager.init(appScope, SharedPrefsNip46Store(applicationContext))
        // 保存済みの外部/保護セッションを復元（優先: NIP-55 → NIP-46 →（無ければ）Nosskey は identity のみ）。
        if (!AndroidExternalSigner.restore(applicationContext) && !Nip46Manager.restore()) {
            AndroidNosskey.restore(applicationContext)
        }

        val db = createDatabase(DriverFactory(applicationContext))
        // 各カラムが表示時に自分のフィルタで購読する（カラム=REQ ライフサイクル）。
        repository = EventRepository(db, appScope, DEFAULT_RELAYS).apply { start() }

        setContent {
            // [#152] ステータスバー/ナビバーのアイコン色をテーマに追従させる
            // （ライトテーマで白アイコンのままだと見えない）。
            val mode by repository.themeModeFlow().collectAsState()
            val dark = when (mode) {
                app.nostrdeck.model.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                app.nostrdeck.model.ThemeMode.LIGHT -> false
                app.nostrdeck.model.ThemeMode.DARK -> true
            }
            androidx.compose.runtime.LaunchedEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(t) else SystemBarStyle.light(t, t),
                )
            }
            App(repository)
        }

        // [#100][#101] 起動 Intent（共有/ディープリンク）を処理。
        // 再生成（回転/プロセス復元）では再処理しない（savedInstanceState で判定）。
        if (savedInstanceState == null) handleExternalIntent(intent)
    }

    // [#100][#101] singleTask で起動中に共有/ディープリンクが届いたときの入口。
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExternalIntent(intent)
    }

    /**
     * [#100][#101] 外部 Intent を解析して ExternalIntents に流す。
     * 消費（コンポーザー起動/画面遷移）はログイン状態を見られる App 側で行う。
     */
    private fun handleExternalIntent(intent: Intent?) {
        when (intent?.action) {
            // [#100] 共有ターゲット: EXTRA_TEXT（+あれば EXTRA_SUBJECT を先頭行に）を投稿初期値へ。
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") != true) return
                val body = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                val text = listOf(subject, body).filter { it.isNotBlank() }.joinToString("\n")
                if (text.isNotBlank()) ExternalIntents.post(ExternalIntent.ShareText(text))
            }
            // [#101] nostr: ディープリンク（nostr:npub1… / nostr://npub1… の両形式を許容）。
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                if (uri.scheme != "nostr") return
                val bech = uri.schemeSpecificPart.orEmpty().removePrefix("//").trim()
                val external = when {
                    bech.startsWith("npub1") || bech.startsWith("nprofile1") ->
                        Nip19.mentionBechToHex(bech)?.let { ExternalIntent.OpenProfile(it) }
                    bech.startsWith("note1") || bech.startsWith("nevent1") ->
                        Nip19.eventBechToIdAndRelays(bech)?.let { (id, relays) ->
                            ExternalIntent.OpenEvent(id, relays)
                        }
                    // [#200] naddr（記事等の parameterized replaceable）→ 解決して開く。
                    bech.startsWith("naddr1") ->
                        Nip19.naddrDecode(bech)?.let { a ->
                            ExternalIntent.OpenAddr(a.kind, a.pubkey, a.dTag, a.relays)
                        }
                    else -> null
                }
                if (external != null) {
                    ExternalIntents.post(external)
                } else {
                    // naddr 等の未対応タイプ、または壊れた bech32。
                    Toast.makeText(this, "未対応のリンク形式です", Toast.LENGTH_SHORT).show()
                }
            }
        }
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

/**
 * 圧縮プロキシ(wsrv.nl)経由の取得が失敗したら、元画像 URL で取り直すインターセプタ。
 * wsrv.nl は一部ドメイン/TLD（例: .cc）をポリシーで 400 拒否するため、その場合でも
 * 画像を表示できるようにする（プロキシは通常時のキャッシュ/圧縮のため引き続き使う）。
 *
 * 失敗は「ErrorResult が返る」場合と「fetcher が例外を投げる」場合の両方があり得るので
 * 双方を拾う。キャンセル例外は握りつぶさず再送出する。
 *
 * 学習（以後そのホストはプロキシを回避）は恒久的なポリシー拒否(400/403)のときだけ行う。
 * 一時的失敗(429/5xx/タイムアウト等)は今回だけ元 URL で取り直し、プロキシは使い続ける
 * （= 一時的な失敗で圧縮を無駄に諦めない）。
 */
private object ProxyFallbackInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val result = try {
            chain.proceed()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            return retryWithOrigin(chain, t) ?: throw t
        }
        if (result is ErrorResult) return retryWithOrigin(chain, result.throwable) ?: result
        return result
    }

    /** リクエストがプロキシ URL なら元 URL で取り直す。プロキシ URL でなければ null。 */
    private suspend fun retryWithOrigin(chain: Interceptor.Chain, cause: Throwable?): ImageResult? {
        val origin = ImageProxy.originOf(chain.request.data) ?: return null
        // ポリシー拒否(400/403)のホストだけ学習して以後プロキシを回避する。
        if (isPolicyBlock(cause)) ImageProxy.markProxyBlocked(origin)
        val retry = chain.request.newBuilder().data(origin).build()
        return chain.withRequest(retry).proceed()
    }

    /** 失敗原因が「プロキシによる恒久的な拒否」(HTTP 400/403)かどうか。 */
    private fun isPolicyBlock(cause: Throwable?): Boolean {
        var e: Throwable? = cause
        var depth = 0
        while (e != null && depth < 8) {
            if (e is HttpException) return e.response.code == 400 || e.response.code == 403
            e = e.cause
            depth++
        }
        return false
    }
}
