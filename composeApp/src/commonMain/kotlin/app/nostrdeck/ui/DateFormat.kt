package app.nostrdeck.ui

/**
 * [#312] 絶対日時（端末のタイムゾーン/ロケール）。相対表示の `16h` `3d` だけだと
 * 「いつの投稿か」が潰れてしまうため、投稿詳細ではこちらを出す。
 *
 * 形式は `2026/08/02 15:41` 相当。秒は既定で落とす（投稿の同定には要らず、幅だけ食うため）。
 * [#930] [withSeconds] なら `2026/08/02 15:41:07`（時刻の表示を「日時（秒まで）」にしたとき）。
 */
expect fun formatAbsoluteTime(unixSeconds: Long, withSeconds: Boolean = false): String
