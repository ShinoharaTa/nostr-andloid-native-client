package app.nostrdeck.data

import app.nostrdeck.model.ColumnKind
import app.nostrdeck.model.ColumnRenderer
import app.nostrdeck.model.ColumnSpec
import app.nostrdeck.model.ReqFilter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#374] リレー同期の差分計算（設定・カラム構成）と個別適用のテスト。 */
class SettingsSyncDiffTest {

    // ---- 設定の差分 ----

    @Test
    fun settings_same_values_yield_no_diff() {
        val local = mapOf(
            EventRepository.AUTH_POLICY to "dm",
            EventRepository.BOLD_TEXT_KEY to "0",
        )
        assertEquals(emptyList(), diffSyncSettings(local, local))
    }

    @Test
    fun settings_changed_values_are_listed_in_whitelist_order() {
        val local = mapOf(
            EventRepository.AUTH_POLICY to "dm",
            EventRepository.BOLD_TEXT_KEY to "0",
            EventRepository.NOTE_ACCENT_STYLE_KEY to "none",
        )
        val remote = mapOf(
            EventRepository.NOTE_ACCENT_STYLE_KEY to "line",
            EventRepository.AUTH_POLICY to "always",
            EventRepository.BOLD_TEXT_KEY to "0",
        )
        val diffs = diffSyncSettings(local, remote)
        assertEquals(
            listOf(
                SettingDiff(EventRepository.AUTH_POLICY, "dm", "always"),
                SettingDiff(EventRepository.NOTE_ACCENT_STYLE_KEY, "none", "line"),
            ),
            diffs,
        )
    }

    @Test
    fun settings_keys_missing_on_remote_or_unknown_are_ignored() {
        val local = mapOf(
            EventRepository.AUTH_POLICY to "dm",
            EventRepository.BOLD_TEXT_KEY to "1",
        )
        // リモートは古いスナップショット（AUTH_POLICY 無し）+ ホワイトリスト外のキー。
        val remote = mapOf(
            EventRepository.BOLD_TEXT_KEY to "0",
            "unknown:key" to "x",
        )
        assertEquals(
            listOf(SettingDiff(EventRepository.BOLD_TEXT_KEY, "1", "0")),
            diffSyncSettings(local, remote),
        )
    }

    // ---- カラム構成の差分 ----

    private fun col(id: String, title: String = id, order: Int = 0, hashtags: List<String> = emptyList()) =
        ColumnSpec(
            id = id, title = title, subtitle = "",
            kind = if (hashtags.isEmpty()) ColumnKind.FOLLOWING else ColumnKind.HASHTAG,
            renderer = ColumnRenderer.FEED,
            filter = ReqFilter(hashtags = hashtags),
            pinned = true, order = order,
        )

    @Test
    fun columns_identical_yield_no_diff() {
        val cols = listOf(col("a", order = 0), col("b", order = 1))
        assertEquals(emptyList(), diffDeckColumns(cols, cols))
    }

    @Test
    fun columns_added_removed_changed_are_detected_per_column() {
        val local = listOf(col("a", order = 0), col("b", title = "旧タイトル", order = 1))
        val remote = listOf(
            col("b", title = "新タイトル", order = 0),
            col("c", title = "追加カラム", order = 1),
        )
        val diffs = diffDeckColumns(local, remote)
        assertTrue(diffs.any { it is ColumnDiff.Added && it.spec.id == "c" })
        assertTrue(diffs.any { it is ColumnDiff.Removed && it.spec.id == "a" })
        assertTrue(diffs.any { it is ColumnDiff.Changed && it.remote.title == "新タイトル" })
        // 共通カラムは b の1つだけなので並び順の差分は出ない。
        assertTrue(diffs.none { it is ColumnDiff.Reordered })
    }

    @Test
    fun columns_filter_change_is_detected() {
        val local = listOf(col("a", hashtags = listOf("nostr")))
        val remote = listOf(col("a", hashtags = listOf("zap")))
        val diffs = diffDeckColumns(local, remote)
        assertEquals(1, diffs.size)
        assertTrue(diffs[0] is ColumnDiff.Changed)
    }

