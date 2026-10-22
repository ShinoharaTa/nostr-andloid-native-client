package app.nostrdeck.store

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.App
import app.nostrdeck.data.EventRepository
import app.nostrdeck.db.DriverFactory
import app.nostrdeck.db.createDatabase
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.CustomThemePrefs
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.model.ThemeMode
import app.nostrdeck.signer.KeyVault
import app.nostrdeck.signer.LocalSigner
import app.nostrdeck.signer.SignerProvider
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.net.InetSocketAddress
import java.util.Locale
import kotlin.test.Test

/**
 * [store] ストア用スクリーンショットの「素の画面」を描く。アプリの画面（[App]）を、リレーにつながないデモ用の
 * データベース（[DemoWorld] の署名済みイベント）で、各端末の解像度・密度そのままに描いて PNG にする。
 * 台紙（キャッチコピー・背景）は distribution/store/frames が付ける。
 *
 * 普段のテストでは何もしない。描くときだけ環境変数で言語を渡す:
 *   STORE_SHOTS=ja ./gradlew :composeApp:desktopTest --tests 'app.nostrdeck.store.StoreScreenshots'
 *   （STORE_SHOTS=en で英語版。STORE_VIEWPORTS=iphone,ipad で端末を絞れる）
 * 出力: distribution/store/raw/<ja|en>/<端末>-<場面>.png
 */
class StoreScreenshots {

    enum class Os { IOS, ANDROID }

    /** 端末の画面。[topDp] / [bottomDp] はステータスバーとホームインジケータ（ジェスチャーバー）の高さ。 */
    class Viewport(val id: String, val widthPx: Int, val heightPx: Int, val density: Float, val os: Os, val topDp: Int, val bottomDp: Int) {
        val wide get() = widthPx / density >= 600f
    }

    private val viewports = listOf(
        Viewport("iphone", 1206, 2622, 3f, Os.IOS, 54, 34),          // iPhone（Dynamic Island 中）402×874pt
        Viewport("iphone-max", 1320, 2868, 3f, Os.IOS, 54, 34),      // iPhone（Dynamic Island 大）440×956pt
        Viewport("ipad", 2752, 2064, 2f, Os.IOS, 24, 20),            // iPad 13" 横 1376×1032pt
        Viewport("duo-inner", 2007, 2853, 3f, Os.IOS, 44, 28),       // iPhone Duo 内側 669×951pt
        Viewport("duo-outer", 1398, 2034, 3f, Os.IOS, 44, 28),       // iPhone Duo 外側 466×678pt
        Viewport("android-phone", 1080, 2400, 2.625f, Os.ANDROID, 28, 24),   // 411×914dp
        Viewport("android-tablet", 2560, 1600, 2f, Os.ANDROID, 28, 24),      // 10" 横 1280×800dp
        Viewport("fold-inner", 2208, 1840, 2.625f, Os.ANDROID, 28, 24),      // 折りたたみ（開いた状態）841×701dp
    )

    private val repoRoot = File("..").canonicalFile
    private val mediaDir = File(repoRoot, "distribution/store/demo-media")

