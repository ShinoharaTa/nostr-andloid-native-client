package app.nostrdeck.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.data.EventRepository
import app.nostrdeck.state.EventLink

/**
 * 実データの Repository を Compose ツリーに供給する。
 * null のときは SampleData（仮データ）にフォールバック（iOS 未配線・プレビュー等）。
 */
val LocalRepository = staticCompositionLocalOf<EventRepository?> { null }

/** [M10] 本文メンション(@npub…)を表示名に解決するための pubkey(hex)→name マップ。 */
val LocalProfileNames = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * 本文内リンクのタップ遷移ハンドラ。@メンション→プロフィール / #タグ→ハッシュタグカラム /
 * note・nevent→スレッド（[#791] パブリックチャットの kind:40/42 はルーム）。
 * null（既定）ならリンクは強調表示のみでタップ不可（プレビュー用途）。
 */
class NoteNav(
    val onMention: (pubkeyHex: String) -> Unit,
    val onHashtag: (tag: String) -> Unit,
    // [#791] note・nevent。nevent の kind・リレーヒントも渡し、開き先（スレッド / ルーム）を kind で決めて開く。
    val onEventLink: (link: EventLink) -> Unit,
    // naddr（parameterized replaceable / 記事など）。kind+著者+dTag から実イベントを解決して開く。
    val onAddr: (addr: Nip19.AddrRef) -> Unit,
) {
    /** id だけ分かっているイベントを開く（引用カード・返信元など）。kind は手元のイベントか取得して決める。 */
    val onEvent: (eventIdHex: String) -> Unit = { id -> onEventLink(EventLink(id)) }
}

val LocalNoteNav = staticCompositionLocalOf<NoteNav?> { null }
