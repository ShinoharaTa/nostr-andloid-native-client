package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.db.Media_server
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.UploadProtocol
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.compose_upload_target
import nostr_deck_client.composeapp.generated.resources.compose_upload_target_hint

/** [#872] 送信先の表示名（スキームと末尾の / を落とした URL。例: share.yabu.me）。 */
internal fun uploadServerName(url: String): String = url.substringAfter("://").trimEnd('/')

/** [#872] 方式のバッジ（NIP-96 / Blossom）。設定の一覧・投稿画面の切り替えで共有する。 */
@Composable
internal fun UploadProtocolBadge(protocol: UploadProtocol) {
    Text(
        protocol.label,
        color = if (protocol == UploadProtocol.BLOSSOM) DeckColors.Accent else DeckColors.Text2,
        fontSize = DeckType.Micro, fontWeight = DeckWeight.Name,
        modifier = Modifier.clip(RoundedCornerShape(DeckRadius.Full))
            .background(if (protocol == UploadProtocol.BLOSSOM) DeckColors.AccentWeak else DeckColors.Surface3)
            .padding(horizontal = DeckSpace.Sm, vertical = 2.dp),
    )
}

/**
 * [#872] 投稿画面の「送信先」。添付があるときに出し、押すと登録済みのサーバー（方式のバッジ付き）から選べる。
 * 選んだ送信先はその投稿だけに効く（呼び出し側が状態を持ち、設定は変えない）。[selectedUrl] が null なら設定の送信先。
 */
@Composable
internal fun UploadTargetSelector(
    servers: List<Media_server>,
    selectedUrl: String?,
    onSelect: (String) -> Unit,
) {
    // 設定の送信先 = 最初の有効なサーバー（アップロードもこの順に試す）。
    val settingsUrl = servers.firstOrNull { it.enabled != 0L }?.url
    val current = servers.firstOrNull { it.url == (selectedUrl ?: settingsUrl) } ?: return
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HintText(stringResource(Res.string.compose_upload_target))
        Spacer(Modifier.width(DeckSpace.Sm))
        Box {
            Row(
                Modifier.clip(RoundedCornerShape(DeckRadius.Full)).background(DeckColors.Surface2)
                    .clickable { open = true }
                    .padding(start = DeckSpace.Md, end = DeckSpace.Xs, top = DeckSpace.Xs, bottom = DeckSpace.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    uploadServerName(current.url), color = DeckColors.Text, fontSize = DeckType.Label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(DeckSpace.Xs))
                UploadProtocolBadge(UploadProtocol.of(current.protocol))
                Icon(Icons.Outlined.ArrowDropDown, null, tint = DeckColors.Text3, modifier = Modifier.size(DeckDimens.IconMd))
            }
            DeckDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Text(
                    stringResource(Res.string.compose_upload_target_hint),
                    color = DeckColors.Text3, fontSize = DeckType.Label,
                    modifier = Modifier.padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Xs),
                )
                servers.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(uploadServerName(s.url), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(DeckSpace.Sm))
                                UploadProtocolBadge(UploadProtocol.of(s.protocol))
                            }
                        },
                        leadingIcon = {
                            Box(Modifier.size(DeckDimens.IconMd)) {
                                if (s.url == current.url) Icon(Icons.Outlined.Check, null, tint = DeckColors.Accent)
                            }
                        },
                        onClick = { open = false; onSelect(s.url) },
                    )
                }
            }
        }
    }
}
