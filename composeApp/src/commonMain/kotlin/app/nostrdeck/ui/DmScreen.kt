package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
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
import app.nostrdeck.data.EventRepository
import app.nostrdeck.data.SampleData
import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.DmConversation
import app.nostrdeck.model.ReqFilter
import app.nostrdeck.state.DeckState
import app.nostrdeck.state.NavDest
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import app.nostrdeck.theme.DeckDimens
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
    // [#417] 送信結果の通知。非コルーチン文脈から使うので文言は先に解決しておく。
    val toast = rememberToaster()
    val sendFailedMsg = stringResource(Res.string.dm_send_failed)
    val noRelaysMsg = stringResource(Res.string.dm_no_relays_warn)
    val unconfirmedMsg = stringResource(Res.string.publish_unconfirmed)
    // 実データ（NIP-17）: repo があれば復号済み DM、無ければ SampleData。null = 読み込み前。
    val loaded = if (repo != null) repo.dmConversationsFlow().collectAsState().value
    else SampleData.dmConversations
    val convos = loaded.orEmpty()
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
    // [#416] 開いている会話は既読にする（全体ではなくその相手ぶんだけ）。開いた時だけでなく、
    // 開いている間に届いた新着も既読にする（見ている会話に未読が積まれないように）。
    val openUnread = selected?.unread ?: 0
    // 未読が無ければ何もしない（既読基準は既に全発言を覆っている。無駄な KV 書き込みを避ける）。
    LaunchedEffect(state.dmThread, openUnread) {
        if (openUnread > 0) state.dmThread?.let { repo?.markDmSeen(it) }
    }
    // [#417] 「相手が DM リレーを公開していない」警告は相手ごとに1回だけ（毎回出すと雑音になる）。
    val warnedNoRelays = remember { mutableSetOf<String>() }
    var showNew by remember { mutableStateOf(false) }
    TwoPane(
        isCompact = isCompact,
        showDetail = state.dmThread != null,
        list = {
            DmList(
                state, loaded, selectedPubkey = state.dmThread,
                onNew = { showNew = true },
                onSelect = { state.dmThread = it.pubkey },
                onOpenProfile = { state.openProfile(it.pubkey) },
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
                        filter = ReqFilter(kinds = listOf(14)),   // [#415] 表示は復号後の kind:14
                    ),
                    messages = messages,
                    names = names,
                    // 実データ時のみ送信可能（NIP-17 gift wrap を発行）。
                    // [#417] 結果をトーストで返す。以前はここで例外を握り潰していたため、
                    // 失敗しても入力欄がクリアされ自分のバブルも出て、送れたように見えていた。
                    // （例外を投げっぱなしにすると appScope 直下の未捕捉例外で落ちるので握るのは維持）
                    onSend = if (repo != null) ({ text, _ ->
                        scope.launch {
                            val peer = selected.pubkey
                            when (repo.sendDm(peer, text)) {
                                EventRepository.DmSendResult.SENT -> Unit
                                EventRepository.DmSendResult.SENT_NO_PEER_RELAYS ->
                                    if (warnedNoRelays.add(peer)) toast(noRelaysMsg)
                                EventRepository.DmSendResult.PENDING -> toast(unconfirmedMsg)   // [#423]
                                EventRepository.DmSendResult.FAILED -> toast(sendFailedMsg)
                            }
                        }
                    }) else null,
                    // Compact は ← 戻る（一覧へ）、Expanded は ✕ 選択解除。
                    onClose = if (isCompact) null else ({ state.dmThread = null }),
                    onBack = if (isCompact) ({ state.dmThread = null }) else null,
                    // DM(1:1) は自分＝右寄せ・明色バブル（iMessage流）を維持。
                    mineOnRight = true,
                    // [#382] ヘッダの名前をタップ → 相手のプロフィールへ。
                    onTitleClick = { state.openProfile(selected.pubkey) },
                    titleClickLabel = stringResource(Res.string.open_profile),
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
    state: DeckState,
    convos: List<DmConversation>?,
    selectedPubkey: String?,
    onNew: () -> Unit,
    onSelect: (DmConversation) -> Unit,
    onOpenProfile: (DmConversation) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(DeckColors.Surface)) {
        // [#422] 見出しはメッセージ画面の「DM | チャット」切り替え。新規はチャットと同じく一覧の先頭行。
        MessagesSegmentBar(state)
        HorizontalDivider(color = DeckColors.Border)
        DmConversationRows(convos, selectedPubkey, onSelect, onOpenProfile, onNew = onNew)
    }
}

