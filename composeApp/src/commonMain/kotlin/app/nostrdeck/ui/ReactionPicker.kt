package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import app.nostrdeck.model.MadeEmoji
import app.nostrdeck.model.EmojiMaker
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.LaunchedEffect
import app.nostrdeck.model.TypedEmoji
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.model.CustomEmoji
import app.nostrdeck.model.NoteUi
import app.nostrdeck.model.UsedEmoji
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.scaledByText
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import kotlinx.coroutines.flow.flowOf

/**
 * リアクション送信ピッカー（NIP-25/30）。
 *  - 検索（日英キーワード）で Unicode 絵文字＋自分のカスタム絵文字を絞り込み
 *  - 「最近」= 過去に飛ばした絵文字（used_emoji、最近/よく使う順）
 *  - 「カスタム」= NIP-51(kind:10030/30030) の自分の絵文字リスト（:shortcode:）
 *  - Unicode はカテゴリ別グリッド
 * 選択で [onPick]（content と、カスタムなら画像URL）を呼んで kind:7 を送る。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReactionPickerSheet(
    onPick: (content: String, imageUrl: String?) -> Unit,
    onDismiss: () -> Unit,
    targetNote: NoteUi? = null,
    onMake: ((MadeEmoji) -> Unit)? = null,
) {
    val repo = LocalRepository.current
    // [#775] 「絵文字を作る」の画面を開いているか。フォームの入力は一覧に戻っても覚えておく。
    var making by remember { mutableStateOf(false) }
    val makerState = rememberEmojiMakerState()
    PlatformBackHandler(enabled = making) { making = false }
    val recents by remember(repo) { repo?.recentEmojisFlow() ?: flowOf(emptyList()) }
        .collectAsState(emptyList())
    val customs by remember(repo) { repo?.customEmojisFlow() ?: flowOf(emptyList()) }
        .collectAsState(emptyList())
    var query by remember { mutableStateOf("") }
    // [#868] 開いたら標準の絵文字すべてを読み込む（終わるまでは手で選んだ 120 個のまま）。
    LaunchedEffect(Unit) { EmojiPickerCatalog.ensureLoaded() }

    // [#284] ボトムシート → Dialog（AppModalSheet）。グリッドのスクロールとシートのドラッグの競合を断つ。
    AppModalSheet(
        title = stringResource(Res.string.picker_title),
        onDismiss = onDismiss,
    ) {
        // モーダルは可能な限り上部まで広く使う（絵文字グリッドが weight で残り高さを占有）。
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f),
        ) {
          if (making && onMake != null) {
            MakeEmojiPane(makerState, onBack = { making = false }, onReact = { onMake(it); onDismiss() })
          } else {
            // リアクション対象ノート（アイコン＋本文2行）を先頭に表示して文脈を明示。
            if (targetNote != null) {
                TargetNoteHeader(targetNote)
                Spacer(Modifier.size(DeckSpace.Sm))
                HorizontalDivider(color = DeckColors.Border)
                Spacer(Modifier.size(DeckSpace.Sm))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeckTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = stringResource(Res.string.picker_search_placeholder),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
                // [#775] リアクションのときだけ（投稿の絵文字挿入・既定リアクションの設定では出さない）。
                if (onMake != null) {
                    Spacer(Modifier.width(DeckSpace.Sm))
                    DeckGhostButton(stringResource(Res.string.picker_make), onClick = { making = true })
                }
            }
            Spacer(Modifier.size(DeckSpace.Sm))

            // [#868] 標準の絵文字すべて（約 1,900 個）を並べるので、見えている行だけ組み立てる（LazyColumn）。
            // Unicode の絵文字は固定幅のボタンなので、幅から 1 行の個数を決めて行ごとに分ける。
            val cell = 40.dp.scaledByText()
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val perRow = ((maxWidth + EMOJI_GAP) / (cell + EMOJI_GAP)).toInt().coerceAtLeast(1)
                val q = query.trim()
                LazyColumn(Modifier.fillMaxSize()) {
                    if (q.isNotEmpty()) {
                        // 検索: カスタム（shortcode 部分一致）＋ Unicode（キーワード）
                        val matchedCustom = customs.filter { it.shortcode.contains(q, ignoreCase = true) }
                        val matchedUnicode = EmojiPickerCatalog.search(q)
                        // [#869] 打った文字が絵文字だけで、一覧に同じものが無ければ、そのまま選べるように先頭に出す
                        val typed = TypedEmoji.parse(query)?.takeIf { t -> matchedUnicode.none { it.char == t } }
                        if (typed == null && matchedCustom.isEmpty() && matchedUnicode.isEmpty()) {
                            item("empty") { EmptyHint(stringResource(Res.string.picker_no_match)) }
                        } else {
                            if (typed != null) {
                                item("typed_label") { SectionLabel(stringResource(Res.string.picker_typed_emoji)) }
                                item("typed") { EmojiFlow { UnicodeEmojiButton(typed) { onPick(typed, null); onDismiss() } } }
                            }
                            if (matchedCustom.isNotEmpty()) {
                                item("custom_label") { SectionLabel(stringResource(Res.string.picker_custom)) }
                                item("custom") {
                                    EmojiFlow {
                                        matchedCustom.forEach { CustomEmojiButton(it) { onPick(":${it.shortcode}:", it.url); onDismiss() } }
                                    }
                                }
                            }
                            if (matchedUnicode.isNotEmpty()) {
                                item("unicode_label") { SectionLabel(stringResource(Res.string.picker_emoji)) }
                                emojiRows("match", matchedUnicode, perRow) { e -> onPick(e.char, null); onDismiss() }
                            }
                        }
                    } else {
                        if (recents.isNotEmpty()) {
                            item("recent_label") { SectionLabel(stringResource(Res.string.picker_recent)) }
                            item("recent") {
                                EmojiFlow {
                                    recents.forEach { r -> RecentEmojiButton(r) { onPick(r.content, r.imageUrl); onDismiss() } }
                                }
                            }
                        }
                        if (customs.isNotEmpty()) {
                            item("customs_label") { SectionLabel(stringResource(Res.string.picker_custom_emoji)) }
                            item("customs") {
                                EmojiFlow {
                                    customs.forEach { c -> CustomEmojiButton(c) { onPick(":${c.shortcode}:", c.url); onDismiss() } }
                                }
                            }
                        }
                        EmojiPickerCatalog.categories.forEachIndexed { i, cat ->
                            item("cat_label_$i") { SectionLabel(cat.title?.let { stringResource(it) } ?: cat.fallbackTitle) }
                            emojiRows("cat_$i", cat.emojis, perRow) { e -> onPick(e.char, null); onDismiss() }
                        }
                    }
                    item("bottom") { Spacer(Modifier.size(DeckSpace.Lg)) }
                }
            }
          }
        }
    }
}

/**
 * [#775] ピッカーの「絵文字を作る」の画面（Web #768）。作成フォーム + ショートコード（任意）+「自分の絵文字リストにも保存」、
 * 下に「この絵文字でリアクション」を固定する。ショートコードが空なら自動の名前（nostrism_ + 画像 URL の SHA-256 先頭 8 桁）。
 * 押せないのは: テキストが空・プレビューがエラー・今の入力のプレビューがまだ届いていない・名前が不正の間。
 */
