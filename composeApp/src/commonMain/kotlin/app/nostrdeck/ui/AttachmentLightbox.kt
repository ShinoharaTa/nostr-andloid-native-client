package app.nostrdeck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.nostrdeck.i18n.stringResource
import app.nostrdeck.model.ImageTransform
import app.nostrdeck.theme.DeckDimens
import app.nostrdeck.theme.DeckRadius
import app.nostrdeck.theme.DeckSpace
import app.nostrdeck.theme.DeckType
import kotlinx.coroutines.launch
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.common_close
import nostr_deck_client.composeapp.generated.resources.img_edit_reset
import nostr_deck_client.composeapp.generated.resources.img_flip_horizontal
import nostr_deck_client.composeapp.generated.resources.img_rotate_left
import nostr_deck_client.composeapp.generated.resources.img_rotate_right

/**
 * [#739] 投稿前の添付画像の全画面表示。投稿の画像の [Lightbox] と同じ操作（スワイプで前後・ピンチで拡大・
 * タップで閉じる）で、下部のツールバーで向きを直せる（左に回転・右に回転・左右反転・元に戻す）。
 *
 * 編集は表示にすぐ当て、送信用の画像は [onEdit] を受けた投稿画面が作り直す。編集した画像は端末に保存しない
 * （投稿の画像の Lightbox にある「画像を保存」も出さない）。向きを編集できない画像（GIF・Desktop）はツールバーを出さない。
 */
@Composable
internal fun AttachmentLightbox(
    images: List<ComposeAttachment>,
    startIndex: Int,
    onEdit: (ComposeAttachment, ImageTransform) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, images.size - 1)) { images.size }
        val scope = rememberCoroutineScope()
        // 現在ページが拡大中はページャのスワイプを無効化する（Lightbox と同じ）。
        var pagerScrollEnabled by remember { mutableStateOf(true) }

        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            HorizontalPager(
                state = pager,
                userScrollEnabled = pagerScrollEnabled,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val att = images[page]
                ZoomableImage(
                    model = att.src.bytes,
                    transform = att.edit,
                    onTap = onDismiss,
                    onLongPress = null,
                    onZoomChange = { zoomed -> if (page == pager.currentPage) pagerScrollEnabled = !zoomed },
                    onEdgeSwipe = { dir ->
                        val target = page + dir
                        if (target in images.indices) scope.launch { pager.animateScrollToPage(target) }
                    },
                )
            }

            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (images.size > 1) {
                    Text(
                        "${pager.currentPage + 1} / ${images.size}",
                        color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = DeckSpace.Lg),
                    )
                }
                Box(Modifier.align(Alignment.TopEnd).padding(DeckSpace.Md)) {
                    OverlayIconButton(Icons.Filled.Close, stringResource(Res.string.common_close), onClick = onDismiss)
                }
                val current = images.getOrNull(pager.currentPage)
                if (current != null && current.src.isOrientationEditable()) {
                    OrientationToolbar(
                        edit = current.edit,
                        onChange = { onEdit(current, it) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = DeckSpace.Lg),
                    )
                }
            }
        }
    }
}

/** [#739] 向きの編集ツールバー。どの操作も「今見えている向き」に対して効く。 */
@Composable
private fun OrientationToolbar(edit: ImageTransform, onChange: (ImageTransform) -> Unit, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(DeckRadius.Lg))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = DeckSpace.Xs, vertical = DeckSpace.Xs),
        horizontalArrangement = Arrangement.spacedBy(DeckSpace.Xs),
    ) {
        ToolbarButton(Icons.AutoMirrored.Outlined.RotateLeft, stringResource(Res.string.img_rotate_left)) { onChange(edit.rotatedLeft()) }
        ToolbarButton(Icons.AutoMirrored.Outlined.RotateRight, stringResource(Res.string.img_rotate_right)) { onChange(edit.rotatedRight()) }
        ToolbarButton(Icons.Outlined.Flip, stringResource(Res.string.img_flip_horizontal)) { onChange(edit.flippedHorizontally()) }
        ToolbarButton(Icons.Outlined.Restore, stringResource(Res.string.img_edit_reset), enabled = !edit.isIdentity) {
            onChange(ImageTransform.IDENTITY)
        }
    }
}

@Composable
private fun ToolbarButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f)
    Column(
        Modifier.widthIn(min = 64.dp)
            .clip(RoundedCornerShape(DeckRadius.Md))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = DeckSpace.Sm, vertical = DeckSpace.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(DeckDimens.IconLg))
        Spacer(Modifier.height(2.dp))
        Text(label, color = tint, fontSize = DeckType.Micro, maxLines = 1)
    }
}
