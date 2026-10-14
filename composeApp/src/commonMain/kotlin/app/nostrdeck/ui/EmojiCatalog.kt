package app.nostrdeck.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nostrdeck.model.nfkc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import org.jetbrains.compose.resources.StringResource

/**
 * リアクションピッカー用の Unicode 絵文字カタログ。
 *
 * [#868] 標準の絵文字すべて（emojibase-data。Web #684 と同じ版・同じ規則）を `files/emoji-catalog.json` に同梱し、
 * ピッカーを開いたときに読み込む（[EmojiPickerCatalog]）。読み込むまでは、手で選んだ 120 個（[EmojiCatalog.fallbackCategories]）を使う。
 * 一覧ファイルは `scripts/gen-emoji-catalog.mjs` で作る（手で編集しない）。
 * 検索は Web の `searchEmojis` と同じ: ja / en のラベル・タグの部分一致と、絵文字そのものとの一致。
 * キーワードと検索語は NFKC + 小文字 + カタカナ→ひらがな にそろえてから比べる。
 */
data class EmojiEntry(val char: String, val keywords: List<String>)

/** [title] が null のカテゴリ（emojibase の更新で増えたもの等）は [fallbackTitle]（データの名前）を出す。 */
data class EmojiCategory(val title: StringResource?, val emojis: List<EmojiEntry>, val fallbackTitle: String = "")

/** [#868] 同梱の一覧ファイルを読んだ結果。[source] は `emojibase-data@<版>`。 */
data class FullEmojiCatalog(val source: String, val categories: List<EmojiCategory>)

object EmojiCatalog {

    /** [#868] 同梱の一覧ファイルの場所（`Res.readBytes` に渡すパス）。 */
    const val FULL_CATALOG_PATH = "files/emoji-catalog.json"

    /**
     * 読み込む前のフォールバック（手で選んだ 120 個）。Web の FALLBACK_CATEGORIES と同じ並び・キーワード。
     * キーワードは検索用に正規化しておく。
     */
    val fallbackCategories: List<EmojiCategory> get() = fallback

    private val fallback: List<EmojiCategory> by lazy {
        curated.map { c -> c.copy(emojis = c.emojis.map { e -> e.copy(keywords = e.keywords.map(::normalizeForSearch)) }) }
    }

    private val curated: List<EmojiCategory> = listOf(
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

    /** カテゴリを頭から見て、char の重複を除いた全エントリ。 */
    fun flatten(categories: List<EmojiCategory>): List<EmojiEntry> = categories.flatMap { it.emojis }.distinctBy { it.char }

    /** NFKC + 小文字 + カタカナ→ひらがな（Web の normalizeForSearch と同じ）。 */
    fun normalizeForSearch(s: String): String = buildString {
        for (ch in nfkc(s).lowercase()) append(if (ch in 'ァ'..'ヶ') ch - 0x60 else ch)
    }

    /**
     * emojibase の ja のラベル・タグは漢字（笑う・嬉しい 等）なので、ひらがなの読みで打つと一致しない。
     * よく使う感情語の完全な読みだけ漢字へ橋渡しする（Web の JA_READING_TO_KANJI と同じ表）。
     */
    private val JA_READING_TO_KANJI = listOf(
        "わらう" to "笑", "なく" to "泣", "おこる" to "怒", "いかり" to "怒", "かなしい" to "悲",
        "うれしい" to "嬉", "たのしい" to "楽", "こわい" to "怖", "すき" to "好", "ねむい" to "眠", "おどろく" to "驚",
    )

    /** [entries] から、日英キーワードの部分一致か、絵文字そのものとの一致で探す。空なら空（Web の searchEmojis と同じ）。 */
    fun search(query: String, entries: List<EmojiEntry>): List<EmojiEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        val q = normalizeForSearch(trimmed)
        val kanjiHints = JA_READING_TO_KANJI.filter { (reading, _) -> q.contains(reading) }.map { it.second }
        return entries.filter { e ->
            e.char == trimmed || e.keywords.any { k -> k.contains(q) || kanjiHints.any { k.contains(it) } }
        }
    }

    /** emojibase のグループ（key）→ カテゴリ名（Web の web_picker_group_* と同じ言い方）。 */
    private val GROUP_TITLES: Map<String, StringResource> = mapOf(
        "smileys-emotion" to Res.string.emoji_group_smileys_emotion,
        "people-body" to Res.string.emoji_group_people_body,
        "animals-nature" to Res.string.emoji_group_animals_nature,
        "food-drink" to Res.string.emoji_group_food_drink,
        "travel-places" to Res.string.emoji_group_travel_places,
        "activities" to Res.string.emoji_group_activities,
        "objects" to Res.string.emoji_group_objects,
        "symbols" to Res.string.emoji_group_symbols,
        "flags" to Res.string.emoji_group_flags,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 同梱の一覧ファイル（[FULL_CATALOG_PATH]）を読む。形が違えば null（呼び出し側はフォールバックのまま）。
     * 各絵文字は `[文字, 正規化済みキーワード…]` の配列。
     */
    fun parseFull(text: String): FullEmojiCatalog? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val source = root["source"]?.jsonPrimitive?.content.orEmpty()
        val categories = root.getValue("groups").jsonArray.map { g ->
            val o = g.jsonObject
            val key = o.getValue("key").jsonPrimitive.content
            val emojis = o.getValue("emojis").jsonArray.map { row ->
                val cells = (row as JsonArray).map { it.jsonPrimitive.content }
                EmojiEntry(cells.first(), cells.drop(1))
            }
            EmojiCategory(GROUP_TITLES[key], emojis, fallbackTitle = o["message"]?.jsonPrimitive?.content ?: key)
        }
        FullEmojiCatalog(source, categories).takeIf { categories.isNotEmpty() }
    }.getOrNull()
}

/**
 * [#868] ピッカーが使う今の一覧。初めは手で選んだ 120 個で、[ensureLoaded] が終わると標準の絵文字すべてに差し替わる
 * （snapshot state なので、開いているピッカーはそのまま組み直される）。読み込みはプロセスで 1 回だけ。
 */
object EmojiPickerCatalog {
    var categories: List<EmojiCategory> by mutableStateOf(EmojiCatalog.fallbackCategories)
        private set
    private var all: List<EmojiEntry> by mutableStateOf(EmojiCatalog.flatten(EmojiCatalog.fallbackCategories))
    private val mutex = Mutex()
    private var loaded = false

    suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return
            val full = withContext(Dispatchers.Default) {
                runCatching { Res.readBytes(EmojiCatalog.FULL_CATALOG_PATH).decodeToString() }.getOrNull()
                    ?.let(EmojiCatalog::parseFull)
            } ?: return
            categories = full.categories
            all = EmojiCatalog.flatten(full.categories)
            loaded = true
        }
    }

    fun search(query: String): List<EmojiEntry> = EmojiCatalog.search(query, all)
}
