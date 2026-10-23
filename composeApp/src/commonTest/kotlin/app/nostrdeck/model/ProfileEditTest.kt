package app.nostrdeck.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [#920] プロフィール編集の初期値と、発行する content。name と display_name を別の項目として扱い（NIP-24）、
 * 片方を変えてももう片方を書き換えないことを守る。Web の profileEdit.test.ts（#922）も同じケースにそろえる。
 */
class ProfileEditTest {

    private fun obj(content: String): JsonObject = Json.parseToJsonElement(content).jsonObject
    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    // ---- 初期値 ----

    @Test
    fun fields_keep_name_and_display_name_apart() {
        val f = ProfileEdit.fieldsOf("""{"name":"shino3","display_name":"しの","about":"hi"}""")
        assertEquals("shino3", f["name"])
        assertEquals("しの", f["display_name"])
        assertEquals("hi", f["about"])
    }

    @Test
    fun display_name_falls_back_to_legacy_displayName() {
        val f = ProfileEdit.fieldsOf("""{"name":"shino3","display_name":"","displayName":"しの"}""")
        assertEquals("しの", f["display_name"])
        assertEquals("shino3", f["name"])
    }

    @Test
    fun name_does_not_fill_display_name() {
        // 表示名が無い人の表示名の欄は空のまま（name を表示名の欄に出さない）。
        val f = ProfileEdit.fieldsOf("""{"name":"shino3"}""")
        assertEquals("", f["display_name"])
        assertEquals("shino3", f["name"])
    }

    @Test
    fun non_string_values_and_broken_json_are_blank() {
        val f = ProfileEdit.fieldsOf("""{"name":123,"about":null}""")
        assertEquals("", f["name"])
        assertEquals("", f["about"])
        assertTrue(ProfileEdit.fieldsOf("not json").values.all { it.isEmpty() })
        assertTrue(ProfileEdit.fieldsOf(null).values.all { it.isEmpty() })
        assertEquals(ProfileEdit.FIELDS.toSet(), ProfileEdit.fieldsOf(null).keys)
    }

    // ---- 変えた欄 ----

    @Test
    fun changed_has_only_edited_fields_trimmed() {
        val initial = ProfileEdit.fieldsOf("""{"name":"shino3","display_name":"しの","about":"hi"}""")
        val current = initial + mapOf("display_name" to " しの🌸 ", "about" to "hi  ")
        assertEquals(mapOf("display_name" to "しの🌸"), ProfileEdit.changed(initial, current))
    }

    // ---- 発行する content ----

    @Test
    fun editing_display_name_keeps_name() {
        // 不具合の再現: 以前は name も「しの🌸」になって「shino3」が消えていた。
        val out = obj(ProfileEdit.buildContent("""{"name":"shino3","display_name":"しの"}""", mapOf("display_name" to "しの🌸")))
        assertEquals("shino3", out.text("name"))
        assertEquals("しの🌸", out.text("display_name"))
    }

    @Test
    fun editing_name_keeps_display_name() {
        val out = obj(ProfileEdit.buildContent("""{"name":"shino3","display_name":"しの"}""", mapOf("name" to "shino")))
        assertEquals("shino", out.text("name"))
        assertEquals("しの", out.text("display_name"))
    }

    @Test
    fun editing_name_does_not_create_display_name() {
        val out = obj(ProfileEdit.buildContent("""{"name":"shino3"}""", mapOf("name" to "shino")))
        assertEquals("shino", out.text("name"))
        assertFalse("display_name" in out)
    }

    @Test
    fun unknown_keys_stay_and_blank_removes_key() {
        val out = obj(
            ProfileEdit.buildContent(
                """{"name":"shino3","about":"hi","custom_app_field":{"x":1},"lud06":"lnurl1"}""",
                mapOf("about" to "  "),
            ),
        )
        assertFalse("about" in out)
        assertEquals("shino3", out.text("name"))
        assertTrue("custom_app_field" in out)
        assertEquals("lnurl1", out.text("lud06"))
    }

    @Test
    fun legacy_displayName_moves_to_display_name_when_missing() {
        val out = obj(ProfileEdit.buildContent("""{"name":"shino3","displayName":"しの"}""", mapOf("about" to "hi")))
        assertFalse("displayName" in out)
        assertEquals("しの", out.text("display_name"))
        assertEquals("shino3", out.text("name"))
    }

    @Test
    fun legacy_displayName_is_dropped_when_display_name_exists() {
        val out = obj(ProfileEdit.buildContent("""{"display_name":"しの","displayName":"old"}""", mapOf("about" to "hi")))
        assertFalse("displayName" in out)
        assertEquals("しの", out.text("display_name"))
    }

    @Test
    fun clearing_display_name_does_not_revive_legacy_value() {
        // 表示名の欄（displayName から読んだ値）を空にして保存したら、表示名は消えたまま。
        val out = obj(ProfileEdit.buildContent("""{"name":"shino3","displayName":"しの"}""", mapOf("display_name" to "")))
        assertFalse("displayName" in out)
        assertFalse("display_name" in out)
        assertEquals("shino3", out.text("name"))
    }

    @Test
    fun no_base_publishes_only_changed_fields() {
        val out = obj(ProfileEdit.buildContent(null, mapOf("name" to "shino3", "display_name" to "しの", "about" to "")))
        assertEquals(setOf("name", "display_name"), out.keys)
    }
}
