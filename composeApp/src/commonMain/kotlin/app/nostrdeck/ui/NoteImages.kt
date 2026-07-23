package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nostrdeck.theme.DeckColors
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.getString
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckRadius
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import kotlinx.coroutines.launch

/**
 * 本文中の画像を表示する。
 *  - 1枚         : 横幅いっぱいの単一表示
 *  - 2〜9枚       : グリッド（2/4枚は2列、それ以外は3列）
 *  - 10枚以上     : 横スクロールのカルーセル
 * いずれもタップで元画像（プロキシ非経由のフル解像度）を Lightbox 全画面表示する。
 * Lightbox は複数画像のスワイプ移動・ピンチズーム・パンに対応する。
 */
@Composable
fun NoteImages(urls: List<String>, modifier: Modifier = Modifier) {
    // タップした画像の index（null=閉）。Lightbox は urls 全体を受け取り前後にスワイプできる。
    var lightboxIndex by remember { mutableStateOf<Int?>(null) }
    val open: (Int) -> Unit = { lightboxIndex = it }

    when {
        urls.isEmpty() -> Unit
        urls.size == 1 -> Thumb(
            urls[0], proxyWidth = 800,
            modifier = modifier.fillMaxWidth().height(200.dp), onClick = { open(0) },
        )
        urls.size >= 10 -> ImageCarousel(urls, modifier, open)
        else -> ImageGrid(urls, modifier, open)
    }

    lightboxIndex?.let { idx -> Lightbox(urls, idx) { lightboxIndex = null } }
}

@Composable
private fun ImageGrid(urls: List<String>, modifier: Modifier, onClick: (Int) -> Unit) {
    val cols = when (urls.size) { 2, 4 -> 2; else -> 3 }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        urls.chunked(cols).forEachIndexed { rowIdx, rowUrls ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowUrls.forEachIndexed { colIdx, url ->
                    val index = rowIdx * cols + colIdx
                    Thumb(
                        url, proxyWidth = 400,
                        modifier = Modifier.weight(1f).aspectRatio(1f), onClick = { onClick(index) },
                    )
                }
                // 端数行は空セルで列幅をそろえる。
                repeat(cols - rowUrls.size) { Box(Modifier.weight(1f).aspectRatio(1f)) }
            }
        }
    }
}

@Composable
private fun ImageCarousel(urls: List<String>, modifier: Modifier, onClick: (Int) -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        urls.forEachIndexed { index, url ->
            Thumb(url, proxyWidth = 280, modifier = Modifier.size(140.dp), onClick = { onClick(index) })
        }
    }
}

@Composable
private fun Thumb(url: String, proxyWidth: Int, modifier: Modifier, onClick: () -> Unit) {
    AsyncImage(
        model = ImageRequest.Builder(LocalPlatformContext.current)
            .data(ImageProxy.proxied(url, width = proxyWidth, quality = 75, animated = true))
            .crossfade(true).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(RoundedCornerShape(DeckRadius.Md))
            .background(DeckColors.Surface2)
            .clickable(onClick = onClick),
    )
}

/**
 * 元画像（プロキシ非経由）の全画面表示。複数画像はスワイプで前後に移動。
 *  - 1倍       : 横スワイプで前/次の画像（HorizontalPager）。
 *  - 拡大中     : 1本指ドラッグでパン（スワイプ量に追従）。端まで来てさらにドラッグすると前/次へ。
 *  - ダブルタップでズームのトグル、シングルタップ/×で閉じる。
 */