    @Test
    fun render() {
        val lang = System.getenv("STORE_SHOTS") ?: return
        val ja = lang == "ja"
        Locale.setDefault(if (ja) Locale.JAPAN else Locale.US)
        val only = System.getenv("STORE_VIEWPORTS")?.split(",")?.map { it.trim() }?.toSet()
        val outDir = File(repoRoot, "distribution/store/raw/$lang").apply { mkdirs() }

        val server = HttpServer.create(InetSocketAddress("127.0.0.1", DemoWorld.MEDIA_PORT), 0).apply {
            createContext("/") { ex ->
                val f = File(mediaDir, ex.requestURI.path.removePrefix("/"))
                if (f.isFile && f.canonicalPath.startsWith(mediaDir.canonicalPath)) {
                    ex.responseHeaders.add("Content-Type", "image/png")
                    ex.sendResponseHeaders(200, f.length())
                    ex.responseBody.use { it.write(f.readBytes()) }
                } else {
                    ex.sendResponseHeaders(404, -1); ex.close()
                }
            }
            start()
        }
        try {
            val world = DemoWorld(ja, System.currentTimeMillis() / 1000)
            val events = world.build()
            SignerProvider.use(LocalSigner(MemoryVault(world.me.secret)))
            val dbFile = File.createTempFile("nostrism-store", ".db").apply { deleteOnExit() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val repo = EventRepository(createDatabase(DriverFactory(dbFile)), scope, emptyList(), offline = true)
            repo.start()
            Thread.sleep(800)   // start() が自分の pubkey を読むのを待つ（フォローの取り込みに要る）
            repo.ingestLocal(events)
            repo.ingestLocal(events.filter { it.kind == 3 })   // 念のため（自分の pubkey が決まってから）
            repo.persistPinnedColumns(demoColumns(world, ja))
            // タイムラインには投稿だけを出す（通知・自分のリアクションの行を混ぜない。列ごとの設定）
            for (c in app.nostrdeck.model.FeedNoticeCategory.entries) repo.setColumnCategoryHidden("c_following", c, true)
            listOf("wss://relay-jp.shino3.net", "wss://yabu.me", "wss://relay.damus.io").forEach { repo.addRelay(it) }
            repo.setThemeMode(ThemeMode.DARK)

            for (vp in viewports) {
                if (only != null && vp.id !in only) continue
                val scenes: List<Scene> = buildList {
                    add(Scene("home") {})
                    if (!vp.wide) add(Scene("notifications") { it.navDest = NavDest.NOTIFICATIONS })
                    add(Scene("thread") { it.openThreadDetail(world.threadOpenId) })
                    if (!vp.wide) add(Scene("chat") { it.openChannelRoom(world.channelId) })
                    add(Scene("profile", after = { repo.ingestLocal(listOf(world.hiyoriContacts)) }) { it.openProfile(world.profileUser.pubkey) })
                    add(Scene("theme", light = true) {})
                }
                for (sc in scenes) {
                    if (sc.light) {
                        repo.setCustomTheme(CustomThemePrefs(bg = THEME_BG.toInt(), text = THEME_TEXT.toInt(), accent = 0xFFFF5C93.toInt()))
                        repo.setThemeMode(ThemeMode.CUSTOM)
                    }
                    shoot(repo, vp, sc, File(outDir, "${vp.id}-${sc.name}.png"))
                    if (sc.light) repo.setThemeMode(ThemeMode.DARK)
                    println("store: ${vp.id}-${sc.name}")
                }
            }
        } finally {
            server.stop(0)
        }
    }

    private fun demoColumns(world: DemoWorld, ja: Boolean) = listOf(
        ColumnSpec("c_following", "フォロー中", "following", ColumnKind.FOLLOWING, ColumnRenderer.FEED, ReqFilter(kinds = listOf(1)), order = 0),
        ColumnSpec("c_photo", "#photography", "hashtag", ColumnKind.HASHTAG, ColumnRenderer.FEED,
            ReqFilter(kinds = listOf(1), hashtags = listOf("photography")), order = 1),
        ColumnSpec("c_lounge", if (ja) "Nostrism 雑談" else "Nostrism Lounge", "NIP-28", ColumnKind.CHANNEL_ROOM, ColumnRenderer.ROOM,
            ReqFilter(kinds = listOf(42), channelId = world.channelId), order = 2),
        ColumnSpec("c_notif", "通知", "mentions · zaps", ColumnKind.NOTIFICATIONS, ColumnRenderer.FEED, ReqFilter(kinds = listOf(1, 7, 9735)), order = 3),
    )

    /** 場面: [setup] で画面を移し、少し待ってから [after]（画面を開いたあとに届くデータ）を入れる。[light] はテーマの場面。 */
    private class Scene(val name: String, val light: Boolean = false, val after: (() -> Unit)? = null, val setup: (DeckState) -> Unit)

    /** 1 場面を描く。開いて少し待つ → 画面を移す → 画像とデータが揃うまで待つ → 保存。 */
    private fun shoot(repo: EventRepository, vp: Viewport, sc: Scene, out: File) {
        var deck: DeckState? = null
        val bg = Color(if (sc.light) THEME_BG else DARK_BG)
        val fg = Color(if (sc.light) THEME_TEXT else DARK_TEXT)
        val scene = ImageComposeScene(vp.widthPx, vp.heightPx, Density(vp.density)) {
            DeviceFrame(vp, bg, fg) { App(repo, onDeckState = { deck = it }) }
        }
        try {
            pump(scene, 1_200)
            deck?.let(sc.setup)
            pump(scene, 800)
            sc.after?.invoke()
            pump(scene, 1_800)
            val img = scene.render(System.nanoTime())
            out.writeBytes(img.encodeToData()!!.bytes)
        } finally {
            scene.close()
        }
    }

    private fun pump(scene: ImageComposeScene, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            scene.render(System.nanoTime())
            Thread.sleep(40)
        }
    }

