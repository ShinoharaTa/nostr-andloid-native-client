package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.model.XPost
import app.nostrdeck.model.XPosts
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.sp
import app.nostrdeck.i18n.stringResource
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.xpost_open
import app.nostrdeck.model.EmbedKind
import app.nostrdeck.model.EmbedPrefs
import app.nostrdeck.model.NetworkTier
import app.nostrdeck.model.OgpData
import app.nostrdeck.model.visibleEmbeds
import app.nostrdeck.model.imetaThumbs
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import coil3.compose.AsyncImage

/**
 * 本文中リンクの埋め込み表示（YouTube サムネ / Spotify・一般リンクの OGP カード）。
 * 表示可否と OGP 画像読み込みは設定([EmbedPrefs])に従う。OGP はカード枠と URL を先に出し、
 * 取得中はスピナーを添える（本文から URL を畳んでいるため、カードが出ないとリンクの存在自体が消える）。
 * 画像 URL は [NoteImages] が別途表示するため [detectEmbeds] 側で除外済み。
 * [tags] はイベントのタグ列。NIP-92 imeta のサムネイルを動画ポスターに使う。
 */
@Composable
fun LinkEmbeds(
    content: String,
    tags: List<List<String>> = emptyList(),
    // [#140] タイムライン経路は event.tags が空のため、NoteUi 側で抽出済みの imeta を渡せる。
    // null なら tags から抽出する（チャンネル等の従来経路）。
    imeta: Map<String, app.nostrdeck.model.ImetaInfo>? = null,
    modifier: Modifier = Modifier,
) {
    val repo = LocalRepository.current
    val prefs by (repo?.embedPrefsFlow()?.collectAsState() ?: remember { mutableStateOf(EmbedPrefs()) })
    val meta = imeta ?: remember(tags) { app.nostrdeck.model.imetaInfo(tags) }
    // [#326] 設定による絞り込みは nostr-core と共有する。本文からURLを畳む側が同じ判定を
    // 使うので、「カードは出ていないのに URL だけ消えた」が起きない。
    val visible = remember(content, prefs) { visibleEmbeds(content, prefs) }
    if (visible.isEmpty() || repo == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
        visible.forEach { e ->
            when (e.kind) {
                EmbedKind.YOUTUBE -> YouTubeEmbed(e.url, e.youtubeId!!)
                EmbedKind.SPOTIFY -> OgpEmbed(e.url, loadImage = true)   // Spotify も OGP カードで表現
                // [#733] X の投稿 URL は、OGP から組み立てた投稿カード（本文が取れなければ従来のリンクカード）。
                EmbedKind.OGP ->
                    if (XPosts.isPostUrl(e.url)) XPostEmbed(e.url, loadImage = prefs.ogpImages)
                    else OgpEmbed(e.url, loadImage = prefs.ogpImages)
                EmbedKind.VIDEO -> VideoPlayer(
                    e.url, posterUrl = meta[e.url]?.thumb,
                    blurhash = meta[e.url]?.blurhash,   // [#140] ポスター未取得の間のぼかし
                )
            }
        }
    }
}

/**
 * [#136] YouTube 埋め込み。
 *  - 対応プラットフォームでは公式 iframe プレイヤー（WebView）を置く。
 *    再生前のポスター・タイトル・再生ボタンも YouTube 標準 UI に任せる（サムネ再現はしない）
 *  - [#359] ただし従量制回線（モバイル/データセーバー）では iframe（初期ロード約1MB）を
 *    即置きせず、サムネカードを出してタップされたときだけプレイヤーをロードする
 *  - 未対応プラットフォーム（iOS）はサムネカード（タイトル帯=oEmbed + 赤ボタン + ロゴ）を表示し、
 *    タップで外部アプリ/ブラウザへ
 * 赤い再生ボタンはブランド要素としてモノクロ鉄則の例外。
 */