@Composable
private fun Lightbox(urls: List<String>, startIndex: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, urls.size - 1)) { urls.size }
        val scope = rememberCoroutineScope()
        // 現在ページが拡大中はページャのスワイプを無効化し、パン/端ハンドオフを自前で処理する。
        var pagerScrollEnabled by remember { mutableStateOf(true) }

        // 画像保存（端末ギャラリーへ）とトースト通知。
        val saveImage = rememberImageSaver()
        val toast = rememberToaster()
        val clipboard = rememberClipboardCopy()
    val urlCopiedMsg = stringResource(Res.string.img_url_copied)
        // 二重タップ連打での多重保存を防ぐ。
        var saving by remember { mutableStateOf(false) }
        // 長押しメニューの開閉。
        var menuOpen by remember { mutableStateOf(false) }

        val doSave: () -> Unit = {
            if (!saving) {
                saving = true
                val url = urls[pager.currentPage]
                scope.launch {
                    val ok = saveImage(url)
                    toast(if (ok) getString(Res.string.img_saved) else getString(Res.string.img_save_failed))
                    saving = false
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = pagerScrollEnabled,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ZoomableImage(
                    url = urls[page],
                    onTap = onDismiss,
                    onLongPress = { menuOpen = true },
                    onZoomChange = { zoomed -> if (page == pager.currentPage) pagerScrollEnabled = !zoomed },
                    onEdgeSwipe = { dir ->
                        val target = page + dir
                        if (target in urls.indices) scope.launch { pager.animateScrollToPage(target) }
                    },
                )
            }

            if (urls.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${urls.size}",
                    color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = DeckSpace.Lg),
                )
            }
            // 右上のオーバーレイ操作（ダウンロード＋閉じる）。40dp 実タップ領域・半透明の丸背景。
            Row(
                Modifier.align(Alignment.TopEnd).padding(DeckSpace.Md),
                horizontalArrangement = Arrangement.spacedBy(DeckSpace.Sm),
            ) {
                OverlayIconButton(Icons.Outlined.Download, stringResource(Res.string.img_save), onClick = doSave)
                OverlayIconButton(Icons.Filled.Close, stringResource(Res.string.common_close), onClick = onDismiss)
            }

            // 長押しメニュー（画像を保存 / URLをコピー）。中央付近にアンカーする。
            Box(Modifier.align(Alignment.Center)) {
                DeckDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.img_save)) },
                        leadingIcon = { Icon(Icons.Outlined.Download, null, modifier = Modifier.size(DeckDimens.IconMd)) },
                        onClick = { menuOpen = false; doSave() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.img_copy_url)) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, modifier = Modifier.size(DeckDimens.IconMd)) },
                        onClick = {
                            menuOpen = false
                            clipboard(urls[pager.currentPage])
                            toast(urlCopiedMsg)
                        },
                    )
                }
            }
        }
    }
}

/** Lightbox 右上のオーバーレイ操作ボタン（半透明の丸背景・白アイコン）。 */
@Composable
private fun OverlayIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(DeckDimens.TouchTargetSm).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription, tint = Color.White, modifier = Modifier.size(DeckDimens.IconLg)) }
}

/** 端ハンドオフ（拡大中に画像の端からさらにドラッグ）を発火する閾値(px)。 */
private const val EDGE_HANDOFF_THRESHOLD = 140f

/**
 * ピンチズーム + 1本指パンに対応した1枚画像。
 * `detectTransformGestures` を使うことで（multi-touch 専用の transformable と違い）
 * 1本指ドラッグのパンがスワイプ量に追従する。パンは画像境界でクランプし、端を越えて
 * さらにドラッグすると [onEdgeSwipe] で前後の画像へ移る。
 */
@Composable
private fun ZoomableImage(
    url: String,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
    onEdgeSwipe: (Int) -> Unit,
) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var edgeAccum by remember { mutableStateOf(0f) }

    LaunchedEffect(scale) { onZoomChange(scale > 1.01f) }

    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() },
                    onDoubleTap = {
                        if (scale > 1.01f) {
                            scale = 1f; offset = Offset.Zero; edgeAccum = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // 1倍かつ1本指のドラッグはページャに委ねる（横スワイプで画像送り）。
                // 拡大中、またはピンチ(2本指)のときだけ自前で処理する。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        val pinching = pressed >= 2
                        if (scale > 1.01f || pinching) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 5f)
                            val maxX = ((newScale - 1f) * boxSize.width / 2f).coerceAtLeast(0f)
                            val maxY = ((newScale - 1f) * boxSize.height / 2f).coerceAtLeast(0f)
                            var nx = offset.x + pan.x
                            var ny = offset.y + pan.y
                            // 横方向: 境界を越えた分を edgeAccum に貯め、閾値超過で画像送り。
                            if (newScale > 1.01f) {
                                when {
                                    nx > maxX -> { edgeAccum += nx - maxX; nx = maxX }
                                    nx < -maxX -> { edgeAccum += nx + maxX; nx = -maxX }
                                    else -> edgeAccum = 0f
                                }
                                if (edgeAccum > EDGE_HANDOFF_THRESHOLD) {
                                    edgeAccum = 0f; onEdgeSwipe(-1)  // 左端を越えて右ドラッグ → 前へ
                                } else if (edgeAccum < -EDGE_HANDOFF_THRESHOLD) {
                                    edgeAccum = 0f; onEdgeSwipe(1)   // 右端を越えて左ドラッグ → 次へ
                                }
                            }
                            scale = newScale
                            offset = if (newScale > 1.01f) {
                                Offset(nx.coerceIn(-maxX, maxX), ny.coerceIn(-maxY, maxY))
                            } else {
                                edgeAccum = 0f; Offset.Zero
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalPlatformContext.current)
                .data(url).crossfade(true).build(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
        )
    }
}
