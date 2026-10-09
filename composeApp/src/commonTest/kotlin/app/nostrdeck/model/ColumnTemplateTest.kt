package app.nostrdeck.model

import app.nostrdeck.data.SampleData
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [#792] カラム追加の一覧（ColumnTemplate）から作るカラムの形を固定する。 */
class ColumnTemplateTest {

    @Test
    fun CHANNEL_LISTはパブリックチャット画面の一覧と同じ形のカラムを作る() {
        val spec = ColumnTemplate.CHANNEL_LIST.build()
        assertEquals(ColumnKind.CHANNEL_LIST, spec.kind)
        assertEquals(ColumnRenderer.CHANNEL_LIST, spec.renderer)
        assertEquals(ReqFilter(kinds = listOf(40, 41)), spec.filter)
        // タイトルは正準キー（columnDisplayTitle がロケールへ写す）。保存・同期されるので変えない。
        assertEquals("パブリックチャット", spec.title)
        assertTrue(spec.pinned)
        assertTrue(spec.id.startsWith("col_channel_list_"))
        // タイトル・サブタイトル・filter はパブリックチャット画面が使う仕様と揃える。
        val screen = SampleData.channelListColumn
        assertEquals(screen.title, spec.title)
        assertEquals(screen.subtitle, spec.subtitle)
        assertEquals(screen.filter, spec.filter)
    }

    @Test
    fun CHANNEL_LISTのfilterは同期のJSONでkindsだけになる() {
        // 同期（NIP-78）の DeckColumnDto と同じ設定（encodeDefaults = false）。Web の columns.ts と同じ形。
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(
            """{"kinds":[40,41]}""",
            json.encodeToString(ReqFilter.serializer(), ColumnTemplate.CHANNEL_LIST.build().filter),
        )
    }

    @Test
    fun CHANNEL_LISTは設定なしで追加でき_ステータスの前に並ぶ() {
        val t = ColumnTemplate.CHANNEL_LIST
        assertEquals(ColumnConfig.NONE, t.config)
        assertTrue(t.acceptsInput(""))
        assertNull(t.build().editTemplate())   // 編集する設定を持たない
        val entries = ColumnTemplate.entries
        assertEquals(entries.indexOf(ColumnTemplate.STATUS) - 1, entries.indexOf(t))
    }
}
