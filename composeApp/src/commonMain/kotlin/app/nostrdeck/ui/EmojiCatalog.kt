package app.nostrdeck.ui

import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * リアクションピッカー用の軽量 Unicode 絵文字カタログ。
 * フルデータセット(数百KB)は同梱せず、よく使うものを厳選して日英キーワードで検索できるようにする。
 * 各エントリの [keywords] は小文字英語＋日本語の両方を持たせ、[EmojiCatalog.search] で前方/部分一致する。
 */
data class EmojiEntry(val char: String, val keywords: List<String>)
data class EmojiCategory(val title: StringResource, val emojis: List<EmojiEntry>)

object EmojiCatalog {

    val categories: List<EmojiCategory> = listOf(
        EmojiCategory(
            Res.string.emoji_cat_faces,
            listOf(
                EmojiEntry("😀", listOf("grin", "smile", "笑顔", "にこ")),
                EmojiEntry("😃", listOf("smile", "happy", "笑顔", "うれしい")),
                EmojiEntry("😄", listOf("smile", "happy", "笑", "わらい")),
                EmojiEntry("😁", listOf("grin", "beam", "にやり", "笑")),
                EmojiEntry("😆", listOf("laugh", "haha", "爆笑", "わらい")),
                EmojiEntry("😅", listOf("sweat", "苦笑", "あせ", "汗")),
                EmojiEntry("🤣", listOf("rofl", "lol", "爆笑", "わらい")),
                EmojiEntry("😂", listOf("joy", "tears", "笑い泣き", "わらい")),
                EmojiEntry("🙂", listOf("slight smile", "ほほえみ", "にこ")),
                EmojiEntry("🙃", listOf("upside down", "さかさ", "とぼけ")),
                EmojiEntry("😉", listOf("wink", "ウインク", "ちゃめ")),
                EmojiEntry("😊", listOf("blush", "smile", "にこ", "照れ")),
                EmojiEntry("😍", listOf("heart eyes", "love", "好き", "ラブ")),
                EmojiEntry("🥰", listOf("love", "smiling hearts", "好き", "ラブ")),
                EmojiEntry("😘", listOf("kiss", "キス", "ちゅ")),
                EmojiEntry("😎", listOf("cool", "sunglasses", "クール", "かっこいい")),
                EmojiEntry("🤔", listOf("thinking", "考え", "うーん", "なやみ")),
                EmojiEntry("🤐", listOf("zip", "だまる", "むぐ")),
                EmojiEntry("😴", listOf("sleep", "ねむい", "睡眠", "zzz")),
                EmojiEntry("😭", listOf("cry", "sob", "泣く", "なき")),
                EmojiEntry("😱", listOf("scream", "shock", "驚き", "びっくり")),
                EmojiEntry("😡", listOf("angry", "怒り", "おこ", "rage")),
                EmojiEntry("🥺", listOf("pleading", "うるうる", "おねがい")),
                EmojiEntry("😇", listOf("angel", "天使", "せいなる")),
                EmojiEntry("🤩", listOf("star eyes", "すごい", "キラキラ")),
                EmojiEntry("😏", listOf("smirk", "にやり", "どや")),
                EmojiEntry("😬", listOf("grimace", "うわ", "やばい")),
                EmojiEntry("🥳", listOf("party", "celebrate", "お祝い", "パーティ")),
                EmojiEntry("😮", listOf("wow", "おどろき", "ほー")),
                EmojiEntry("🤗", listOf("hug", "ハグ", "うれしい")),
            ),
        ),
        EmojiCategory(
            Res.string.emoji_cat_gestures,
            listOf(
                EmojiEntry("👍", listOf("thumbs up", "good", "いいね", "グッド", "了解")),
                EmojiEntry("👎", listOf("thumbs down", "bad", "だめ", "わるい")),
                EmojiEntry("👏", listOf("clap", "拍手", "ぱちぱち", "すごい")),
                EmojiEntry("🙏", listOf("pray", "thanks", "please", "おねがい", "感謝", "ありがとう")),
                EmojiEntry("🙌", listOf("raise", "万歳", "やった")),
                EmojiEntry("👌", listOf("ok", "オーケー", "了解")),
                EmojiEntry("✌️", listOf("victory", "peace", "ピース")),
                EmojiEntry("🤝", listOf("handshake", "握手", "よろしく")),
                EmojiEntry("💪", listOf("muscle", "strong", "がんばる", "筋肉")),
                EmojiEntry("👋", listOf("wave", "hello", "bye", "やあ", "ばいばい")),
                EmojiEntry("🤙", listOf("call me", "しゃか")),
                EmojiEntry("👀", listOf("eyes", "見てる", "目")),
                EmojiEntry("🫡", listOf("salute", "敬礼", "了解")),
                EmojiEntry("🤷", listOf("shrug", "さあ", "しらない")),
            ),
        ),
        EmojiCategory(
            Res.string.emoji_cat_hearts,
            listOf(
                EmojiEntry("❤️", listOf("heart", "love", "好き", "ハート", "ラブ")),
                EmojiEntry("🧡", listOf("orange heart", "オレンジ", "ハート")),
                EmojiEntry("💛", listOf("yellow heart", "黄色", "ハート")),
                EmojiEntry("💚", listOf("green heart", "緑", "ハート")),
                EmojiEntry("💙", listOf("blue heart", "青", "ハート")),
                EmojiEntry("💜", listOf("purple heart", "紫", "ハート")),
                EmojiEntry("🖤", listOf("black heart", "黒", "ハート")),
                EmojiEntry("🤍", listOf("white heart", "白", "ハート")),
                EmojiEntry("💗", listOf("growing heart", "ときめき", "ハート")),
                EmojiEntry("💕", listOf("two hearts", "ハート", "ラブ")),
                EmojiEntry("💔", listOf("broken heart", "失恋", "こわれ")),
                EmojiEntry("🔥", listOf("fire", "hot", "炎", "あつい", "やばい")),
                EmojiEntry("✨", listOf("sparkles", "キラキラ", "すごい")),
                EmojiEntry("⭐", listOf("star", "星", "すごい")),
                EmojiEntry("🎉", listOf("party", "tada", "おめでとう", "クラッカー")),
                EmojiEntry("🎊", listOf("confetti", "お祝い", "くす玉")),
                EmojiEntry("💯", listOf("100", "perfect", "満点", "完璧")),
                EmojiEntry("💢", listOf("anger", "怒り", "イライラ")),
                EmojiEntry("💦", listOf("sweat", "あせ", "汗")),
                EmojiEntry("💤", listOf("zzz", "ねむい", "睡眠")),
            ),
        ),
        EmojiCategory(
            Res.string.emoji_cat_nature,
            listOf(
                EmojiEntry("🐶", listOf("dog", "犬", "いぬ")),
                EmojiEntry("🐱", listOf("cat", "猫", "ねこ")),
                EmojiEntry("🐭", listOf("mouse", "ねずみ")),
                EmojiEntry("🐰", listOf("rabbit", "うさぎ")),
                EmojiEntry("🦊", listOf("fox", "きつね")),
                EmojiEntry("🐻", listOf("bear", "くま")),
                EmojiEntry("🐼", listOf("panda", "パンダ")),
                EmojiEntry("🐸", listOf("frog", "かえる")),
                EmojiEntry("🐧", listOf("penguin", "ペンギン")),
                EmojiEntry("🐤", listOf("chick", "ひよこ")),
                EmojiEntry("🦄", listOf("unicorn", "ユニコーン")),
                EmojiEntry("🐝", listOf("bee", "はち")),
                EmojiEntry("🌸", listOf("cherry blossom", "桜", "さくら")),
                EmojiEntry("🌺", listOf("flower", "花", "はな")),
                EmojiEntry("🌈", listOf("rainbow", "虹", "にじ")),
                EmojiEntry("☀️", listOf("sun", "晴れ", "たいよう")),
                EmojiEntry("🌙", listOf("moon", "月", "つき")),
                EmojiEntry("⚡", listOf("lightning", "雷", "かみなり")),
                EmojiEntry("❄️", listOf("snow", "雪", "ゆき")),
                EmojiEntry("🌊", listOf("wave", "波", "なみ")),
            ),
        ),
        EmojiCategory(
            Res.string.emoji_cat_food,
            listOf(
                EmojiEntry("🍎", listOf("apple", "りんご")),
                EmojiEntry("🍌", listOf("banana", "バナナ")),
                EmojiEntry("🍓", listOf("strawberry", "いちご")),
                EmojiEntry("🍅", listOf("tomato", "トマト")),
                EmojiEntry("🍙", listOf("rice ball", "おにぎり")),
                EmojiEntry("🍣", listOf("sushi", "寿司", "すし")),
                EmojiEntry("🍜", listOf("ramen", "ラーメン", "麺")),
                EmojiEntry("🍕", listOf("pizza", "ピザ")),
                EmojiEntry("🍔", listOf("burger", "ハンバーガー")),
                EmojiEntry("🍰", listOf("cake", "ケーキ")),
                EmojiEntry("🍩", listOf("donut", "ドーナツ")),
                EmojiEntry("🍺", listOf("beer", "ビール", "酒")),
                EmojiEntry("🍷", listOf("wine", "ワイン")),
                EmojiEntry("☕", listOf("coffee", "コーヒー", "お茶")),
                EmojiEntry("🍵", listOf("tea", "お茶", "緑茶")),
                EmojiEntry("🎂", listOf("birthday cake", "誕生日", "ケーキ")),
            ),
        ),
        EmojiCategory(
            Res.string.emoji_cat_activity,
            listOf(
                EmojiEntry("⚽", listOf("soccer", "サッカー")),
                EmojiEntry("⚾", listOf("baseball", "野球")),
                EmojiEntry("🏀", listOf("basketball", "バスケ")),
                EmojiEntry("🎮", listOf("game", "ゲーム")),
                EmojiEntry("🎵", listOf("music", "音楽", "おんがく")),
                EmojiEntry("🎸", listOf("guitar", "ギター")),
                EmojiEntry("📷", listOf("camera", "カメラ", "写真")),
                EmojiEntry("💻", listOf("laptop", "pc", "パソコン")),
                EmojiEntry("📱", listOf("phone", "スマホ", "携帯")),
                EmojiEntry("💰", listOf("money", "お金", "かね")),
                EmojiEntry("🎁", listOf("gift", "present", "プレゼント")),
                EmojiEntry("✅", listOf("check", "ok", "完了", "チェック")),
                EmojiEntry("❌", listOf("cross", "no", "ばつ", "だめ")),
                EmojiEntry("❓", listOf("question", "はてな", "疑問")),
                EmojiEntry("❗", listOf("exclamation", "びっくり", "注意")),
                EmojiEntry("🆗", listOf("ok", "オーケー")),
                EmojiEntry("🈵", listOf("full", "満")),
                EmojiEntry("🚀", listOf("rocket", "ロケット", "すごい")),
                EmojiEntry("👑", listOf("crown", "王冠", "おう")),
                EmojiEntry("💎", listOf("gem", "diamond", "ダイヤ", "宝石")),
            ),
        ),
    )

    /** 全エントリ（重複は char で除去）。「最近」が空のときのフォールバック等にも使う。 */
    val all: List<EmojiEntry> = categories.flatMap { it.emojis }.distinctBy { it.char }

    /** 日英キーワード/絵文字そのもの の部分一致で検索（小文字無視）。 */
    fun search(query: String): List<EmojiEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return all.filter { e ->
            e.char == query || e.keywords.any { it.contains(q) }
        }
    }
}