@Composable
private fun YouTubeEmbed(url: String, videoId: String) {
    val repo = LocalRepository.current
    val tier by (repo?.networkTierFlow()?.collectAsState()
        ?: remember { mutableStateOf(NetworkTier.UNMETERED) })
    val metered = tier == NetworkTier.METERED || tier == NetworkTier.CONSTRAINED
    var activated by remember(videoId) { mutableStateOf(false) }
    if (youTubeInlineSupported() && (!metered || activated)) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Md)).background(Color.Black)) {
            // カードのタップ起動([activated])で来たときは自動再生（再生ボタンを二度押させない）。
            YouTubeInlinePlayer(videoId, autoplay = activated, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        }
        return
    }
    val uri = LocalUriHandler.current
    val info by produceState<Pair<String, String>?>(null, videoId) { value = repo?.fetchYouTubeInfo(videoId) }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Md))
            .background(Color.Black)
            // インライン対応（Android の従量制カード）はタップでプレイヤー起動、非対応（iOS）は外部へ。
            .clickable { if (youTubeInlineSupported()) activated = true else uri.openUri(url) },
    ) {
        AsyncImage(
            model = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            contentDescription = "YouTube",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        // タイトル帯（公式埋め込みの上部バー相当。グラデ禁止のため半透明の単色帯）。
        info?.let { (title, author) ->
            Column(
                Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Color(0xB3000000))
                    .padding(horizontal = DeckSpace.Md, vertical = DeckSpace.Sm),
            ) {
                Text(
                    title, color = Color.White, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (author.isNotBlank()) {
                    Text(
                        author, color = Color(0xCCFFFFFF), fontSize = DeckType.Label,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // YouTube 標準の再生ボタン（赤い角丸長方形 + 白い三角）。
        Box(
            Modifier.align(Alignment.Center).width(58.dp).height(40.dp)
                .clip(RoundedCornerShape(10.dp)).background(Color(0xF2FF0000)),
            contentAlignment = Alignment.Center,
        ) { Text("▶", color = Color.White, fontSize = DeckType.Title) }
        // 右下の YouTube ロゴタイプ（公式埋め込みの透かし相当）。タップで外部アプリ/ブラウザへ。
        Text(
            "YouTube",
            color = Color(0xCCFFFFFF), fontSize = DeckType.Label, fontWeight = DeckWeight.Strong,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(DeckSpace.Sm)
                .clip(RoundedCornerShape(DeckRadius.Sm))
                .background(Color(0x66000000))
                .clickable { uri.openUri(url) }
                .padding(horizontal = DeckSpace.Xs, vertical = 1.dp),
        )
    }
}

/**
 * 一般リンク/Spotify の OGP カード（画像 + タイトル + サイト名 + 説明 + URL）。
 * カード枠と URL は取得を待たずに表示する。本文側で URL を畳んでいるため、OGP が出るまで
 * 何も描かないとリンクの存在も正当性も確認できない。取得中はスピナー、失敗時は URL のみ残す。
 */
@Composable
private fun OgpEmbed(url: String, loadImage: Boolean) {
    val repo = LocalRepository.current ?: return
    // 結果 null が「取得中」か「失敗」かを区別するため、完了フラグと対にして持つ。
    val fetched by produceState(false to null as OgpData?, url) { value = true to repo.fetchOgp(url) }
    val (done, data) = fetched
    OgpCard(url, data, done, loadImage)
}

/**
 * [#733] X（Twitter）の投稿リンク。取得は OGP のまま（通信先は増えない）。本文が取れていれば
 * 引用ノートに近い投稿カード（著者・@ハンドル・本文・写真）で出し、取れていなければ
 * （削除済み・非公開・取得失敗）従来のリンクカードに落とす。
 */
@Composable
private fun XPostEmbed(url: String, loadImage: Boolean) {
    val repo = LocalRepository.current ?: return
    val fetched by produceState(false to null as OgpData?, url) { value = true to repo.fetchOgp(url) }
    val (done, data) = fetched
    val post = remember(data) { XPosts.from(url, data) }
    if (post == null) OgpCard(url, data, done, loadImage) else XPostCard(post, loadImage)
}

/**
 * [#733] X の投稿カード。見た目は引用ノート（QuotedNoteCard）に揃える: 1 行目に（プロフィール画像）名前 @ハンドル、
 * 本文は 4 行で折りたたみ（カードのタップで全文⇔折りたたみ）、写真があれば下に 1 枚、末尾に「X で開く」。
 * 本文中の URL は引用カードと同じく短縮表示。画像は [loadImage]（設定「OGP カードの画像を読み込む」）に従う。
 */
@Composable
internal fun XPostCard(post: XPost, loadImage: Boolean) {
    val uri = LocalUriHandler.current
    var expanded by remember(post.url) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(DeckRadius.Md))
            .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Md))
            .background(DeckColors.Surface2)
            .clickable { expanded = !expanded }
            .padding(DeckSpace.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loadImage && post.avatar != null) {
                AsyncImage(
                    model = ImageProxy.proxied(post.avatar, width = 64),
                    contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(16.dp).clip(CircleShape),
                )
                Spacer(Modifier.width(DeckSpace.Xs))
            }
            Text(
                post.name ?: post.handle, color = DeckColors.Text2, fontSize = DeckType.Caption, fontWeight = DeckWeight.Name,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(DeckSpace.Xs))
            Text("@" + post.handle, color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(DeckSpace.Xs))
        val annotated = remember(post.text) { noteAnnotated(post.text, shortenUrls = true) }
        Text(
            annotated, color = DeckColors.Text2, fontSize = DeckType.Caption, fontWeight = DeckWeight.Body,
            lineHeight = 18.sp, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
        )
        if (loadImage && post.image != null) {
            Spacer(Modifier.height(DeckSpace.Xs))
            // [#360] OGP の画像は数 MB のことがあるため圧縮プロキシを通す。
            AsyncImage(
                model = ImageProxy.proxied(post.image, width = 800),
                contentDescription = null, contentScale = ContentScale.Crop,
                // 4:3 で中央を切り抜く。16:9 だと正方形に近い写真の上下（見出しや顔）が落ちやすい。
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(DeckRadius.Sm)),
            )
        }
        Spacer(Modifier.height(DeckSpace.Xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hostOf(post.url), color = DeckColors.Text3, fontSize = DeckType.Label,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(Res.string.xpost_open), color = DeckColors.Accent, fontSize = DeckType.Label, fontWeight = DeckWeight.Link,
                modifier = Modifier.clickable { uri.openUri(post.url) }.padding(DeckSpace.Xs),
            )
        }
    }
}

