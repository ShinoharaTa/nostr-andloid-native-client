package app.nostrdeck.ui

import androidx.annotation.OptIn
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.nostrdeck.theme.DeckColors
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [#138] 同時再生を1本に抑える調停役。新しいプレイヤーが再生を始めたら
 * 直前に再生していたものを一時停止する（音の混在とデコーダ/帯域の浪費を防ぐ）。
 */
private object VideoPlaybackArbiter {
    private var current: ExoPlayer? = null
    fun onPlay(player: ExoPlayer) {
        if (current !== player) current?.pause()
        current = player
    }
    fun onRelease(player: ExoPlayer) {
        if (current === player) current = null
    }
}

/**
 * [#138] Android: Media3/ExoPlayer によるインライン動画プレイヤー。
 *  - 遅延ロード: 最初はポスター（1フレーム目）+▶のみ。
 *    タップして初めて ExoPlayer を生成・再生する（従来はフィードに現れた瞬間に全動画が prepare されていた）
 *  - カード右下にミュートトグル（既定ミュート・ワンタップで音出し）と全画面ボタンを常設
 *  - 同時再生は1本まで（新しく再生すると他は一時停止）
 * Composable 破棄時にプレイヤーを解放してリークを防ぐ。
 */
@Composable
actual fun VideoPlayer(url: String, modifier: Modifier) {
    var activated by remember(url) { mutableStateOf(false) }
    if (!activated) {
        VideoPoster(url, onPlay = { activated = true }, modifier = modifier)
    } else {
        ActiveVideoPlayer(url, modifier)
    }
}

/**
 * 再生前のポスター。1フレーム目は MediaMetadataRetriever で取得する
 * （HTTP range で必要部分のみ読む。Coil の VideoFrameDecoder は動画全体を
 * ダウンロードしてしまうため使わない）。取得失敗時は黒地 + ▶ のまま。
 */
@Composable
private fun VideoPoster(url: String, onPlay: () -> Unit, modifier: Modifier) {
    var poster by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(url) {
        withContext(Dispatchers.IO) {
            runCatching {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(url, emptyMap())
                val frame = mmr.getFrameAtTime(0)
                mmr.release()
                frame?.let { poster = it.asImageBitmap() }
            }
        }
    }
    Box(
        modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(DeckRadius.Md)).background(Color.Black)
            .clickable(onClick = onPlay),
    ) {
        poster?.let {
            Image(
                bitmap = it,
                contentDescription = "動画",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) { Text("▶", color = Color.White, fontSize = DeckType.Title) }
        Text(
            "動画",
            color = Color(0xCCFFFFFF), fontSize = DeckType.Label,
            modifier = Modifier.align(Alignment.BottomEnd).padding(DeckSpace.Sm)
                .clip(RoundedCornerShape(DeckRadius.Sm)).background(Color(0x66000000))
                .padding(horizontal = DeckSpace.Xs, vertical = 1.dp),
        )
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ActiveVideoPlayer(url: String, modifier: Modifier) {
    val context = LocalContext.current
    var muted by remember(url) { mutableStateOf(true) }
    var fullscreen by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            volume = 0f            // 既定ミュート（TL を流しても静か）
            playWhenReady = true   // ポスターのタップで起動された時点から再生
            prepare()
            VideoPlaybackArbiter.onPlay(this)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) VideoPlaybackArbiter.onPlay(this@apply)
                }
            })
        }
    }
    DisposableEffect(url) {
        onDispose {
            VideoPlaybackArbiter.onRelease(player)
            player.release()
        }
    }

    val toggleMute = {
        muted = !muted
        player.volume = if (muted) 0f else 1f
    }

    Box(modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(DeckRadius.Md))) {
        // 全画面中はインライン側から player を外す（1つの player を2つの View に繋がない）。
        if (!fullscreen) {
            PlayerSurface(player)
            // 右下の常設オーバーレイ: ミュートトグル + 全画面。
            Row(Modifier.align(Alignment.BottomEnd).padding(DeckSpace.Sm)) {
                MuteButton(muted, toggleMute)
                Spacer(Modifier.width(DeckSpace.Xs))
                OverlayButton(Icons.Outlined.Fullscreen, "全画面") { fullscreen = true }
            }
        }
    }

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PlayerSurface(player)
                // 閉じる（戻るキーでも閉じられる）。
                Box(
                    Modifier.align(Alignment.TopEnd).padding(DeckSpace.Md)
                        .size(40.dp).clip(CircleShape).background(Color(0x99000000))
                        .clickable { fullscreen = false },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.Close, "全画面を閉じる", tint = Color.White) }
                Row(Modifier.align(Alignment.BottomEnd).padding(DeckSpace.Md)) {
                    MuteButton(muted, toggleMute)
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(player: ExoPlayer) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun MuteButton(muted: Boolean, onToggle: () -> Unit) {
    OverlayButton(
        icon = if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
        label = if (muted) "ミュート解除" else "ミュート",
        onClick = onToggle,
    )
}

@Composable
private fun OverlayButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = DeckColors.Text, modifier = Modifier.size(18.dp)) }
}
