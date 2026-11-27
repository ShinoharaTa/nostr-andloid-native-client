package app.nostrdeck.data

/**
 * [#150] 初回起動時にシードする既定リレーを端末の言語で切り替える。
 * リレー表が空のとき（初回）のみ使われ、既存ユーザーのリレー表は上書きしない
 * （seed 条件は [EventRepository.start] 側）。
 *
 * 日本語話者は日本の投稿が多いリレーを含めないとタイムラインがほぼ英語圏になり、
 * 初見の体験が悪い。逆に非日本語話者に日本リレーを既定で繋ぐ意味は薄い。
 */
fun defaultRelaysFor(language: String): List<String> = when (language.lowercase().take(2)) {
    "ja" -> listOf(
        "wss://relay-jp.shino3.net",   // 日本向け（本アプリ運営）
        "wss://yabu.me",               // 日本の大手フリーリレー
        "wss://relay.damus.io",        // グローバル
        "wss://nos.lol",               // グローバル
    )
    else -> listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
    )
}
