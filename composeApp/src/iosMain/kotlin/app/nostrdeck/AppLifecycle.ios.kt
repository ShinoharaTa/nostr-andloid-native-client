package app.nostrdeck

import app.nostrdeck.data.EventRepository
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.NSObjectProtocol

/**
 * [#848] iOS のアプリの前面・背面の切り替えをリレー接続につなぐ受け口。
 * Android の MainActivity.onStart / onStop（[EventRepository.onForeground] / [EventRepository.onBackground]）と対。
 *
 * - 前面に戻った（didBecomeActive）: [EventRepository.onForeground] で後退待ちのリレーを即再接続し、
 *   未送信を送り直す。起動直後の 1 回目も届くが、Android の初回 onStart と同じで無害
 *   （接続中のリレーの start() は何もせず、wake() は次の後退待ちを飛ばすだけ、retryUnsent() は間隔の制限つき）。
 * - 背面に移った（didEnterBackground）: OS に短い猶予（バックグラウンドタスク、約 30 秒）をもらい、
 *   その間は接続を保って未送信を送り切る。あわせて [EventRepository.onBackground] で 5 分の一時停止タイマーを
 *   始める（Android とそろえる。実際は OS が先にアプリを止めるので、効き目は OS 次第）。
 *   5 分を超えて止められていた場合は、再開の瞬間にこのタイマーが先に切れて一時停止し、
 *   直後の didBecomeActive（onForeground）でつなぎ直す（シミュレーターで確認）。
 *
 * 通知はメインキューで受け、期限のハンドラもメインスレッドで呼ばれるので、[bgTask] の読み書きは
 * すべてメインスレッドで起きる（排他は要らない）。
 * ネットワーク復帰（Android の netCallback。iOS では NWPathMonitor）はまだつないでいない。
 */
internal class AppLifecycle(private val repository: EventRepository) {
    // 取得中のバックグラウンドタスク。無ければ UIBackgroundTaskInvalid。
    private var bgTask: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid

    // 通知の購読の印。アプリ生存中ずっと購読するので外さない（2 回目の register() を弾くのにも使う）。
    private val observers = mutableListOf<NSObjectProtocol>()

    /** 通知の購読を始める。2 回呼んでも二重には購読しない。 */
    fun register() {
        if (observers.isNotEmpty()) return
        val center = NSNotificationCenter.defaultCenter
        val main = NSOperationQueue.mainQueue
        observers += center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, main) { _ ->
            onDidBecomeActive()
        }
        observers += center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, main) { _ ->
            onDidEnterBackground()
        }
    }

    private fun onDidBecomeActive() {
        // 猶予の途中で戻ってきたら、もう要らないので OS に返す。
        endBackgroundTask()
        repository.onForeground()
    }

    private fun onDidEnterBackground() {
        beginBackgroundTask()
        // 猶予の間に送り切る（つながっているリレーへ今すぐ送る。間隔の制限は retryUnsent 側）。
        repository.retryUnsent()
        repository.onBackground()
    }

    private fun beginBackgroundTask() {
        // 前の猶予が残っていれば（前面に戻りきらずにまた背面へ、など）返してから取り直す。
        endBackgroundTask()
        bgTask = UIApplication.sharedApplication.beginBackgroundTaskWithName("relays") {
            // 期限。ここで返さないと OS にアプリを終了させられる。
            endBackgroundTask()
        }
    }

    /** 取得中のバックグラウンドタスクを返す。期限のハンドラと復帰の両方から呼ばれるので、二重には返さない。 */
    private fun endBackgroundTask() {
        val id = bgTask
        if (id == UIBackgroundTaskInvalid) return
        bgTask = UIBackgroundTaskInvalid
        UIApplication.sharedApplication.endBackgroundTask(id)
    }
}
