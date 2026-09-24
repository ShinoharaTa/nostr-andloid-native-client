package app.nostrdeck.data

/**
 * [#423] リレーの受理確認（NIP-01 `["OK", <id>, <true|false>, <message>]`）と再送の規則。純関数だけを置く。
 *
 * 成功の判定は一般的な実装（nostr-tools の `pool.publish()`、NDK の `event.publish()`）に合わせ、
 * **どれか1つのリレーが受理したら成功**とする。
 */
object PublishAck {

    /** 受理を待つ時間。これを過ぎても1つも受理が無ければ未送信として残す。 */
    const val TIMEOUT_MS = 10_000L

    /** 自動再送の上限（試行回数）。超えたものは手動の「再送」でだけ送り直す。 */
    const val MAX_AUTO_RETRY = 5

    /** 自動再送を続けて走らせない間隔（再接続が立て続けに起きたときの暴発防止）。 */
    const val RETRY_MIN_INTERVAL_SEC = 30L

    /**
     * その OK を「受理」とみなすか。
     * `OK false` でも理由が `duplicate:` のものはリレーが既に持っている＝届いているので受理扱い。
     * 再送すると返ってくることが多い（NIP-01 は true を推奨しているが false で返すリレーもある）。
     */
    fun isAccepted(accepted: Boolean, message: String): Boolean =
        accepted || message.trimStart().startsWith("duplicate:")

    /**
     * 自動再送の対象か。attempts = 0 は初回の確認待ち（まだ結果が出ていない）なので対象外。
     * 上限を超えたものも対象外（手動の再送でだけ送る）。
     */
    fun shouldAutoRetry(attempts: Long): Boolean = attempts in 1 until MAX_AUTO_RETRY
}
