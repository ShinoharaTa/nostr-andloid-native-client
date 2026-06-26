package app.nostrdeck.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.nostrdeck.data.EventRepository

/**
 * 実データの Repository を Compose ツリーに供給する。
 * null のときは SampleData（仮データ）にフォールバック（iOS 未配線・プレビュー等）。
 */
val LocalRepository = staticCompositionLocalOf<EventRepository?> { null }
