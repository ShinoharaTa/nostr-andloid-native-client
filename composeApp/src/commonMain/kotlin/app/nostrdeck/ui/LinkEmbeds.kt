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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nostrdeck.model.EmbedKind
import app.nostrdeck.model.EmbedPrefs
import app.nostrdeck.model.OgpData
import app.nostrdeck.model.detectEmbeds
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import app.nostrdeck.theme.DeckWeight
import coil3.compose.AsyncImage

/**
 * 本文中リンクの埋め込み表示（YouTube サムネ / Spotify・一般リンクの OGP カード）。
 * 表示可否と OGP 画像読み込みは設定([EmbedPrefs])に従う。取得中/失敗は何も描かない。
 * 画像 URL は [NoteImages] が別途表示するため [detectEmbeds] 側で除外済み。
 */
@Composable
fun LinkEmbeds(content: String, modifier: Modifier = Modifier) {
    val repo = LocalRepository.current
    val prefs by (repo?.embedPrefsFlow()?.collectAsState() ?: remember { mutableStateOf(EmbedPrefs()) })
    val embeds = remember(content) { detectEmbeds(content) }
    val visible = embeds.filter {
        when (it.kind) {
            EmbedKind.YOUTUBE -> prefs.youtube
            EmbedKind.SPOTIFY -> prefs.spotify
            EmbedKind.OGP -> prefs.ogp
            EmbedKind.VIDEO -> prefs.video
        }
    }
    if (visible.isEmpty() || repo == null) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DeckSpace.Sm)) {
        visible.forEach { e ->
            when (e.kind) {
                EmbedKind.YOUTUBE -> YouTubeEmbed(e.url, e.youtubeId!!)
                EmbedKind.SPOTIFY -> OgpEmbed(e.url, loadImage = true)   // Spotify も OGP カードで表現
                EmbedKind.OGP -> OgpEmbed(e.url, loadImage = prefs.ogpImages)
                EmbedKind.VIDEO -> VideoPlayer(e.url)                    // 動画直リンクはインライン再生
            }
        }
    }
}

/**
 * [#136] YouTube: 公式埋め込み（iframe）風のカード。
 *  - 上部にタイトル帯（oEmbed からタイトル/チャンネル名を取得・取得中は帯なし）
 *  - 中央に YouTube 標準の赤い角丸再生ボタン（ブランド要素としてモノクロ鉄則の例外）
 *  - 右下に YouTube ロゴタイプ
 * 通信はサムネ画像 + oEmbed(JSON) のみ。タップで外部アプリ/ブラウザへ。
 */
@Composable
private fun YouTubeEmbed(url: String, videoId: String) {
    val uri = LocalUriHandler.current
    val repo = LocalRepository.current
    val info by produceState<Pair<String, String>?>(null, videoId) { value = repo?.fetchYouTubeInfo(videoId) }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(DeckRadius.Md))
            .background(Color.Black).clickable { uri.openUri(url) },
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
        // 右下の YouTube ロゴタイプ（公式埋め込みの透かし相当）。
        Text(
            "YouTube",
            color = Color(0xCCFFFFFF), fontSize = DeckType.Label, fontWeight = DeckWeight.Strong,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(DeckSpace.Sm)
                .clip(RoundedCornerShape(DeckRadius.Sm))
                .background(Color(0x66000000))
                .padding(horizontal = DeckSpace.Xs, vertical = 1.dp),
        )
    }
}

/** 一般リンク/Spotify の OGP カード（画像 + タイトル + サイト名 + 説明）。 */
@Composable
private fun OgpEmbed(url: String, loadImage: Boolean) {
    val repo = LocalRepository.current ?: return
    val uri = LocalUriHandler.current
    val ogp: OgpData? by produceState<OgpData?>(null, url) { value = repo.fetchOgp(url) }
    val data = ogp ?: return   // 取得できるまで/失敗時は非表示
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(DeckRadius.Md))
            .border(1.dp, DeckColors.Border, RoundedCornerShape(DeckRadius.Md))
            .background(DeckColors.Surface2)
            .clickable { uri.openUri(url) },
    ) {
        if (loadImage && !data.image.isNullOrBlank()) {
            AsyncImage(
                model = data.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(88.dp),
            )
        }
        Column(Modifier.weight(1f).padding(DeckSpace.Sm)) {
            Text(
                data.siteName?.ifBlank { null } ?: hostOf(url),
                color = DeckColors.Text3, fontSize = DeckType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            data.title?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it, color = DeckColors.Text, fontSize = DeckType.Sub, fontWeight = DeckWeight.Strong,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            data.description?.ifBlank { null }?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    it, color = DeckColors.Text2, fontSize = DeckType.Label,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun hostOf(url: String): String =
    Regex("""^https?://([^/]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.removePrefix("www.") ?: url