    @Test
    fun columns_reorder_only_yields_single_reordered_diff() {
        val local = listOf(col("a", order = 0), col("b", order = 1))
        val remote = listOf(col("b", order = 0), col("a", order = 1))
        val diffs = diffDeckColumns(local, remote)
        assertEquals(listOf<ColumnDiff>(ColumnDiff.Reordered(listOf("b", "a"))), diffs)
    }

    // ---- 個別適用 ----

    @Test
    fun apply_selected_diffs_only() {
        val local = listOf(col("a", order = 0), col("b", title = "旧", order = 1))
        val remote = listOf(col("b", title = "新", order = 0), col("c", order = 1))
        val diffs = diffDeckColumns(local, remote)
        // 追加(c)だけ選択。削除(a)・変更(b)は未選択のまま。
        val selected = diffs.filterIsInstance<ColumnDiff.Added>()
        val applied = applyColumnDiffs(local, selected)
        assertEquals(listOf("a", "b", "c"), applied.map { it.id }.sorted())
        assertEquals("旧", applied.first { it.id == "b" }.title)
    }

    @Test
    fun apply_all_diffs_reproduces_remote() {
        val local = listOf(col("a", order = 0), col("b", title = "旧", order = 1), col("d", order = 2))
        val remote = listOf(
            col("c", title = "追加", order = 0),
            col("b", title = "新", order = 1),
            col("a", order = 2),
        )
        val diffs = diffDeckColumns(local, remote)
        val applied = applyColumnDiffs(local, diffs)
        assertEquals(listOf("c", "b", "a"), applied.map { it.id })
        assertEquals("新", applied.first { it.id == "b" }.title)
        assertEquals(listOf(0, 1, 2), applied.map { it.order })
    }

    @Test
    fun apply_reorder_keeps_local_only_columns_at_tail() {
        val local = listOf(col("x", order = 0), col("a", order = 1), col("b", order = 2))
        val remote = listOf(col("b", order = 0), col("a", order = 1))
        val diffs = diffDeckColumns(local, remote)
        // 並び順だけ選択（x の削除は選択しない）。
        val selected = diffs.filterIsInstance<ColumnDiff.Reordered>()
        val applied = applyColumnDiffs(local, selected)
        assertEquals(listOf("b", "a", "x"), applied.map { it.id })
    }

    @Test
    fun apply_removed_diff_deletes_column() {
        val local = listOf(col("a", order = 0), col("b", order = 1))
        val remote = listOf(col("a", order = 0))
        val diffs = diffDeckColumns(local, remote)
        val applied = applyColumnDiffs(local, diffs.filterIsInstance<ColumnDiff.Removed>())
        assertEquals(listOf("a"), applied.map { it.id })
    }

    // ---- [#778] 知らない種別のカラムを、同期の保存で消さない ----

    // a（知っている）/ x（知らない kind。未知のフィールド付き）/ b（知っている）/ y（知らない renderer）
    private val unknownX =
        """{"id":"x","title":"未来","kind":"FUTURE_KIND","renderer":"FEED","filter":{"kinds":[30315]},"order":1,"extra":{"nested":[1,"two"]}}"""
    private val unknownY = """{"id":"y","title":"y","kind":"FOLLOWING","renderer":"GRID","order":3}"""
    private val snapshotWithUnknown =
        """[{"id":"a","title":"a","kind":"FOLLOWING","renderer":"FEED"},""" + unknownX + "," +
            """{"id":"b","title":"b","kind":"FOLLOWING","renderer":"FEED","order":2},""" + unknownY + "]"

    private fun rows(content: String): JsonArray = Json.parseToJsonElement(content).jsonArray
    private fun idOf(row: JsonElement): String = row.jsonObject.getValue("id").jsonPrimitive.content

