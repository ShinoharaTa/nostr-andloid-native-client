package app.nostrdeck.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import app.nostrdeck.NowPlayingListenerService
import app.nostrdeck.model.NowPlaying
import app.nostrdeck.model.NowPlayingTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual val nowPlayingSupported: Boolean = true

/**
 * [#1021] Android: ほかのアプリのメディアセッションから、再生中の曲を読む。
 * OS の「通知へのアクセス」でこのアプリが許可されているときだけ読める（[NowPlayingListenerService]）。
 * 読むのは [NowPlayingSource.read] を呼んだときの 1 回だけで、通知の中身は読まない。
 */
@Composable
actual fun rememberNowPlayingSource(): NowPlayingSource {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        NowPlayingSource(
            read = { withContext(Dispatchers.Default) { readNowPlaying(context) } },
            openPermissionSettings = { openListenerSettings(context) },
        )
    }
}

private fun listenerComponent(context: Context) = ComponentName(context, NowPlayingListenerService::class.java)

private fun readNowPlaying(context: Context): NowPlayingResult {
    if (context.packageName !in NotificationManagerCompat.getEnabledListenerPackages(context)) {
        return NowPlayingResult.NeedsPermission
    }
    val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        ?: return NowPlayingResult.Nothing
    val sessions = try {
        manager.getActiveSessions(listenerComponent(context))
    } catch (_: SecurityException) {
        // 許可の一覧には居るのに読めない（許可を外した直後など）
        return NowPlayingResult.NeedsPermission
    }
    // Nostrism 自身の再生（動画・読み上げ）は読まない。OS は手前のセッションから順に返す
    val tracks = sessions.filter { it.packageName != context.packageName }
        .mapNotNull { controller -> trackOf(controller)?.let { controller to it } }
    // 再生中のものを優先し、無ければ一時停止中のいちばん手前のもの
    val picked = tracks.firstOrNull { (controller, _) -> controller.playbackState?.state == PlaybackState.STATE_PLAYING }
        ?: tracks.firstOrNull()
    return picked?.let { NowPlayingResult.Playing(it.second) } ?: NowPlayingResult.Nothing
}

/** 広告の印（MediaMetadataCompat.METADATA_KEY_ADVERTISEMENT と同じキー。プラットフォームの MediaMetadata には定数が無い）。 */
private const val METADATA_KEY_ADVERTISEMENT = "android.media.metadata.ADVERTISEMENT"

private fun trackOf(controller: MediaController): NowPlayingTrack? {
    val metadata = controller.metadata ?: return null
    if (metadata.getLong(METADATA_KEY_ADVERTISEMENT) != 0L) return null
    val title = sequenceOf(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        .firstNotNullOfOrNull { metadata.getString(it)?.takeIf { v -> v.isNotBlank() } } ?: return null
    val artist = sequenceOf(
        MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
    ).firstNotNullOfOrNull { metadata.getString(it)?.takeIf { v -> v.isNotBlank() } }
    val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }
    return NowPlayingTrack(
        title = title,
        artist = artist,
        durationSec = durationMs?.div(1000),
        positionSec = controller.playbackState?.let { positionMs(it) }?.div(1000),
        app = NowPlaying.appOfPackage(controller.packageName),
    )
}

/** いまの再生位置（ミリ秒）。再生中なら、最後に報告された位置から経過した分を足す。分からなければ null。 */
private fun positionMs(state: PlaybackState): Long? {
    if (state.position < 0) return null   // PLAYBACK_POSITION_UNKNOWN
    if (state.state != PlaybackState.STATE_PLAYING) return state.position
    val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
    return state.position + (elapsed * state.playbackSpeed).toLong().coerceAtLeast(0)
}

/**
 * OS の「通知へのアクセス」を開く。Android 11 以降はこのアプリのスイッチの画面を直接開き、開けなければ一覧を開く。
 */
private fun openListenerSettings(context: Context) {
    val detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listenerComponent(context).flattenToString())
    } else null
    val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    for (intent in listOfNotNull(detail, list)) {
        val opened = runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (opened) return
    }
}
