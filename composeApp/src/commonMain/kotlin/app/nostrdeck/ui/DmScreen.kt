package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import app.nostrdeck.crypto.Nip19
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.DmConversation
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.state.DeckState
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight

/**
 * DM（NIP-17 想定）の独立画面。list-detail 2ペイン。
 *  左 = 会話一覧 / 右 = スレッド（チャット）。詳細はルーム描画を再利用。
 */
@Composable
fun DmScreen(state: DeckState, isCompact: Boolean) {
    val repo = LocalRepository.current
    val scope = rememberCoroutineScope()
    val names = LocalProfileNames.current
    // 実データ（NIP-17）: repo があれば復号済み DM、無ければ SampleData。
    val convos = if (repo != null) repo.dmConversationsFlow().collectAsState(emptyList()).value
    else SampleData.dmConversations
    // 既存会話に無い相手（新規メッセージ）でもスレッドを開けるよう、無ければ即席の会話を作る。
    val selected = convos.firstOrNull { it.pubkey == state.dmThread }
        ?: state.dmThread?.let { pk ->
            DmConversation(pk, names[pk]?.takeIf { it.isNotBlank() } ?: pk.take(10), "", "")
        }

    // DM 相手のアイコン/名前は接続中リレーに無いことが多いので、複数リレーから取得する。
    // 一覧の全相手ぶんもまとめて取得してアバター/名前を解決する。
    LaunchedEffect(convos.map { it.pubkey }) {
        val ids = (convos.map { it.pubkey } + listOfNotNull(selected?.pubkey)).distinct()
        if (ids.isNotEmpty()) repo?.fetchProfilesNow(ids)
    }
    var showNew by remember { mutableStateOf(false) }
    TwoPane(
        isCompact = isCompact,
        showDetail = state.dmThread != null,
        list = {
            DmList(
                convos, selectedPubkey = state.dmThread,
                onNew = { showNew = true },
                onSelect = { state.dmThread = it.pubkey },
            )
        },
        detail = {
            if (selected == null) {
                DetailPlaceholder(stringResource(Res.string.dm_select_conversation))
            } else {
                val messages = if (repo != null)
                    remember(selected.pubkey) { repo.dmMessagesFlow(selected.pubkey) }.collectAsState(emptyList()).value
                else SampleData.dmMessages(selected.pubkey)
                ChannelRoomColumn(
                    spec = ColumnSpec(
                        id = "dm_${selected.pubkey}", title = selected.name, subtitle = selected.handle,
                        kind = ColumnKind.DM, renderer = ColumnRenderer.ROOM,
                        filter = ReqFilter(kinds = listOf(1059)),
                    ),
                    messages = messages,
                    names = names,
                    // 実データ時のみ送信可能（NIP-17 gift wrap を発行）。
                    // 送信中の例外（暗号/リレー I/O 等）が rememberCoroutineScope で未捕捉のまま
                    // 伝播するとアプリごと落ちうるので、ここで握ってログに留める（無音失敗に留める）。
                    onSend = if (repo != null) ({ text, _ ->
                        scope.launch {
                            runCatching { repo.sendDm(selected.pubkey, text) }
                                .onFailure { println("Nostrism sendDm failed: $it") }
                        }
                    }) else null,
                    // Compact は ← 戻る（一覧へ）、Expanded は ✕ 選択解除。
                    onClose = if (isCompact) null else ({ state.dmThread = null }),
                    onBack = if (isCompact) ({ state.dmThread = null }) else null,
                    // DM(1:1) は自分＝右寄せ・明色バブル（iMessage流）を維持。
                    mineOnRight = true,
                )
            }
        },
    )

    // 新規 DM: npub / hex を入力して会話を開く（送信は開いたスレッドの入力欄から）。
    if (showNew) {
        var input by remember { mutableStateOf("") }
        val hex = remember(input) {
            val t = input.trim()
            when {
                t.startsWith("npub1") -> runCatching { Nip19.npubToHex(t) }.getOrNull()
                t.length == 64 && t.all { it.isDigit() || it in 'a'..'f' } -> t
                else -> null
            }
        }
        DeckInputDialog(
            title = stringResource(Res.string.dm_new_title),
            placeholder = stringResource(Res.string.tpl_profile_hint),
            value = input, onValueChange = { input = it },
            confirmLabel = stringResource(Res.string.dm_open), confirmEnabled = hex != null,
            onConfirm = { hex?.let { state.dmThread = it }; showNew = false },
            onDismiss = { showNew = false },
        )
    }
}

@Composable
private fun DmList(
    convos: List<DmConversation>,
    selectedPubkey: String?,
    onNew: () -> Unit,
    onSelect: (DmConversation) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        Row(
            Modifier.fillMaxWidth().padding(DeckSpace.Md, DeckSpace.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(Res.string.dm_title), color = DeckColors.Text, fontSize = DeckType.Title, fontWeight = DeckWeight.Strong,
                modifier = Modifier.weight(1f))
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onNew),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Add, stringResource(Res.string.dm_new_title), tint = DeckColors.Text) }
        }
        HorizontalDivider(color = DeckColors.Border)
        LazyColumn(Modifier.fillMaxSize()) {
            items(convos, key = { it.pubkey }) { c ->
                val active = c.pubkey == selectedPubkey
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (active) DeckColors.AccentWeak else DeckColors.Surface)
                        .clickable { onSelect(c) }.padding(DeckSpace.Md, DeckSpace.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(c.name, c.pictureUrl, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.width(DeckSpace.Sm))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                            lineHeight = DeckType.LineTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.lastMessage, color = DeckColors.Text2, fontSize = DeckType.Caption,
                            lineHeight = DeckType.LineDesc, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.unread > 0) {
                        Spacer(Modifier.width(DeckSpace.Sm))
                        Box(
                            Modifier.clip(CircleShape).background(DeckColors.Accent)
                                .padding(horizontal = DeckSpace.Xs, vertical = 1.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text("${c.unread}", color = DeckColors.Bg, fontSize = DeckType.Micro, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}
