package app.nostrdeck.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType

/**
 * [#17] リスト系画面の共通ステート表示。
 * [loading]=true でスピナー＋「読み込み中…」、false で [emptyText]（空表示）を中央に出す。
 * items が空のときに LazyColumn の上へ重ねて使う想定。
 */
@Composable
fun ColumnStateView(loading: Boolean, emptyText: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(DeckSpace.Xl), contentAlignment = Alignment.Center) {
        if (loading) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = DeckColors.Text3, strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(DeckSpace.Sm))
                Text(stringResource(Res.string.loading), color = DeckColors.Text3, fontSize = DeckType.Caption)
            }
        } else {
            Text(emptyText, color = DeckColors.Text3, fontSize = DeckType.Caption)
        }
    }
}
