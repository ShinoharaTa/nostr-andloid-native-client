package app.nostrdeck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Density
import app.nostrdeck.data.EventRepository
import app.nostrdeck.model.TextScale
import app.nostrdeck.model.ThemeMode
import app.nostrdeck.model.UiScale
import app.nostrdeck.data.SampleData
import app.nostrdeck.signer.SignerProvider
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.ExternalIntent
import app.nostrdeck.state.ExternalIntents
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckTheme
import app.nostrdeck.ui.AppScaffold
import app.nostrdeck.ui.LocalNoteNav
import app.nostrdeck.ui.LocalProfileNames
import app.nostrdeck.ui.LocalRepository
import app.nostrdeck.ui.LoginGate
import app.nostrdeck.ui.NoteNav
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * アプリのルート（Android/iOS 共通の入口）。
 * [repository] が渡されれば実データ、null なら SampleData にフォールバック。
 */
@Composable
fun App(repository: EventRepository? = null) {
    // [#152] テーマ設定（OSに合わせる/ライト/ダーク）。既定はダーク（従来挙動）。
    val themeMode by (repository?.themeModeFlow()?.collectAsState()
        ?: remember { mutableStateOf(ThemeMode.DARK) })
    DeckTheme(themeMode) {
        // カラム構成は pinned_column に永続化する。初回（空）のみ既定をseedして保存し、
        // 以降は保存済みを復元。追加/固定/解除/並べ替えのたびに DeckState から保存する。
        val state = remember {
            val persisted = repository?.loadPinnedColumns().orEmpty()
            val initial = persisted.ifEmpty { SampleData.columns }
            if (persisted.isEmpty()) repository?.persistPinnedColumns(SampleData.columns.filter { it.pinned })
            DeckState(initial, onPinnedChanged = { cols -> repository?.persistPinnedColumns(cols) })
        }
        // 本文メンション(@npub…)解決用の pubkey→name マップを供給（実データ時のみ）。
        val names by (repository?.profileNames()?.collectAsState(emptyMap<String, String>())
            ?: remember { mutableStateOf(emptyMap<String, String>()) })
        // 本文リンクのタップ遷移: @→プロフィール / #→ハッシュタグカラム / note・nevent→スレッド。
        val navScope = rememberCoroutineScope()
        val noteNav = remember(state, repository) {
            NoteNav(
                onMention = { hex -> state.openProfile(hex) },
                onHashtag = { tag -> state.openHashtag(tag) },
                onEvent = { id -> state.openThreadDetail(id) },
                // naddr は kind+著者+dTag から実イベント id を解決してスレッドを開く（Markdown 記事と同じ経路）。
                onAddr = { addr ->
                    navScope.launch {
                        repository?.resolveAddress(addr.kind, addr.pubkey, addr.dTag, addr.relays)
                            ?.let { id -> state.openThreadDetail(id) }
                    }
                },
            )
        }
        // [#login] 未ログイン（鍵を自動生成しない）ならログインゲートを出す。
        // ログイン成立の瞬間だけ identity を読み直す（起動時に既ログインなら start() 済みなので二重取得しない）。
        val loggedIn by SignerProvider.session.collectAsState()
        var wasLoggedIn by remember { mutableStateOf(loggedIn) }
        LaunchedEffect(loggedIn) {
            if (loggedIn && !wasLoggedIn) repository?.reloadForNewIdentity()
            wasLoggedIn = loggedIn
        }
        // [#122] リレー保存モードで取り込んだカラム構成を UI に反映（ローカル保存は Repository 側で済み）。
        LaunchedEffect(state, repository) {
            repository?.remoteDeckColumnsFlow()?.collect { specs -> state.applyPinnedColumns(specs) }
        }
        // [#100][#101] 外部 Intent（共有/ディープリンク）の消費。未ログイン中は値を保持したまま
        // 待ち、ログイン成立（session=true）で combine が再発火して処理される。
        LaunchedEffect(state, repository) {
            combine(ExternalIntents.pending, SignerProvider.session) { intent, session -> intent to session }
                .collect { (intent, session) ->
                    if (intent == null || !session) return@collect
                    when (intent) {
                        is ExternalIntent.ShareText -> {
                            // 共有テキストをコンポーザー初期値に。返信/引用モードの残骸はクリア。
                            state.replyTo = null
                            state.quoting = null
                            state.composeInitialText = intent.text
                            state.showCompose = true
                        }
                        is ExternalIntent.OpenProfile -> state.openProfile(intent.pubkeyHex)
                        is ExternalIntent.OpenEvent -> {
                            // 未取得でも requestEvent で取得を促してからスレッドを開く
                            // （ThreadDetail 側も未キャッシュ分は自前で解決を試みる）。
                            repository?.requestEvent(intent.id, intent.relays)
                            state.openThreadDetail(intent.id)
                        }
                        is ExternalIntent.OpenAddr -> {
                            // [#200] naddr は kind+著者+dタグ を実イベント id へ解決してから開く
                            // （本文中 naddr タップ=NoteNav.onAddr と同じ経路）。resolveAddress は
                            // DB 未取得なら #d 付き REQ をリレーへ投げて取り込みを待つ（=未取得も自動 fetch）。
                            repository?.resolveAddress(intent.kind, intent.pubkey, intent.dTag, intent.relays)
                                ?.let { id -> state.openThreadDetail(id) }
                        }
                    }
                    ExternalIntents.consume()
                }
        }
        // [#appearance] 表示サイズと文字サイズを LocalDensity へ反映（独立2軸）。
        //  - 表示サイズ(uiScale): density に乗算 → dp を含む UI 全体（アイコン・余白・
        //    カラム幅・下部ナビのアイコン）と文字がまとめて拡大する。
        //  - 文字サイズ(textScale): fontScale に乗算 → sp 指定の文字だけ追加で拡大する。
        // 両者は掛け合わせて効く（Android の「表示サイズ」×「フォントサイズ」と同じ考え方）。
        val textScale by (repository?.textScaleFlow()?.collectAsState()
            ?: remember { mutableStateOf(TextScale.SMALL) })
        val uiScale by (repository?.uiScaleFlow()?.collectAsState()
            ?: remember { mutableStateOf(UiScale.SMALL) })
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalRepository provides repository,
            LocalProfileNames provides names,
            LocalNoteNav provides noteNav,
            LocalDensity provides Density(
                density.density * uiScale.factor,
                density.fontScale * textScale.factor,
            ),
        ) {
            // システムバー裏まで暗色で塗る（iOS はウィンドウ既定が白でステータスバー裏が白帯に
            // なるため）。子は systemBars 分を padding するので、この背景が最上端まで敷かれる。
            // [#177] 背景（フォーム外）タップで入力フォーカス/キーボードを閉じる。iOS はフィールド
            // 外タップで自動解除されず、キーボードが残って更新ボタン等が押せなくなるため。
            // detectTapGestures は子（ボタン/TextField）が消費したタップでは発火せず、スクロールは
            // 移動を伴うので tap 扱いにならない → 通常操作と競合しない。Dialog は別ウィンドウのため
            // 各 Dialog 側にも同処理を入れる。
            val rootFocus = LocalFocusManager.current
            Box(
                Modifier.fillMaxSize().background(DeckColors.Bg)
                    .pointerInput(Unit) { detectTapGestures { rootFocus.clearFocus() } },
            ) {
                // 実データ運用（repository あり）で未ログインならゲート。SampleData プレビュー時は素通し。
                if (repository != null && !loggedIn) LoginGate() else AppScaffold(state)
            }
        }
    }
}