/** 汎用のリンクカード（OGP）。[done] が false の間は取得中のスピナー。 */
@Composable
internal fun OgpCard(url: String, data: OgpData?, done: Boolean, loadImage: Boolean) {
    val uri = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(DeckRadius.Md))
            .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Md))
            .background(DeckColors.Surface2)
            .clickable { uri.openUri(url) },
    ) {
        if (data != null && loadImage && !data.image.isNullOrBlank()) {
            // [#360] og:image は数MBのことがあるため圧縮プロキシを通す(表示は88dp、3.5x密度でも300pxで足りる)。
            AsyncImage(
                model = ImageProxy.proxied(data.image!!, width = 300),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp),
            )
        }
        Column(Modifier.weight(1f).padding(DeckSpace.Sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    data?.siteName?.ifBlank { null } ?: hostOf(url),
                    color = DeckColors.Text3, fontSize = DeckType.Label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!done) {
                    Spacer(Modifier.width(DeckSpace.Xs))
                    CircularProgressIndicator(
                        color = DeckColors.Text3, strokeWidth = 1.5.dp, modifier = Modifier.size(12.dp),
                    )
                }
            }
            data?.title?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            data?.description?.ifBlank { null }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it, color = DeckColors.Text2, fontSize = DeckType.Label,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            // リンク先の確認用に URL は常に表示する（OGP の有無に依らず）。
            Spacer(Modifier.height(2.dp))
            Text(
                url, color = DeckColors.Text3, fontSize = DeckType.Label,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun hostOf(url: String): String =
    Regex("""^https?://([^/]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.removePrefix("www.") ?: url
