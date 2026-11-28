package app.nostrdeck

import android.service.notification.NotificationListenerService

/**
 * [#1021] Now Playing の許可の受け皿。
 *
 * ほかのアプリの再生中の曲（メディアセッション）を読むには、OS の「通知へのアクセス」で、このアプリの
 * NotificationListenerService が許可されている必要がある（[android.media.session.MediaSessionManager.getActiveSessions]。
 * ほかに一般的な方法は無い）。このサービスはその許可を受けるためだけに置く。
 *
 * **通知は読まない**（通知が来ても何もしない）。曲を読むのは、利用者がステータスの画面のボタンを押したときの 1 回だけ
 * （[app.nostrdeck.ui.rememberNowPlayingSource]）。
 *
 * OS は許可された読み手をつなぎっぱなしにする（アプリのプロセスが起きたままになる）ので、つながれたらすぐ切り離して
 * もらう。切り離しても許可は残り、メディアセッションは読める。
 */
class NowPlayingListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        runCatching { requestUnbind() }
    }
}
