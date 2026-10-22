package app.nostrdeck.store

import app.nostrdeck.crypto.Nip01
import app.nostrdeck.crypto.Nip19
import app.nostrdeck.crypto.hexToBytes
import app.nostrdeck.crypto.toHex
import app.nostrdeck.model.NostrEvent
import fr.acinq.secp256k1.Secp256k1
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.kotlincrypto.hash.sha2.SHA256

/**
 * [store] ストア用スクリーンショットのデモの世界。実在しない利用者（使い捨ての鍵）と、その投稿・リアクション・
 * リポスト・Zap・パブリックチャット・ステータスを、正しく署名したイベントとして作る（リレーには送らない）。
 *
 * 画像は手元の HTTP サーバー（[MEDIA_BASE]。`distribution/store/demo-media` を配る）から読む。
 * 文言は [ja] で日本語・英語を切り替える（アプリの表示言語と合わせる）。時刻は [now] からの相対。
 */
class DemoWorld(private val ja: Boolean, private val now: Long) {

    class User(val key: String, val nameJa: String, val nameEn: String, val aboutJa: String, val aboutEn: String, val banner: String? = null) {
        val secret: ByteArray = SHA256().digest("nostrism-store-demo-$key".encodeToByteArray())
        val pubkey: String = secp.pubKeyCompress(secp.pubkeyCreate(secret)).copyOfRange(1, 33).toHex()
    }

    val me = User("mio", "みお", "Mio", "Nostr はじめました。写真と猫とコーヒーが好き。", "New to Nostr. Into photos, cats and coffee.", "flowers")
    val hiyori = User("hiyori", "ひより", "Hiyori", "写真を撮っています。海と空が好き。", "I take photos. Love the sea and the sky.", "sunset")
    val nekomaru = User("nekomaru", "ねこまる", "Nekomaru", "猫と暮らしています。", "Living with a very lazy cat.")
    val kaito = User("kaito", "カイト", "Kaito", "アプリを作る人。Nostr のリレーを触るのが趣味。", "I build apps. Tinkering with Nostr relays for fun.", "night")
    val aoi = User("aoi", "あおい", "Aoi", "カフェ巡り。", "Cafe hopping.")
    val sora = User("sora", "そら", "Sora", "星を見るのが好き。", "Stargazer.", "night")
    val mochi = User("mochi", "もち", "Mochi", "絵を描いたり、絵文字を作ったり。", "I draw things and make emoji.")
    val yuzuki = User("yuzuki", "ゆずき", "Yuzuki", "音楽と作業用 BGM。", "Music and focus playlists.")
    val riku = User("riku", "りく", "Riku", "山登り。", "Hiking.", "mountains")
    val nana = User("nana", "なな", "Nana", "ベランダ菜園。", "Balcony gardener.")
    val users = listOf(me, hiyori, nekomaru, kaito, aoi, sora, mochi, yuzuki, riku, nana)

    private fun t(jaText: String, enText: String) = if (ja) jaText else enText
    private fun name(u: User) = if (ja) u.nameJa else u.nameEn
    private fun ago(minutes: Int) = now - minutes * 60L

    private val events = ArrayList<NostrEvent>()

    private fun sign(u: User, kind: Int, content: String, tags: List<List<String>>, createdAt: Long): NostrEvent {
        val id = Nip01.eventId(u.pubkey, createdAt, kind, tags, content)
        val sig = secp.signSchnorr(id.hexToBytes(), u.secret, null).toHex()
        return NostrEvent(id, u.pubkey, kind, createdAt, content, tags, sig).also { events += it }
    }

    private fun media(path: String) = "$MEDIA_BASE/$path"
    private fun imeta(path: String) = listOf("imeta", "url ${media(path)}", "m image/png", "dim 1200x900")
    private fun emojiTag(code: String) = listOf("emoji", code, media("emoji/$code.png"))

    private fun post(u: User, min: Int, text: String, photo: String? = null, hashtags: List<String> = emptyList(), extra: List<List<String>> = emptyList()): NostrEvent {
        val content = if (photo != null) "$text\n${media("photos/$photo.png")}" else text
        val tags = buildList {
            if (photo != null) add(imeta("photos/$photo.png"))
            hashtags.forEach { add(listOf("t", it)) }
            addAll(extra)
        }
        return sign(u, 1, content, tags, ago(min))
    }

