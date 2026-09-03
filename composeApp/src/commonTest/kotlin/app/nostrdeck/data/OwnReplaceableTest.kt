package app.nostrdeck.data

import app.nostrdeck.model.NostrEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** [#396] 自分の replaceable リスト共通処理: 受信ゲート・KV バックアップ・リセット。 */
class OwnReplaceableTest {

    private class MapStore : OwnListStore {
        val map = mutableMapOf<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String) { map[key] = value }
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun ev(kind: Int, at: Long, tags: List<List<String>>, pubkey: String = "me") =
        NostrEvent("id$at", pubkey, kind, at, "", tags)

    /** t タグ値の一覧を導出する簡単なリスト。 */
    private fun rep(store: MapStore? = null, key: String? = null, dTag: String? = null, legacy: ((String) -> OwnListBackup?)? = null) =
        OwnReplaceable(
            kind = 30015, subId = "x", dTag = dTag, backupKey = key, store = store, json = json, legacyDecode = legacy,
            derive = { tags -> tags.filter { it.size >= 2 && it[0] == "t" }.map { it[1] } },
        )

    @Test
    fun filter_carries_kind_author_d_and_limit() {
        val f = rep(dTag = "pinned").filter("me")
        assertEquals(listOf(30015), f.kinds)
        assertEquals(listOf("me"), f.authors)
        assertEquals(listOf("pinned"), f.dTags)
        assertEquals(1, f.limit)
        assertNull(rep().filter("me").dTags)
    }

    @Test
    fun accept_rejects_others_other_kind_other_d_and_logged_out() {
        val r = rep(dTag = "pinned")
        val tags = listOf(listOf("d", "pinned"), listOf("t", "a"))
        assertFalse(r.accept(ev(30015, 10, tags, pubkey = "other"), "me"))
        assertFalse(r.accept(ev(10015, 10, tags), "me"))
        assertFalse(r.accept(ev(30015, 10, listOf(listOf("d", "cooking"), listOf("t", "a"))), "me"))
        assertFalse(r.accept(ev(30015, 10, tags), null))
        assertEquals(emptyList(), r.state.value)
        assertEquals(0L, r.at)
    }

    @Test
    fun accept_takes_newer_or_same_time_and_drops_older() {
        val r = rep()
        assertTrue(r.accept(ev(30015, 100, listOf(listOf("t", "a"))), "me"))
        assertEquals(listOf("a"), r.state.value)
        assertFalse(r.accept(ev(30015, 50, listOf(listOf("t", "old"))), "me"))   // 古い版
        assertEquals(listOf("a"), r.state.value)
        assertTrue(r.accept(ev(30015, 100, listOf(listOf("t", "same"))), "me"))  // 同時刻は受け入れる
        assertEquals(listOf("same"), r.state.value)
        assertTrue(r.accept(ev(30015, 200, listOf(listOf("t", "b"))), "me"))
        assertEquals(200L, r.at)
    }

    @Test
    fun commit_persists_tags_and_at_and_restore_reads_them_back() {
        val store = MapStore()
        val r = rep(store, "k")
        r.commit(listOf(listOf("t", "a"), listOf("x", "keep")), 123)
        assertTrue("k" in store.map)

        val r2 = rep(store, "k")
        assertTrue(r2.restore())
        assertEquals(listOf("a"), r2.state.value)
        assertEquals(123L, r2.at)
        assertEquals(listOf(listOf("t", "a"), listOf("x", "keep")), r2.tags)
        // 復元した版より古いエコーは潰さない
        assertFalse(r2.accept(ev(30015, 100, listOf(listOf("t", "stale"))), "me"))
    }

    @Test
    fun restore_falls_back_to_legacy_format() {
        val store = MapStore()
        // 旧形式: 生タグの JSON 配列のみ（created_at 無し）
        store.map["k"] = json.encodeToString(ListSerializer(ListSerializer(String.serializer())), listOf(listOf("t", "legacy")))
        val r = rep(store, "k", legacy = { raw ->
            OwnListBackup(json.decodeFromString(ListSerializer(ListSerializer(String.serializer())), raw), 0L)
        })
        assertTrue(r.restore())
        assertEquals(listOf("legacy"), r.state.value)
        assertEquals(0L, r.at)
        // 旧形式で読めない・legacy 無しなら復元しない
        assertFalse(rep(store, "k").restore())
        assertFalse(rep(store, "missing").restore())
        assertFalse(rep(store, null).restore())
    }

    @Test
    fun reset_clears_state_at_and_backup() {
        val store = MapStore()
        val r = rep(store, "k")
        r.commit(listOf(listOf("t", "a")), 100)
        r.reset()
        assertEquals(emptyList(), r.state.value)
        assertEquals(0L, r.at)
        assertEquals(emptyList(), r.tags)
        val r2 = rep(store, "k")
        assertTrue(r2.restore())
        assertEquals(emptyList(), r2.state.value)
    }
}
