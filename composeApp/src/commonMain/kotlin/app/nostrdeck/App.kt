package app.nostrdeck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.nostrdeck.data.EventRepository
import app.nostrdeck.data.SampleData
import app.nostrdeck.signer.SignerProvider
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckTheme
import app.nostrdeck.ui.AppScaffold
import app.nostrdeck.ui.LocalNoteNav
import app.nostrdeck.ui.LocalProfileNames
import app.nostrdeck.ui.LocalRepository
import app.nostrdeck.ui.LoginGate
import app.nostrdeck.ui.NoteNav

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
        CompositionLocalProvider(
            LocalRepository provides repository,
            LocalProfileNames provides names,
            LocalNoteNav provides noteNav,
        ) {
            // 実データ運用（repository あり）で未ログインならゲート。SampleData プレビュー時は素通し。
            if (repository != null && !loggedIn) LoginGate() else AppScaffold(state)
        }
    }
}
