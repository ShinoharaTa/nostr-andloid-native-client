package app.nostrdeck.ui

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nostr_deck_client.composeapp.generated.resources.Res
import nostr_deck_client.composeapp.generated.resources.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [#868] 同梱した標準の絵文字すべての一覧（`files/emoji-catalog.json`）。
 * 版は Web の固定した emojibase-data と同じであること（違えば scripts/gen-emoji-catalog.mjs で作り直す）。
 */
class EmojiCatalogFullTest {
    private val bundled = File("src/commonMain/composeResources/files/emoji-catalog.json")
    private val catalog by lazy { assertNotNull(EmojiCatalog.parseFull(bundled.readText())) }
    private val all by lazy { EmojiCatalog.flatten(catalog.categories) }

    @Test
    fun bundled_catalog_is_the_same_version_as_web() {
        val lock = Json.parseToJsonElement(File("../web/package-lock.json").readText()).jsonObject
        val webVersion = lock.getValue("packages").jsonObject.getValue("node_modules/emojibase-data").jsonObject
            .getValue("version").jsonPrimitive.content
        assertEquals("emojibase-data@$webVersion", catalog.source, "scripts/gen-emoji-catalog.mjs で作り直すこと")
    }

    @Test
    fun has_all_standard_emoji_in_web_categories_without_duplicates() {
        assertTrue(all.size > 1500, "${all.size}")
        assertEquals(all.size, all.map { it.char }.toSet().size)
        assertEquals(
            listOf(
                Res.string.emoji_group_smileys_emotion, Res.string.emoji_group_people_body,
                Res.string.emoji_group_animals_nature, Res.string.emoji_group_food_drink,
                Res.string.emoji_group_travel_places, Res.string.emoji_group_activities,
                Res.string.emoji_group_objects, Res.string.emoji_group_symbols, Res.string.emoji_group_flags,
            ),
            catalog.categories.map { it.title },
        )
    }

    @Test
    fun japanese_reading_and_english_both_find_smile() {
        fun search(q: String) = EmojiCatalog.search(q, all).map { it.char }
        assertTrue("😄" in search("わらう"))   // ひらがなの読み → 漢字のラベル（笑）
        assertTrue("😄" in search("smile"))
        assertTrue("🔥" in search("FIRE"))
        assertTrue("🇯🇵" in search("🇯🇵"))
    }

    @Test
    fun picker_can_load_the_bundled_catalog_as_a_resource() = runBlocking {
        EmojiPickerCatalog.ensureLoaded()
        assertEquals(catalog.categories.size, EmojiPickerCatalog.categories.size)
        assertTrue("😄" in EmojiPickerCatalog.search("smile").map { it.char })
    }
}
