package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import app.nostrdeck.model.NowPlaying
import app.nostrdeck.model.NowPlayingTrack
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSURL
import platform.MediaPlayer.MPMediaLibrary
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatus
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusAuthorized
import platform.MediaPlayer.MPMediaLibraryAuthorizationStatusNotDetermined
import platform.MediaPlayer.MPMusicPlayerController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

actual val nowPlayingSupported: Boolean = true

/**
 * [#1021] iOS: Apple の「ミュージック」アプリで再生中の曲を読む（[MPMusicPlayerController.systemMusicPlayer]）。
 * ほかのアプリ（Spotify など）の再生を読む公開の API は無い。
 * 読むにはメディアライブラリの許可が要る（Info.plist の NSAppleMusicUsageDescription）。まだ聞いていなければ、
 * [NowPlayingSource.read] を呼んだとき（＝利用者がボタンを押したとき）に OS の確認を出す。
 */
@Composable
actual fun rememberNowPlayingSource(): NowPlayingSource =
    remember { NowPlayingSource(read = { readNowPlaying() }, openPermissionSettings = { openAppSettings() }) }

private suspend fun readNowPlaying(): NowPlayingResult {
    val status = MPMediaLibrary.authorizationStatus().let { current ->
        if (current == MPMediaLibraryAuthorizationStatusNotDetermined) requestAuthorization() else current
    }
    if (status != MPMediaLibraryAuthorizationStatusAuthorized) return NowPlayingResult.NeedsPermission
    return withContext(Dispatchers.Main) {
        val player = MPMusicPlayerController.systemMusicPlayer
        val item = player.nowPlayingItem ?: return@withContext NowPlayingResult.Nothing
        val title = item.title?.takeIf { it.isNotBlank() } ?: return@withContext NowPlayingResult.Nothing
        val duration = item.playbackDuration.takeIf { it.isFinite() && it > 0 }?.toLong()
        val position = player.currentPlaybackTime.takeIf { it.isFinite() && it >= 0 }?.toLong()
        NowPlayingResult.Playing(
            NowPlayingTrack(
                title = title,
                artist = item.artist?.takeIf { it.isNotBlank() },
                durationSec = duration,
                positionSec = position,
                app = NowPlaying.App.APPLE_MUSIC,
            ),
        )
    }
}

/** メディアライブラリの許可を聞く（OS の確認が出る。答えは別のスレッドで返る）。 */
private suspend fun requestAuthorization(): MPMediaLibraryAuthorizationStatus =
    suspendCancellableCoroutine { continuation ->
        MPMediaLibrary.requestAuthorization { status -> if (continuation.isActive) continuation.resume(status) }
    }

/** 「設定」のこのアプリの画面を開く（許可を断ったあとに付け直してもらう）。 */
private fun openAppSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, options = emptyMap<Any?, Any>(), completionHandler = null)
}
