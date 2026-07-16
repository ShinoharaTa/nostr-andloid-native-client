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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.nostrdeck.data.EventRepository
import app.nostrdeck.model.TextScale
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

/**
 * アプリのルート（Android/iOS 共通の入口）。
 * [repository] が渡されれば実データ、null なら SampleData にフォールバック。
 */
@Composable
fun App(repository: EventRepository? = null) {
    DeckTheme {
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
        val noteNav = remember(state) {
            NoteNav(
                onMention = { hex -> state.openProfile(hex) },
                onHashtag = { tag -> state.openHashtag(tag) },
                onEvent = { id -> state.openThreadDetail(id) },
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
                    }
                    ExternalIntents.consume()
                }
        }
        // [#appearance] 文字サイズ設定（小/中/大）を fontScale に乗算して全 sp テキストへ波及させる。
        // dp 寸法（レイアウト・アイコン・余白）は変えず、文字だけ拡大する。
        val textScale by (repository?.textScaleFlow()?.collectAsState()
            ?: remember { mutableStateOf(TextScale.SMALL) })
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalRepository provides repository,
            LocalProfileNames provides names,
            LocalNoteNav provides noteNav,
            LocalDensity provides Density(density.density, density.fontScale * textScale.factor),
        ) {
            // システムバー裏まで暗色で塗る（iOS はウィンドウ既定が白でステータスバー裏が白帯に
            // なるため）。子は systemBars 分を padding するので、この背景が最上端まで敷かれる。
            Box(Modifier.fillMaxSize().background(DeckColors.Bg)) {
                // 実データ運用（repository あり）で未ログインならゲート。SampleData プレビュー時は素通し。
                if (repository != null && !loggedIn) LoginGate() else AppScaffold(state)
            }
        }
    }
}
