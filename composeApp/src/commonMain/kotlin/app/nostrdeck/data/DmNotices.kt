package app.nostrdeck.data

import app.nostrdeck.model.DmConversation
import app.nostrdeck.model.NotificationKind
import app.nostrdeck.model.NotificationUi
import app.nostrdeck.model.Profile

/**
 * [#419] DM 会話一覧から、通知欄・フォロー中TLに出す DM 通知を作る（純関数）。
 *
 * **未読のある会話を1件ずつ**出す。1通ごとに出すと、会話が弾んだだけで通知欄とタイムラインが
 * 同じ行で埋まる。未読に限るので、読めば消え（DM の未読バッジと同じ基準）、起動時に消されない
 * DM の履歴（kind:14 はタイムライン消去の対象外）が通知の枠を占めることも無い。
 *
 * 本文は載せない。通知欄とフォロー中TLは主画面で肩越しに見えやすいので、中身は DM 画面の中だけに
 * 留める。並び位置は相手の最新発言の時刻。
 */
fun dmNotices(conversations: List<DmConversation>): List<NotificationUi> =
    conversations.filter { it.unread > 0 }.map { c ->
        NotificationUi(
            id = "dm_${c.pubkey}",   // 会話ごとに固定（新着が来ても同じ行が更新される）
            kind = NotificationKind.DM,
            actor = Profile(c.pubkey, c.name, c.handle, c.pictureUrl),
            createdAt = c.lastIncomingAt,
            dmUnread = c.unread,
        )
    }
