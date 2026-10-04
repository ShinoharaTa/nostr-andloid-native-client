package app.nostrdeck.data

import app.nostrdeck.model.ReactionUi

/**
 * [#732] 「自分が付けたリアクション」のうち、投稿の行に出す 1 つを選ぶ規則。
 *
 * ♡ボタンの押下状態は既定のリアクション（♡/☆など設定した 1 種）で決まる（myReactedFlow）。
 * ここで選ぶのは「＋絵文字」ボタンに出す方で、既定と別の絵文字を付けていればそれを優先する。
 * 既定しか付けていなければ既定を返す（呼び出し側は既定と同じなら「＋絵文字」のままにする）。
 */
object MyReaction {
    /** 設定の既定リアクション content を、DB 照合・表示判定用のキーに揃える（"+"/空 → ❤️）。 */
    fun defaultKey(content: String): String = if (content == "+" || content.isEmpty()) "❤️" else content

    /** [mine] は同じノートに自分が付けたリアクション（到着順）。既定以外を優先し、無ければ先頭。 */
    fun pick(mine: List<ReactionUi>, defaultKey: String): ReactionUi? =
        mine.firstOrNull { it.key != defaultKey } ?: mine.firstOrNull()

    /** 「＋絵文字」ボタンに出す分（既定と同じなら null = ♡側で表現済み）。 */
    fun forPickerButton(mine: ReactionUi?, defaultKey: String): ReactionUi? =
        mine?.takeIf { it.key != defaultKey }
}
