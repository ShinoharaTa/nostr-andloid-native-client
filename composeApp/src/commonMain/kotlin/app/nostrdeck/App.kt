package app.nostrdeck

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.nostrdeck.data.EventRepository
import app.nostrdeck.data.SampleData
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckTheme
import app.nostrdeck.ui.AppScaffold
import app.nostrdeck.ui.LocalProfileNames
import app.nostrdeck.ui.LocalRepository

/**
 * アプリのルート（Android/iOS 共通の入口）。
 * [repository] が渡されれば実データ、null なら SampleData にフォールバック。
 */
@Composable
fun App(repository: EventRepository? = null) {
    DeckTheme {
        val state = remember { DeckState(SampleData.columns) }
        // 本文メンション(@npub…)解決用の pubkey→name マップを供給（実データ時のみ）。
        val names by (repository?.profileNames()?.collectAsState(emptyMap<String, String>())
            ?: remember { mutableStateOf(emptyMap<String, String>()) })
        CompositionLocalProvider(
            LocalRepository provides repository,
            LocalProfileNames provides names,
        ) {
            AppScaffold(state)
        }
    }
}
