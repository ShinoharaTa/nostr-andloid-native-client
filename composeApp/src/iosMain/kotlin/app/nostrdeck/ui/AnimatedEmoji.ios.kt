package app.nostrdeck.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

// iOS: Coil にアニメデコーダが無いため Skia Codec で自前再生（実装は EmojiFrames.ios.kt）。
@Composable
actual fun AnimatedEmoji(url: String, contentDescription: String?, modifier: Modifier) {
    val frames by produceState<EmojiFrames?>(initialValue = null, url) {
        value = EmojiFrameCache.load(url)
    }
    val f = frames
    if (f == null || f.frames.isEmpty()) {
        Box(modifier)
        return
    }
    // [#861] 縦横比を記録する（表示の幅を決めるため）。
    LaunchedEffect(f) { EmojiAspect.record(url, f.frames[0].width, f.frames[0].height) }
    var idx by remember(f) { mutableStateOf(0) }
    if (f.frames.size > 1) {
        LaunchedEffect(f) {
            while (true) {
                kotlinx.coroutines.delay(f.durationsMs[idx].coerceAtLeast(20).toLong())
                idx = (idx + 1) % f.frames.size
            }
        }
    }
    Image(
        bitmap = f.frames[idx],
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