    @Test
    fun decode_keeps_unknown_rows_raw_with_their_position() {
        val decoded = assertNotNull(decodeDeckColumnsContent(snapshotWithUnknown))
        assertEquals(listOf("a", "b"), decoded.columns.map { it.id })
        assertEquals(listOf(1, 3), decoded.unknown.map { it.position })
        assertEquals(Json.parseToJsonElement(unknownX), decoded.unknown[0].raw)
        assertEquals(Json.parseToJsonElement(unknownY), decoded.unknown[1].raw)
    }

    @Test
    fun decode_counts_position_in_order_not_array_index() {
        // 配列の順と order の順が違う: 知らない行は order 順で 0 番目。
        val content = """[{"id":"a","title":"a","kind":"FOLLOWING","renderer":"FEED","order":1},""" +
            """{"id":"z","title":"z","kind":"FUTURE_KIND","renderer":"FEED"}]"""
        val decoded = assertNotNull(decodeDeckColumnsContent(content))
        assertEquals(listOf(0), decoded.unknown.map { it.position })
    }

    @Test
    fun decode_broken_json_is_null() {
        assertNull(decodeDeckColumnsContent("{not json"))
    }

    @Test
    fun load_then_save_keeps_unknown_rows_at_same_position_and_content() {
        val decoded = assertNotNull(decodeDeckColumnsContent(snapshotWithUnknown))
        val saved = rows(encodeDeckColumnsContent(decoded.columns.sortedBy { it.order }, decoded.unknown))
        assertEquals(listOf("a", "x", "b", "y"), saved.map { idOf(it) })
        assertEquals(Json.parseToJsonElement(unknownX), saved[1])
        assertEquals(Json.parseToJsonElement(unknownY), saved[3])
        // 読み直しても同じ（知っている行は同じ中身・知らない行も同じ位置）。
        val again = assertNotNull(decodeDeckColumnsContent(saved.toString()))
        assertEquals(decoded.columns.map { it.id to it.title }, again.columns.map { it.id to it.title })
        assertEquals(decoded.unknown, again.unknown)
    }

    @Test
    fun unknown_rows_are_not_listed_as_removed_in_diff() {
        val decoded = assertNotNull(decodeDeckColumnsContent(snapshotWithUnknown))
        val local = listOf(col("a", order = 0), col("b", order = 1))
        val diffs = diffDeckColumns(local, decoded.columns)
        assertTrue(diffs.none { it is ColumnDiff.Removed })
        assertEquals(emptyList(), diffs)
    }

    @Test
    fun editing_known_rows_still_works_with_unknown_rows_kept() {
        val decoded = assertNotNull(decodeDeckColumnsContent(snapshotWithUnknown))
        // 手元: a を外し、b のタイトルを変え、c を足した。
        val local = listOf(col("b", title = "新しい b", order = 0), col("c", order = 1))
        val saved = rows(encodeDeckColumnsContent(local, decoded.unknown))
        // x は位置 1、y は位置 3 だが手元が減ったので末尾へ。
        assertEquals(listOf("b", "x", "c", "y"), saved.map { idOf(it) })
        assertEquals("新しい b", saved[0].jsonObject.getValue("title").jsonPrimitive.content)
        assertEquals(Json.parseToJsonElement(unknownX), saved[1])
        assertEquals(Json.parseToJsonElement(unknownY), saved[3])
        // 知っている行の order は書き出した位置で振り直す（0 は既定なので省かれる）。
        assertNull(saved[0].jsonObject["order"])
        assertEquals("2", saved[2].jsonObject.getValue("order").jsonPrimitive.content)
    }

    @Test
    fun save_without_unknown_rows_keeps_previous_format() {
        val local = listOf(col("a", order = 0), col("b", order = 1))
        assertEquals(
            """[{"id":"a","title":"a","kind":"FOLLOWING","renderer":"FEED"},""" +
                """{"id":"b","title":"b","kind":"FOLLOWING","renderer":"FEED","order":1}]""",
            encodeDeckColumnsContent(local, emptyList()),
        )
    }
}
