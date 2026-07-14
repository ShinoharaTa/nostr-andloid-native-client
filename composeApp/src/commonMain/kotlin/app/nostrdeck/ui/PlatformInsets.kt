package app.nostrdeck.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable

/**
 * 下部ナビバー(BottomBar)が下端に敷くインセット。
 *  - Android: ジェスチャ/3ボタンのナビゲーション領域は OS が予約するセーフエリア。
 *    アプリが詰めると操作領域と重なるため **systemBars の実値をそのまま確保する**。
 *  - iOS: ホームインジケータのセーフエリアはフル 34dp と過大なので、
 *    高密度デッキ UI の方針で小さめの固定値(8dp)に詰める。
 */
@Composable
expect fun bottomBarInsets(): WindowInsets