@Composable
private fun ColumnScope.MakeEmojiPane(state: EmojiMakerState, onBack: () -> Unit, onReact: (MadeEmoji) -> Unit) {
    var code by remember { mutableStateOf("") }
    var save by remember { mutableStateOf(false) }
    val url = state.url
    val autoName = remember(url) { url?.let { EmojiMaker.autoShortcode(it) } }
    val typed = code.isNotBlank()
    val shortcode = if (typed) EmojiMaker.parseShortcode(code) else autoName

    DeckTextButton("← " + stringResource(Res.string.common_back), onClick = onBack)
    Spacer(Modifier.size(DeckSpace.Xs))
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        EmojiMakerForm(state)
        Spacer(Modifier.size(DeckSpace.Md))
        Text(
            stringResource(Res.string.picker_make_shortcode_label), color = DeckColors.Text2,
            fontSize = DeckType.Caption, fontWeight = DeckWeight.Name, modifier = Modifier.padding(bottom = DeckSpace.Xs),
        )
        DeckTextField(
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = autoName ?: stringResource(Res.string.picker_make_shortcode_hint),
        )
        Text(
            if (typed && shortcode == null) stringResource(Res.string.emoji_shortcode_invalid)
            else stringResource(Res.string.picker_make_shortcode_hint),
            color = if (typed && shortcode == null) DeckColors.Warn else DeckColors.Text3,
            fontSize = DeckType.Label, modifier = Modifier.padding(top = DeckSpace.Xs),
        )
        Row(
            Modifier.fillMaxWidth().clickable { save = !save },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = save,
                onCheckedChange = { save = it },
                colors = CheckboxDefaults.colors(
                    checkedColor = DeckColors.Accent, uncheckedColor = DeckColors.Text3, checkmarkColor = DeckColors.Bg,
                ),
            )
            Text(stringResource(Res.string.picker_make_save), color = DeckColors.Text, fontSize = DeckType.Body)
        }
        Spacer(Modifier.size(DeckSpace.Md))
    }
    Spacer(Modifier.size(DeckSpace.Sm))
    DeckButton(
        stringResource(Res.string.picker_make_react),
        enabled = state.ready && url != null && shortcode != null,
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            if (url != null && shortcode != null) {
                state.saveLast()   // [#834] 使った（リアクション・リストへの保存）ので、文字色・縁取り・フォントを覚える
                onReact(MadeEmoji(shortcode, url, autoName = !typed, save = save))
            }
        },
    )
}

