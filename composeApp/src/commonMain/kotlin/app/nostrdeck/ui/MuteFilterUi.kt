package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import app.nostrdeck.model.MuteMatcher

/** 現在のミュート集合から判定器を作る（muteFlow の変化で再構築）。 */
@Composable
fun rememberMuteMatcher(): MuteMatcher {
    val repo = LocalRepository.current
    val mute by (repo?.muteListFlow()?.collectAsState() ?: return remember { MuteMatcher.from(null) })
    return remember(mute) { MuteMatcher.from(mute) }
}

/** このカラムでミュートを表示中か（目アイコンで切替・KV 永続）。 */
@Composable
fun rememberColumnRevealMuted(columnId: String): Boolean {
    val repo = LocalRepository.current ?: return false
    val set by repo.revealMutedColumns().collectAsState()
    return columnId in set
}
