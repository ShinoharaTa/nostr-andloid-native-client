package app.nostrdeck.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.nostrdeck.data.EventRepository

/**
 * 実データの Repository を Compose ツリーに供給する。
 * null のときは SampleData（仮データ）にフォールバック（iOS 未配線・プレビュー等）。
 */
val LocalRepository = staticCompositionLocalOf<EventRepository?> { null }

/** [M10] 本文メンション(@npub…)を表示名に解決するための pubkey(hex)→name マップ。 */
val LocalProfileNames = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * 本文内リンクのタップ遷移ハンドラ。@メンション→プロフィール / #タグ→ハッシュタグカラム /
 * note・nevent→スレッド。null（既定）ならリンクは強調表示のみでタップ不可（プレビュー用途）。
 */
class NoteNav(
    val onMention: (pubkeyHex: String) -> Unit,
    val onHashtag: (tag: String) -> Unit,
    val onEvent: (eventIdHex: String) -> Unit,
)

val LocalNoteNav = staticCompositionLocalOf<NoteNav?> { null }