    /** 端末の画面: 上にステータスバー、下にホームインジケータ（どちらもアプリの背景色）。 */
    @Composable
    private fun DeviceFrame(vp: Viewport, bg: Color, fg: Color, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().background(bg)) {
            StatusBar(vp, bg, fg)
            Box(Modifier.weight(1f).fillMaxWidth()) { content() }
            Box(Modifier.fillMaxWidth().height(vp.bottomDp.dp).background(bg), contentAlignment = Alignment.Center) {
                val w = if (vp.os == Os.IOS) 140.dp else 108.dp
                Box(Modifier.width(w).height(5.dp).clip(RoundedCornerShape(3.dp)).background(fg))
            }
        }
    }

    @Composable
    private fun StatusBar(vp: Viewport, bg: Color, fg: Color) {
        val ios = vp.os == Os.IOS
        Row(
            Modifier.fillMaxWidth().height(vp.topDp.dp).background(bg)
                .padding(horizontal = if (ios && !vp.wide) 34.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (ios && vp.wide) "9:41  " + (if (Locale.getDefault().language == "ja") "10月21日(火)" else "Tue Oct 21") else "9:41",
                color = fg, fontSize = if (ios) 16.sp else 13.sp, fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Canvas(Modifier.size(width = if (ios) 72.dp else 64.dp, height = 14.dp)) {
                val h = size.height
                // 電波（4 本）
                for (i in 0 until 4) {
                    val bh = h * (0.35f + 0.65f * i / 3f)
                    drawRoundRect(fg, Offset(i * h * 0.42f, h - bh), Size(h * 0.3f, bh), CornerRadius(2f))
                }
                // Wi-Fi（扇形 3 本）
                val cx = h * 2.6f
                for (i in 0 until 3) {
                    val r = h * (0.35f + 0.33f * i)
                    drawArc(fg, 225f, 90f, false, Offset(cx - r, h - r), Size(r * 2, r * 2), style = Stroke(width = h * 0.16f))
                }
                // 電池（満充電）
                val bx = h * 3.5f
                drawRoundRect(fg, Offset(bx, h * 0.12f), Size(h * 1.75f, h * 0.76f), CornerRadius(h * 0.2f), style = Stroke(width = h * 0.09f))
                drawRoundRect(fg, Offset(bx + h * 0.14f, h * 0.26f), Size(h * 1.47f, h * 0.48f), CornerRadius(h * 0.1f))
                drawRoundRect(fg, Offset(bx + h * 1.8f, h * 0.38f), Size(h * 0.12f, h * 0.24f), CornerRadius(1f))
            }
        }
    }

    private companion object {
        // ステータスバーとホームインジケータの色（アプリのダークのパレット・テーマの場面の着せ替えの色と同じ）
        const val DARK_BG = 0xFF0C0C10
        const val DARK_TEXT = 0xFFECEDF1
        const val THEME_BG = 0xFFFFF5F8
        const val THEME_TEXT = 0xFF3B2A33
    }

    private class MemoryVault(private var key: ByteArray?) : KeyVault {
        override fun hasKey() = key != null
        override fun privateKey() = key ?: error("no key")
        override fun importPrivateKey(privateKey: ByteArray) { key = privateKey }
        override fun generate(): ByteArray = error("demo")
        override fun clear() { key = null }
    }
}