/**
 * [#415] 会話一覧の行（DM 画面と Deck の DM カラムで共有）。
 * ヘッダは呼び出し側が用意する（画面は自前のタイトル行、カラムは [ColumnHeader]）。
 */
@Composable
private fun DmConversationRows(
    convos: List<DmConversation>?,
    selectedPubkey: String?,
    onSelect: (DmConversation) -> Unit,
    onOpenProfile: (DmConversation) -> Unit,
    listState: LazyListState = rememberLazyListState(),
    /** [#422] 非null なら先頭に「新しいメッセージを送る」行（DM 画面のみ。DM カラムには出さない）。 */
    onNew: (() -> Unit)? = null,
) {
    // null = まだ読み込んでいない。ここで「まだ会話がありません」を出すと、会話がある人にも
    // 一瞬だけ空表示が出てしまう。
    if (convos == null && onNew == null) return
    if (convos != null && convos.isEmpty() && onNew == null) {
        DetailPlaceholder(stringResource(Res.string.dm_empty))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        // 会話が無くても新規は出す（最初の1通を送る入口が無くなるので）。
        if (onNew != null) {
            item(key = "new_dm") {
                ListCreateRow(stringResource(Res.string.dm_new_row), onNew)
                HorizontalDivider(color = DeckColors.Border)
            }
        }
        if (convos != null && convos.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(Res.string.dm_empty), color = DeckColors.Text3, fontSize = DeckType.Sub,
                    modifier = Modifier.fillMaxWidth().padding(DeckSpace.Xl),
                )
            }
        }
        items(convos.orEmpty(), key = { it.pubkey }) { c ->
            val active = c.pubkey == selectedPubkey
            Row(
                Modifier.fillMaxWidth()
                    .background(if (active) DeckColors.AccentWeak else DeckColors.Surface)
                    .clickable { onSelect(c) }.padding(DeckSpace.Md, DeckSpace.Sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // [#382] アバターだけ個別に clickable（行タップ＝会話を開く、は据え置き）。
                // 40dp = DeckDimens.TouchTargetSm（実用最小のタッチ領域）を実寸で確保する。
                // 呼び出し側で clip すると [#378] 猫耳の先端が切れる（非にゃん時は Avatar が
                // 自分で丸く clip する）ので clip はせず、リップルだけ非クリップの円にする。
                Avatar(
                    c.name, c.pictureUrl,
                    modifier = Modifier.size(DeckDimens.TouchTargetSm)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = DeckDimens.TouchTargetSm / 2),
                            onClickLabel = stringResource(Res.string.open_profile),
                        ) { onOpenProfile(c) },
                    pubkey = c.pubkey,
                )
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
                    ) { Text("${c.unread}", color = DeckColors.Bg, fontSize = DeckType.Micro, fontWeight = DeckWeight.Name) }
                }
            }
        }
    }
}

/**
 * [#415] Deck の DM カラム。会話一覧を出し、タップで DM 画面をその相手で開く。
 *
 * 以前はここが仮データ（SampleData の架空ノート）を描いていた。gift wrap(kind:1059) は
 * event テーブルに保存されない（復号して kind:14 で持つ）ので、素の FEED では永久に空になる。
 * そのため通知カラムと同じく kind ごとの専用描画にし、購読も起動時の `dm_inbox` に任せる
 * （カラム側から kinds=[1059] を購読すると全 gift wrap を引いてしまう）。
 */
@Composable
fun DmColumn(
    state: DeckState,
    spec: ColumnSpec,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onPin: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    menu: ColumnMenuActions? = null,
) {
    val repo = LocalRepository.current
    val convos = if (repo != null) repo.dmConversationsFlow().collectAsState().value
    else SampleData.dmConversations
    // 一覧の相手ぶんのアイコン/名前をまとめて解決する（DM 相手は接続中リレーに居ないことが多い）。
    val peers = convos.orEmpty().map { it.pubkey }
    LaunchedEffect(peers) {
        if (peers.isNotEmpty()) repo?.fetchProfilesNow(peers)
    }
    Column(modifier.background(DeckColors.Surface)) {
        ColumnHeader(
            title = spec.title, subtitle = columnSubtitleFor(spec),
            leadingIcon = columnIcon(spec.kind), pinned = spec.pinned,
            onPin = onPin, onClose = onClose, menu = menu,
        )
        HorizontalDivider(color = DeckColors.Border)
        DmConversationRows(
            convos, selectedPubkey = state.dmThread,
            onSelect = { state.clearDetail(); state.dmThread = it.pubkey; state.navDest = NavDest.DM },
            onOpenProfile = { state.openProfile(it.pubkey) },
            listState = listState,
        )
    }
}
