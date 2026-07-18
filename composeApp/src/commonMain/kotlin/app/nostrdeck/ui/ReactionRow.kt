package app.nostrdeck.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.model.ReactionUi
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckType
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * [M8-react] ノート下の集約リアクション chip 群（NIP-25/30）。
 * モノクロ基調。カスタム絵文字は URL があれば小さな画像、無ければ :shortcode: を文字表示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReactionRow(reactions: List<ReactionUi>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        reactions.forEach { ReactionChip(it) }
    }
}

@Composable
private fun ReactionChip(reaction: ReactionUi) {
    Surface(color = DeckColors.Surface2, shape = RoundedCornerShape(DeckRadius.Sm)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = DeckSpace.Sm, vertical = DeckSpace.Xs),
        ) {
            if (reaction.imageUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalPlatformContext.current)
                        .data(ImageProxy.proxied(reaction.imageUrl, width = 48, quality = 80, animated = true))
                        .crossfade(true).build(),
                    contentDescription = reaction.display,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                SectionCaption(reaction.display)
            }
            Spacer(Modifier.width(DeckSpace.Xs))
            Text(reaction.count.toString(), color = DeckColors.Text2, fontSize = DeckType.Label)
        }
    }
}
