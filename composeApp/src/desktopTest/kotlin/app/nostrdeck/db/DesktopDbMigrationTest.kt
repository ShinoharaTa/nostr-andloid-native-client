package app.nostrdeck.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [#736] Desktop の DB を、版の番号を見て作成・移行すること。番号を記録していなかった頃の DB
 * （`user_version` 0 のまま表がある）は、表・列から版を判断してから移行し、中身を残すこと。
 */
class DesktopDbMigrationTest {

    private val dir = kotlin.io.path.createTempDirectory("nostrdb").toFile()
    private val file = File(dir, "nostr.db")
    private val url = "jdbc:sqlite:${file.absolutePath}"

    @AfterTest
    fun cleanup() { dir.deleteRecursively() }

    private fun <T> jdbc(block: (Connection) -> T): T = DriverManager.getConnection(url).use(block)

    private fun Connection.exec(vararg sql: String) = createStatement().use { s -> sql.forEach { s.execute(it) } }

    private fun Connection.long(sql: String): Long =
        createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } }

    private fun Connection.strings(sql: String): Set<String> =
        createStatement().use { s -> s.executeQuery(sql).use { r -> buildSet { while (r.next()) add(r.getString(1)) } } }

    private fun Connection.tables() = strings("SELECT name FROM sqlite_master WHERE type = 'table'")
    private fun Connection.columns(table: String) = strings("SELECT name FROM pragma_table_info('$table')")

    /**
     * 以前の Desktop の作り方（番号を記録せずにその頃の現行スキーマを作る）。番号を記録しなかったのは #736 より前、
     * つまり版 13 までなので、今のスキーマを作ってから版 14 以降で足した列を落として版 13 の形にする。
     * **N.sqm を足したら、ここで落とす列・表も足すこと**（[version_is_inferred_from_tables_and_columns] の版の確認が知らせる）。
     */
    private fun createLikeOldDesktop() {
        JdbcSqliteDriver(url).use { NostrDb.Schema.create(it) }
        jdbc { c ->
            c.exec(
                "ALTER TABLE media_server DROP COLUMN protocol",   // 13.sqm（#872）
            )
        }
    }

    private fun open() = DriverFactory(file).createDriver().close()

    @Test
    fun new_file_is_created_with_the_current_version() {
        open()
        jdbc { c ->
            assertEquals(NostrDb.Schema.version, c.long("PRAGMA user_version"))
            assertTrue("ogp_cache" in c.tables())
        }
    }

    @Test
    fun old_desktop_db_with_the_current_schema_is_stamped_and_keeps_its_data() {
        createLikeOldDesktop()
        jdbc { c ->
            c.exec(
                "INSERT INTO app_setting VALUES ('theme', 'dark')",
                // ref_id がある（#423 以後の）DB の送信待ちは本当に未送信なので、12.sqm で捨てない。
                "INSERT INTO publish_queue (event_id, payload, created_at) VALUES ('e1', '{}', 1)",
            )
        }
        open()
        jdbc { c ->
            assertEquals(NostrDb.Schema.version, c.long("PRAGMA user_version"))
            assertEquals(1L, c.long("SELECT count(*) FROM app_setting WHERE key = 'theme'"))
            assertEquals(1L, c.long("SELECT count(*) FROM publish_queue"))
        }
    }

    @Test
    fun old_desktop_db_from_version_9_is_migrated() {
        // 手元の Mac に残っていた DB（2026-07 作成）と同じ形: 9.sqm 以降の表と publish_queue の 2 列が無い。
        createLikeOldDesktop()
        jdbc { c ->
            c.exec(
                "DROP TABLE deleted_event", "DROP TABLE deleted_addr", "DROP TABLE ogp_cache", "DROP TABLE publish_queue",
                "CREATE TABLE publish_queue (event_id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL, " +
                    "created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0)",
                "INSERT INTO publish_queue VALUES ('stale', '{}', 1, 0)",
                "INSERT INTO app_setting VALUES ('theme', 'dark')",
            )
        }
        open()
        jdbc { c ->
            assertEquals(NostrDb.Schema.version, c.long("PRAGMA user_version"))
            assertTrue(c.tables().containsAll(listOf("deleted_event", "deleted_addr", "ogp_cache")))
            assertTrue(c.columns("publish_queue").containsAll(listOf("relays", "ref_id")))
            // #423 より前の送信記録は 12.sqm で捨てる（Android / iOS で移行したときと同じ）。
            assertEquals(0L, c.long("SELECT count(*) FROM publish_queue"))
            assertEquals(1L, c.long("SELECT count(*) FROM app_setting WHERE key = 'theme'"))
        }
        // 2 回目以降は番号どおりで、何もしない。
        open()
        jdbc { c -> assertEquals(1L, c.long("SELECT count(*) FROM app_setting")) }
    }

    @Test
    fun empty_file_is_created_normally() {
        file.createNewFile()
        open()
        jdbc { c -> assertEquals(NostrDb.Schema.version, c.long("PRAGMA user_version")) }
    }

    @Test
    fun version_is_inferred_from_tables_and_columns() {
        val base = setOf("event", "profile")
        fun infer(tables: Set<String>, profile: Set<String> = emptySet(), queue: Set<String> = emptySet()) =
            LegacyDesktopDb.inferVersion(base + tables) { t -> if (t == "profile") profile else if (t == "publish_queue") queue else emptySet() }

        assertEquals(1L, infer(emptySet()))
        assertEquals(2L, infer(setOf("event_tag")))
        assertEquals(5L, infer(setOf("event_tag", "relay", "used_hashtag"), profile = setOf("lud16")))
        assertEquals(6L, infer(setOf("event_tag", "relay", "used_hashtag"), profile = setOf("lud16", "banner")))
        assertEquals(9L, infer(setOf("app_setting", "media_server", "custom_emoji", "publish_queue"), queue = setOf("event_id")))
        assertEquals(13L, infer(setOf("ogp_cache", "deleted_event", "publish_queue"), queue = setOf("ref_id")))
        // 版を上げたら createLikeOldDesktop が版 13 の形を作れているか見直す（その版で足した列・表を落とす）。
        assertEquals(14L, NostrDb.Schema.version)
    }
}
