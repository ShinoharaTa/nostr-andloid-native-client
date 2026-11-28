package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** [#1021] Desktop では再生中の曲を読まない（Mac の AppleScript は別の Issue）。 */
actual val nowPlayingSupported: Boolean = false

@Composable
actual fun rememberNowPlayingSource(): NowPlayingSource =
    remember { NowPlayingSource(read = { NowPlayingResult.Nothing }, openPermissionSettings = {}) }
