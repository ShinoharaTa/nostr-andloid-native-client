package app.nostrdeck.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckSpace

/** NIP-05 検証の状態。CHECKING=確認中、VERIFIED=一致(OK)、INVALID=不一致/取得失敗(異常)。 */
private enum class Nip05Status { CHECKING, VERIFIED, INVALID }

/**
 * NIP-05(handle) を表示しつつ、その場で `.well-known/nostr.json` 検証を行い結果バッジを付ける。
 *  - 検証OK : チェックマーク（控えめな緑）
 *  - 異常   : ！マーク（控えめな赤）
 *  - 検証中 : バッジ非表示（テキストのみ）
 *
 * 検証は pubkey/nip05 が変わるたびに走り、ネットワーク失敗時は異常扱い(INVALID)。
 */
@Composable
fun Nip05Handle(
    pubkey: String,
    nip05: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalRepository.current
    val status by produceState(Nip05Status.CHECKING, pubkey, nip05) {
        value = Nip05Status.CHECKING
        val ok = repo?.verifyNip05(pubkey, nip05) ?: false
        value = if (ok) Nip05Status.VERIFIED else Nip05Status.INVALID
    }
    val iconSize = 14.dp
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(nip05, color = DeckColors.Text2, fontSize = fontSize)
        when (status) {
            Nip05Status.CHECKING -> {}
            Nip05Status.VERIFIED -> {
                Spacer(Modifier.width(DeckSpace.Xs))
                Icon(
                    Icons.Filled.Verified,
                    contentDescription = stringResource(Res.string.nip05_ok),
                    tint = DeckColors.Verified,
                    modifier = Modifier.size(iconSize),
                )
            }
            Nip05Status.INVALID -> {
                Spacer(Modifier.width(DeckSpace.Xs))
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = stringResource(Res.string.nip05_bad),
                    tint = DeckColors.Warn,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}