    private fun reply(u: User, min: Int, text: String, to: NostrEvent, extra: List<List<String>> = emptyList()) =
        sign(u, 1, text, listOf(listOf("e", to.id, "", "root"), listOf("p", to.pubkey)) + extra, ago(min))

    /** 英語版では日本語の文字絵文字を英語の文字絵文字に置き換える */
    private val enEmoji = mapOf(
        "erai" to "nice", "sugoi" to "wow", "kawaii" to "cute", "otsu" to "thanks",
        "oishisou" to "yum", "wakaru" to "same", "kusa" to "lol", "kami" to "wow",
    )
    private fun loc(content: String): String =
        if (ja || !(content.startsWith(":") && content.endsWith(":"))) content
        else ":" + (enEmoji[content.trim(':')] ?: content.trim(':')) + ":"

    private fun react(u: User, min: Int, to: NostrEvent, raw: String = "+") {
        val content = loc(raw)
        val tags = buildList {
            add(listOf("e", to.id)); add(listOf("p", to.pubkey)); add(listOf("k", to.kind.toString()))
            if (content.startsWith(":") && content.endsWith(":")) add(emojiTag(content.trim(':')))
        }
        sign(u, 7, content, tags, ago(min))
    }

    private fun reactE(u: User, min: Int, to: NostrEvent, raw: String = "+"): NostrEvent {
        val content = loc(raw)
        val tags = buildList {
            add(listOf("e", to.id)); add(listOf("p", to.pubkey)); add(listOf("k", to.kind.toString()))
            if (content.startsWith(":") && content.endsWith(":")) add(emojiTag(content.trim(':')))
        }
        return sign(u, 7, content, tags, ago(min))
    }

    private fun repost(u: User, min: Int, of: NostrEvent) =
        sign(u, 6, eventJson(of), listOf(listOf("e", of.id), listOf("p", of.pubkey)), ago(min))

    private val zapService = User("zap", "", "", "", "")

