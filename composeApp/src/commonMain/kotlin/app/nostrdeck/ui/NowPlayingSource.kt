package app.nostrdeck.ui

import androidx.compose.runtime.Composable
import app.nostrdeck.model.NowPlayingTrack

/**
 * [#1021] 端末で再生中の曲を読めるか。Android（メディアセッション）と iOS（「ミュージック」アプリ）だけ。
 * false の端末には、ステータスの画面に Now Playing の欄を出さない。
 */
expect val nowPlayingSupported: Boolean

/** [#1021] 再生中の曲を読んだ結果。 */
sealed interface NowPlayingResult {
    data class Playing(val track: NowPlayingTrack) : NowPlayingResult

    /** 再生中（または一時停止中）の曲が無い。 */
    data object Nothing : NowPlayingResult

    /**
     * 読む許可がまだ無い。Android は OS の「通知へのアクセス」、iOS は「メディアと Apple Music」。
     * [NowPlayingSource.openPermissionSettings] で OS の設定を開ける。
     */
    data object NeedsPermission : NowPlayingResult
}

/**
 * [#1021] 再生中の曲の読み手。[read] は利用者がボタンを押したときに 1 回だけ呼ぶ（監視しない。裏では読まない）。
 */
class NowPlayingSource(
    val read: suspend () -> NowPlayingResult,
    val openPermissionSettings: () -> Unit,
)

@Composable
expect fun rememberNowPlayingSource(): NowPlayingSource
