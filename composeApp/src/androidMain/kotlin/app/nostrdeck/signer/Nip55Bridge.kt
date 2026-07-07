package app.nostrdeck.signer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [#39] NIP-55 の Android 連携（外部署名アプリ = Amber 等）。
 *  - 署名/暗号は ContentResolver（UIなし・バックグラウンド）を優先し、権限未付与なら Intent にフォールバック。
 *  - Intent は Activity の ActivityResult ランチャーで待ち合わせる（MainActivity が register する）。
 */
object Nip55Bridge {

    /** 既定の署名アプリ(Amber)。ContentResolver の authority に使う。 */
    const val DEFAULT_SIGNER_PACKAGE = "com.greenart7c3.nostrsigner"

    private var appContext: Context? = null
    private var launcher: ActivityResultLauncher<Intent>? = null
    private var pending: CompletableDeferred<ActivityResult>? = null
    private val intentMutex = Mutex() // Intent は一度に1つずつ（Amber 画面が競合しないように）

    /** MainActivity.onCreate から呼ぶ。ランチャー登録とコンテキスト保持。 */
    fun register(activity: ComponentActivity) {
        appContext = activity.applicationContext
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pending?.complete(result)
            pending = null
        }
    }

    /** 端末に NIP-55 署名アプリ(nostrsigner: を処理できるアプリ)があるか。 */
    fun isSignerInstalled(): Boolean {
        val pm = appContext?.packageManager ?: return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("nostrsigner:"))
        return pm.queryIntentActivities(intent, 0).isNotEmpty()
    }

    /** Intent を投げて ActivityResult を待つ（get_public_key / フォールバック署名・暗号）。 */
    suspend fun sendIntent(intent: Intent): ActivityResult = intentMutex.withLock {
        val l = launcher ?: error("Nip55Bridge: ランチャー未登録")
        val d = CompletableDeferred<ActivityResult>()
        pending = d
        l.launch(intent)
        d.await()
    }

    /**
     * ContentResolver で署名/暗号を問い合わせる（UIなし）。
     * @return 結果文字列（署名済みイベントJSON / 暗号文 / 平文）。null=権限なし・拒否・失敗 → Intent へ。
     */
    fun query(signerPackage: String, type: String, data: String, arg2: String, currentUser: String): String? {
        val ctx = appContext ?: return null
        val uri = Uri.parse("content://$signerPackage.$type")
        val cursor = try {
            ctx.contentResolver.query(uri, arrayOf(data, arg2, currentUser), null, null, null)
        } catch (t: Throwable) {
            null
        } ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) return null
            // 権限未付与/拒否は Intent 経路に回す。
            if (c.getColumnIndex("rejected") != -1) return null
            // 実装差を吸収して結果列を順に探す（sign=event / 暗号=signature or result）。
            for (col in arrayOf("event", "signature", "result")) {
                val idx = c.getColumnIndex(col)
                if (idx != -1) c.getString(idx)?.let { if (it.isNotEmpty()) return it }
            }
            return null
        }
    }
}