/** リアクション対象ノートの要約（アバター＋著者名＋本文2行）。 */
@Composable
private fun TargetNoteHeader(note: NoteUi) {
    val author = note.author
    val name = author.name.takeIf { it.isNotBlank() }
        ?: runCatching { Nip19.hexToNpub(note.event.pubkey).take(12) + "…" }.getOrDefault(note.event.pubkey.take(12))
    val body = note.text?.takeIf { it.isNotBlank() } ?: note.event.content   // [#326] メディアのみでも空にしない
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Avatar(seed = note.event.pubkey, pictureUrl = author.pictureUrl, size = 32.dp, pubkey = note.event.pubkey)
        Spacer(Modifier.width(DeckSpace.Sm))
        Column(Modifier.fillMaxWidth()) {
            Text(
                name, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Name,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (body.isNotBlank()) {
                Spacer(Modifier.size(DeckSpace.Xs))
                Text(
                    noteAnnotated(body), color = DeckColors.Text2, fontSize = DeckType.Sub,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Spacer(Modifier.size(DeckSpace.Sm))
    Text(text, color = DeckColors.Text3, fontSize = DeckType.Label, fontWeight = DeckWeight.Strong)
    Spacer(Modifier.size(DeckSpace.Xs))
}

@Composable
private fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(DeckSpace.Xl), contentAlignment = Alignment.Center) {
        Text(text, color = DeckColors.Text3, fontSize = DeckType.Caption)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiFlow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(EMOJI_GAP),
        verticalArrangement = Arrangement.spacedBy(EMOJI_GAP),
    ) { content() }
}

/** 絵文字のボタンの間隔（縦横）。 */
private val EMOJI_GAP = 4.dp

/** [#868] Unicode の絵文字を [perRow] 個ずつの行に分けて、LazyColumn の行として並べる（見えている行だけ組み立てる）。 */
private fun LazyListScope.emojiRows(keyPrefix: String, emojis: List<EmojiEntry>, perRow: Int, onClick: (EmojiEntry) -> Unit) {
    val rows = emojis.chunked(perRow)
    items(rows.size, key = { "$keyPrefix:$it" }) { i ->
        Row(Modifier.fillMaxWidth().padding(bottom = EMOJI_GAP), horizontalArrangement = Arrangement.spacedBy(EMOJI_GAP)) {
            rows[i].forEach { e -> UnicodeEmojiButton(e.char) { onClick(e) } }
        }
    }
}

@Composable
private fun EmojiCell(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        // [#339] 文字サイズ設定に追従（老眼モードでセルとグリフが一緒に大きくなる）。
        // [#861] 高さは固定、幅は最小で正方形。横長のカスタム絵文字はセルごと横に広がる。
        Modifier.height(40.dp.scaledByText()).widthIn(min = 40.dp.scaledByText()).clip(RoundedCornerShape(DeckRadius.Sm))
            .background(DeckColors.Surface2).clickable(onClick = onClick).padding(horizontal = DeckSpace.Xs),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun UnicodeEmojiButton(char: String, onClick: () -> Unit) {
    EmojiCell(onClick) { Text(char, fontSize = DeckType.Display) }
}

@Composable
private fun CustomEmojiButton(emoji: CustomEmoji, onClick: () -> Unit) {
    EmojiCell(onClick) { EmojiImage(emoji.url, emoji.shortcode) }
}

@Composable
private fun RecentEmojiButton(emoji: UsedEmoji, onClick: () -> Unit) {
    EmojiCell(onClick) {
        if (emoji.imageUrl != null) EmojiImage(emoji.imageUrl, emoji.content)
        else Text(emoji.content, fontSize = DeckType.Display)
    }
}

@Composable
private fun EmojiImage(url: String, desc: String) {
    // [#277] iOS/Desktop でも GIF/アニメ WebP を動かすため共通コンポーネントへ。
    // [#308] 隣に並ぶ Unicode 絵文字ボタン（Display）と同じ大きさに揃える。
    // [#861] 高さ固定・幅は縦横比どおり。
    AnimatedEmoji(url, contentDescription = desc, modifier = Modifier.emojiSize(url, DeckType.Display.asEmojiSize()))
}
