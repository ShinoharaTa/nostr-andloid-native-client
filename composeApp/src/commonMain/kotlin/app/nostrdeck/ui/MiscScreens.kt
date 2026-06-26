package app.nostrdeck.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.ColumnKind

/** 通知（単一フィード・全幅）。Deck カラムではなく1画面。 */
@Composable
fun NotificationsScreen() {
    val spec = SampleData.columns.first { it.kind == ColumnKind.NOTIFICATIONS }
    FeedColumn(spec, SampleData.feedFor(spec), Modifier.fillMaxSize(), offline = true)
}

@Composable
fun SearchScreen() = DetailPlaceholder("検索（未実装）")