    private fun zap(from: User, min: Int, to: NostrEvent, sats: Long, comment: String) {
        val request = buildJsonObject {
            put("kind", 9734); put("pubkey", from.pubkey); put("content", comment); put("created_at", ago(min))
            put("tags", buildJsonArray {
                add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("p")); add(kotlinx.serialization.json.JsonPrimitive(to.pubkey)) })
                add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("e")); add(kotlinx.serialization.json.JsonPrimitive(to.id)) })
                add(buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("amount")); add(kotlinx.serialization.json.JsonPrimitive((sats * 1000).toString())) })
            })
        }.toString()
        sign(
            zapService, 9735, "",
            listOf(listOf("p", to.pubkey), listOf("e", to.id), listOf("P", from.pubkey), listOf("description", request)),
            ago(min),
        )
    }

    /** スレッドの場面で開く id（ひよりが「みおの 👍」に付けた 🎉。木の中で光る）。 */
    lateinit var threadOpenId: String
    lateinit var catPost: NostrEvent
    lateinit var channelId: String
    lateinit var profileUser: User
    /** プロフィールの場面で、画面を開いてから取り込み直すフォローリスト（開いた人のフォロー数はそこから数える）。 */
    lateinit var hiyoriContacts: NostrEvent

    fun build(): List<NostrEvent> {
        // ---- プロフィール（kind:0）とフォロー（kind:3） ----
        users.forEach { u ->
            val meta = buildJsonObject {
                put("name", name(u)); put("display_name", name(u))
                put("about", if (ja) u.aboutJa else u.aboutEn)
                put("picture", media("avatars/${u.key}.png"))
                u.banner?.let { put("banner", media("banners/$it.png")) }
            }.toString()
            sign(u, 0, meta, emptyList(), ago(60 * 24 * 30))
        }
        sign(me, 3, "", users.filter { it !== me }.map { listOf("p", it.pubkey) }, ago(60 * 24))
        hiyoriContacts = sign(hiyori, 3, "", listOf(me, nekomaru, kaito, aoi, sora, riku, nana).map { listOf("p", it.pubkey) }, ago(60 * 24))

        // ---- タイムライン ----
        val sunrise = post(hiyori, 1, t("朝焼けがきれいだったので、少し早起きして海まで🌅\n#写真 #photography", "Woke up early to catch the sunrise at the beach 🌅\n#photography"), "sunset-sea", listOf("photography", "写真"))
        val relays = post(kaito, 9, t("リレーを3つに絞ったら起動がかなり軽くなった。読むリレーと書くリレーを分けられるの、地味に便利。 #nostr", "Trimmed my relays down to three and startup got way snappier. Being able to split read and write relays is quietly great. #nostr"), hashtags = listOf("nostr"))
        catPost = post(nekomaru, 18, t("今日もこたつから出てこない🐈", "Not leaving the kotatsu today, apparently 🐈"), "cat-nap")
        val night = post(sora, 64, t("今夜は空が澄んでて、オリオン座がくっきり✨ #photography", "Clear skies tonight — Orion is so sharp ✨ #photography"), "night-sky", listOf("photography"))
        repost(riku, 22, night)
        val latte = post(aoi, 34, t("駅前に新しいカフェができてた。ラテアートがハートだった☕", "Found a new cafe by the station. Got a heart in my latte ☕"), "latte")
        val emojiPost = post(mochi, 47, t("文字から絵文字を作れるの楽しい :erai: :sugoi: :kawaii: :otsu:", "Making emoji from text is way too fun :nice: :wow: :cute: :thanks:"),
            extra = if (ja) listOf(emojiTag("erai"), emojiTag("sugoi"), emojiTag("kawaii"), emojiTag("otsu")) else listOf(emojiTag("nice"), emojiTag("wow"), emojiTag("cute"), emojiTag("thanks")))
        val playlist = post(yuzuki, 52, t("作業用プレイリストを更新した。今日はこれで集中する🎧", "Updated my focus playlist. Heads down for the rest of the day 🎧"))
        val omurice = post(me, 70, t("お昼はオムライス🍳 ケチャップでハート描けた", "Omurice for lunch 🍳 managed a ketchup heart"), "omurice")
        val flowers = post(nana, 85, t("ベランダの花が一気に咲いた🌼 #photography", "The balcony flowers all bloomed at once 🌼 #photography"), "flowers", listOf("photography"))
        val ridge = post(hiyori, 130, t("山の稜線が何層にも重なって見える朝。 #photography", "Morning ridgelines, layer after layer. #photography"), "mountains", listOf("photography"))
        val hello = post(me, 60 * 5, t("Nostr はじめました。よろしくお願いします！", "Just joined Nostr. Nice to meet you all!"))
        sign(kaito, 1, t("これは早起きする価値ある\nnostr:${Nip19.hexToNote(sunrise.id)}", "Worth waking up early for\nnostr:${Nip19.hexToNote(sunrise.id)}"), listOf(listOf("q", sunrise.id), listOf("p", hiyori.pubkey)), ago(5))

        // ---- リアクション（タイムライン） ----
        listOf(aoi, sora, mochi, nana, riku, yuzuki).forEachIndexed { i, u -> react(u, 2 + i, sunrise, if (i % 3 == 0) ":sugoi:" else "+") }
        react(me, 2, sunrise, "+")
        sign(hiyori, 1, t("帰り道の夕焼けも良かった #photography", "The sunset on the way home was lovely too #photography"), listOf(imeta("photos/sunset-sea.png"), listOf("t", "photography")), ago(60 * 20))
        listOf(hiyori, aoi, nana).forEach { react(it, 6, relays, if (it === aoi) ":wakaru:" else "👍") }
        react(me, 7, relays, "👍")
        listOf(aoi, mochi, hiyori, sora, yuzuki).forEach { react(it, 12, catPost, ":kawaii:") }
        listOf(kaito, riku, nana, me).forEach { react(it, 14, catPost, "❤️") }
        val myThumb = reactE(me, 15, catPost, "👍")
        listOf(hiyori, kaito).forEach { react(it, 30, latte, ":oishisou:") }
        listOf(me, sora, nana).forEach { react(it, 31, latte, "❤️") }
        listOf(nekomaru, hiyori, kaito, aoi).forEach { react(it, 40, emojiPost, ":erai:") }
        react(me, 41, emojiPost, ":kawaii:")
        listOf(me, kaito).forEach { react(it, 50, playlist, "🎧") }
        listOf(hiyori, me).forEach { react(it, 80, flowers, "🌼") }
        listOf(sora, riku, me, nana).forEach { react(it, 120, ridge, "+") }
        listOf(kaito, aoi).forEach { react(it, 60, night, ":sugoi:") }

        // ---- スレッド（猫の投稿）: 返信と、リアクションへのリアクション ----
        reply(aoi, 16, t("かわいすぎる…こたつは猫を溶かす", "Too cute… kotatsu melts cats"), catPost)
        reply(mochi, 11, t("こたつは魔物", "The kotatsu always wins"), catPost)
        reply(me, 6, t("わかる、冬は出られない", "Same. Nobody leaves the kotatsu in winter"), catPost)
        val tada = reactE(hiyori, 6, myThumb, "🎉")
        reactE(kaito, 11, myThumb, ":erai:")
        threadOpenId = tada.id

        // ---- 通知（自分宛て） ----
        reply(sora, 2, t("美味しそう！ハートかわいい", "Looks delicious! Love the heart"), omurice)
        zap(kaito, 4, omurice, 500, t("ナイスオムライス！", "Nice omurice!"))
        react(aoi, 8, omurice, ":oishisou:")
        repost(riku, 13, omurice)
        sign(yuzuki, 1, t("nostr:${Nip19.hexToNpub(me.pubkey)} 今度おすすめのカフェ教えて〜", "nostr:${Nip19.hexToNpub(me.pubkey)} send me your favorite cafes sometime!"), listOf(listOf("p", me.pubkey)), ago(17))
        react(hiyori, 21, omurice, "❤️")
        react(nekomaru, 25, omurice, "❤️")
        react(kaito, 26, omurice, ":kusa:")
        listOf(hiyori, kaito, aoi, sora, mochi, nekomaru, nana).forEachIndexed { i, u -> react(u, 60 * 4 + i, hello, if (i % 2 == 0) "+" else ":thanks:") }
        react(mochi, 33, hello, ":erai:")

        // ---- パブリックチャット（NIP-28） ----
        val channel = sign(
            kaito, 40,
            buildJsonObject {
                put("name", t("Nostrism 雑談", "Nostrism Lounge"))
                put("about", t("ゆるく話す部屋", "A chill place to chat"))
                put("picture", media("avatars/kaito.png"))
            }.toString(),
            emptyList(), ago(60 * 24 * 7),
        )
        channelId = channel.id
        val chat = listOf(
            kaito to t("新しいバージョン入れた？スレッドのリアクションが木になってて見やすい", "Did you update? Reactions in threads are a tree now, so much easier to follow"),
            hiyori to t("入れた！通知から元の投稿に飛べるの便利", "Yep! Love that notifications jump to the original post"),
            me to t("文字から絵文字を作れるの、さっき知った", "Just found out you can make emoji from text"),
            mochi to t(":erai: こんな感じで作れるよ", ":nice: you can make ones like this"),
            sora to t("今夜は流星群らしいよ🌠", "Meteor shower tonight 🌠"),
            aoi to t("見たい！晴れるといいな", "Hope the sky stays clear!"),
            yuzuki to t("BGM は任せて🎧", "I'll bring the music 🎧"),
            me to t("みんなで見よう〜", "Let's all watch together"),
        )
        chat.forEachIndexed { i, (u, text) ->
            val extra = if (text.startsWith(":")) listOf(emojiTag(text.substringAfter(':').substringBefore(':'))) else emptyList()
            sign(u, 42, text, listOf(listOf("e", channel.id, "", "root")) + extra, ago(30 - i * 3))
        }

        // ---- ステータス（NIP-38） ----
        sign(hiyori, 30315, t("📷 撮影散歩中", "📷 Out on a photo walk"), listOf(listOf("d", "general")), ago(20))
        sign(yuzuki, 30315, t("集中モード", "Deep focus"), listOf(listOf("d", "general")), ago(30))
        profileUser = hiyori

        return events.toList()
    }

    companion object {
        private val secp = Secp256k1.get()
        const val MEDIA_PORT = 18765
        const val MEDIA_BASE = "http://127.0.0.1:$MEDIA_PORT"

        private fun eventJson(e: NostrEvent): String = buildJsonObject {
            put("id", e.id); put("pubkey", e.pubkey); put("created_at", e.createdAt); put("kind", e.kind)
            put("tags", buildJsonArray { e.tags.forEach { t -> add(buildJsonArray { t.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }) } })
            put("content", e.content); put("sig", e.sig)
        }.toString()
    }
}
